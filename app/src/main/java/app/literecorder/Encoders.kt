package app.literecorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.view.Surface
import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.FileDescriptor
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** エンコーダの出力先（分割保存では途中でファイルを切り替える） */
interface MuxSink {
    /** トラックを登録して番号を返す（video=true は映像） */
    fun addTrack(format: MediaFormat, video: Boolean): Int
    fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo)

    /** 映像のキーフレームがすぐ欲しいか（分割の切り替え位置に使う） */
    fun wantsKeyFrame(): Boolean = false
}

/**
 * MediaMuxer の薄いラッパー。
 * 全トラックがそろうまで start できないので、それまでのサンプルはメモリに貯めておく。
 */
class Muxer(fd: FileDescriptor, private val expectedTracks: Int) {
    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var added = 0
    var started = false
        private set
    var samples = 0L
        private set
    /** 書き込んだバイト数（サイズ上限の判定用） */
    @Volatile var bytes = 0L
        private set
    private val pending = ArrayList<Triple<Int, ByteArray, MediaCodec.BufferInfo>>()

    @Synchronized
    fun addTrack(format: MediaFormat): Int {
        val t = muxer.addTrack(format)
        added++
        if (added == expectedTracks) {
            muxer.start()
            started = true
            for ((track, data, info) in pending) {
                muxer.writeSampleData(track, ByteBuffer.wrap(data), info)
                samples++
            }
            pending.clear()
        }
        return t
    }

    @Synchronized
    fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        bytes += info.size
        if (started) {
            muxer.writeSampleData(track, buf, info)
            samples++
        } else {
            val data = ByteArray(info.size)
            buf.get(data)
            val copy = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
            pending.add(Triple(track, data, copy))
        }
    }

    @Synchronized
    fun release() {
        if (started) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }
}

/** 出力バッファを Muxer に流す共通処理（時刻を単調増加にそろえる） */
private class TrackWriter(private val sink: MuxSink, private val video: Boolean) {
    var track = -1
    private var lastPts = -1L

    fun add(format: MediaFormat) {
        track = sink.addTrack(format, video)
    }

    fun write(buf: ByteBuffer, info: MediaCodec.BufferInfo, baseUs: Long, pausedUs: Long = 0) {
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || info.size <= 0 || track < 0) return
        var pts = (info.presentationTimeUs - baseUs - pausedUs).coerceAtLeast(0)
        if (pts <= lastPts) pts = lastPts + 1
        lastPts = pts
        info.presentationTimeUs = pts
        buf.position(info.offset)
        buf.limit(info.offset + info.size)
        sink.write(track, buf, info)
    }
}

/**
 * H.264 の画面エンコーダ。入力は Surface（VirtualDisplay の描画先）。
 * Surface のタイムスタンプは System.nanoTime 基準なので、baseUs を引いて 0 始まりにする。
 */
class VideoEncoder(
    val width: Int, val height: Int, fps: Int, bitrate: Int,
    private val sink: MuxSink, private val baseUs: Long, private val clock: PauseClock,
) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val writer = TrackWriter(sink, true)
    val surface: Surface
    private val thread = Thread({ drainLoop() }, "lr-video")

    init {
        val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // 120Hz 画面でも設定した fps 以上はエンコードしない（軽さ優先）
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, fps.toFloat())
            // 画面が止まっていても 0.5 秒ごとにはフレームを出す（シークしやすくする）
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000L)
        }
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
    }

    fun start() = thread.start()

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        var wasPaused = false
        var needKey = false
        var lastKeyRequestNs = 0L
        var lastKeyOutNs = System.nanoTime()
        try {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                val paused = clock.paused
                if (wasPaused && !paused) {
                    // 再開したら、続きがきれいに再生できるようキーフレームを出してもらう
                    runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
                }
                wasPaused = paused
                // 分割の切り替え時刻になったらキーフレームを出してもらう（画面が止まっていると来ないため）
                // 画面が止まっているとキーフレームが何十秒も出ない端末があるので、2 秒ごとには出してもらう
                // （トリミングや分割の位置がずれすぎないように）
                val now = System.nanoTime()
                val keyDue = now - lastKeyOutNs > 2_000_000_000L
                if ((keyDue || sink.wantsKeyFrame()) && now - lastKeyRequestNs > 500_000_000L) {
                    lastKeyRequestNs = System.nanoTime()
                    runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
                }
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    writer.add(codec.outputFormat)
                } else if (idx >= 0) {
                    val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    if (isKey) lastKeyOutNs = System.nanoTime()
                    if (paused) {
                        needKey = true // 一時停止中のフレームは捨てる
                    } else if (needKey && !isKey) {
                        // キーフレームが来るまで捨てる
                    } else {
                        needKey = false
                        codec.getOutputBuffer(idx)?.let { writer.write(it, info, baseUs, clock.pausedUs()) }
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } catch (_: Exception) {
            // エンコーダが止められた
        }
    }

    fun stop() {
        runCatching { codec.signalEndOfInputStream() }
        thread.join(3000)
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { surface.release() }
    }

    companion object {
        /** 端末のエンコーダが受け付けるサイズに収める（16 の倍数・上限内） */
        fun fitSize(w0: Int, h0: Int, shortSide: Int): Pair<Int, Int> {
            val caps = runCatching {
                val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val v = c.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                c.release()
                v
            }.getOrNull()
            var scale = minOf(1.0, shortSide.toDouble() / minOf(w0, h0))
            while (true) {
                val w = align16(w0 * scale)
                val h = align16(h0 * scale)
                if (caps == null || caps.isSizeSupported(w, h) || scale < 0.2) return w to h
                scale *= 0.9
            }
        }

        private fun align16(v: Double) = maxOf(16, (v.toInt() / 16) * 16)
    }
}

/**
 * 一時停止の時計。止まっていた時間の合計を、映像の時刻から引くのに使う。
 * （音声は止まっている間のデータを捨てて、サンプル数から時刻を作るので自然にそろう）
 */
class PauseClock {
    @Volatile var paused = false
        private set
    private var pausedTotalNs = 0L
    private var pauseStartNs = 0L

    @Synchronized
    fun pause() {
        if (paused) return
        pauseStartNs = System.nanoTime()
        paused = true
    }

    @Synchronized
    fun resume() {
        if (!paused) return
        pausedTotalNs += System.nanoTime() - pauseStartNs
        paused = false
    }

    /** これまでに止まっていた時間（µs、今止まっている分は含まない） */
    @Synchronized
    fun pausedUs() = pausedTotalNs / 1000

    /** 開始時刻 startNs からの、止まっていた時間を除いた経過（ms） */
    @Synchronized
    fun activeMs(startNs: Long): Long {
        val now = System.nanoTime()
        val pausedNow = if (paused) now - pauseStartNs else 0
        return (now - startNs - pausedTotalNs - pausedNow) / 1_000_000
    }
}

/** AAC-LC エンコーダ（MP4 の音声トラック・M4A） */
class AacEncoder(
    private val rate: Int, private val channels: Int, bitrate: Int,
    sink: MuxSink,
) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val writer = TrackWriter(sink, false)
    private val info = MediaCodec.BufferInfo()

    init {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** pcm: インターリーブ済み 16bit、samples: short の個数、ptsUs: 録画開始からの時刻 */
    fun encode(pcm: ShortArray, samples: Int, ptsUs: Long) {
        var off = 0
        while (off < samples) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) {
                drain(false)
                continue
            }
            val ib = codec.getInputBuffer(idx) ?: continue
            ib.clear()
            val n = minOf(samples - off, ib.remaining() / 2)
            ib.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, off, n)
            val pts = ptsUs + (off / channels) * 1_000_000L / rate
            codec.queueInputBuffer(idx, 0, n * 2, pts, 0)
            off += n
            drain(false)
        }
    }

    private fun drain(untilEos: Boolean) {
        var tries = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> writer.add(codec.outputFormat)
                idx >= 0 -> {
                    codec.getOutputBuffer(idx)?.let { writer.write(it, info, 0) }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos || ++tries > 100) return
            }
        }
    }

    fun finish() {
        runCatching {
            val idx = codec.dequeueInputBuffer(50_000)
            if (idx >= 0) {
                codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(true)
            }
        }
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}

/**
 * MP3 エンコーダ（jump3r = LAME の Java 移植）。
 * jump3r の LameEncoder は Android に無い javax.sound を使うので、同じ配線を自前で組む。
 */
class Mp3Encoder(private val channels: Int, rate: Int, kbps: Int, var out: OutputStream) {
    private val lame = Lame()
    private val gf: LameGlobalFlags
    private var left = IntArray(0)
    private var right = IntArray(0)
    private var mp3buf = ByteArray(0)

    init {
        val ga = GainAnalysis()
        val bs = BitStream()
        val p = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val vbr = VBRTag()
        val ver = Version()
        val id3 = ID3Tag()
        val rv = Reservoir()
        val tak = Takehiro()
        val parse = Parse()
        val mpg = MPGLib()
        val intf = Interface()
        val common = Common()
        val gaud = GetAudio()
        lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr)
        id3.setModules(bs, ver)
        p.setModules(lame)
        qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, lame.enc.psy)
        rv.setModules(bs)
        tak.setModules(qupvt)
        vbr.setModules(lame, bs, ver)
        gaud.setModules(parse, mpg)
        parse.setModules(ver, id3, p)
        mpg.setModules(intf, common)
        intf.setModules(vbr, common)

        gf = lame.lame_init()
        gf.num_channels = channels
        gf.in_samplerate = rate
        gf.out_samplerate = rate
        gf.mode = if (channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
        gf.brate = kbps
        gf.quality = 7                // 速さ優先（0=最高品質〜9=最速）
        gf.bWriteVbrTag = false       // 固定ビットレートで書きっぱなし（後から先頭を書き換えない）
        id3.id3tag_init(gf)
        gf.write_id3tag_automatic = false
        gf.findReplayGain = false
        check(lame.lame_init_params(gf) >= 0) { "MP3 エンコーダの初期化に失敗しました" }
    }

    /** pcm: インターリーブ済み 16bit、samples: short の個数。書いたバイト数を返す */
    fun encode(pcm: ShortArray, samples: Int): Int {
        val frames = samples / channels
        if (left.size < frames) {
            left = IntArray(frames)
            right = IntArray(frames)
            mp3buf = ByteArray((frames * 5 / 4) + 7200)
        }
        // jump3r の int 入力は 32bit フルスケール
        if (channels == 1) {
            for (i in 0 until frames) left[i] = pcm[i].toInt() shl 16
        } else {
            for (i in 0 until frames) {
                left[i] = pcm[2 * i].toInt() shl 16
                right[i] = pcm[2 * i + 1].toInt() shl 16
            }
        }
        val n = lame.lame_encode_buffer_int(gf, left, if (channels == 1) left else right, frames, mp3buf, 0, mp3buf.size)
        if (n > 0) out.write(mp3buf, 0, n)
        return maxOf(n, 0)
    }

    fun finish() {
        val buf = ByteArray(7200)
        val n = lame.lame_encode_flush(gf, buf, 0, buf.size)
        if (n > 0) out.write(buf, 0, n)
        lame.lame_close(gf)
    }
}

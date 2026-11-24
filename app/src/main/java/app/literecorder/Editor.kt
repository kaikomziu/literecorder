package app.literecorder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * 録ったファイルの軽い編集。どれも再エンコードしないので速くて電池を食わない。
 * - MP4 / M4A のトリミング（映像はキーフレームの位置からしか始められない）
 * - MP4 から音だけ取り出して M4A にする
 * - MP3 のトリミング（フレーム単位で切る）
 */
object Editor {

    /** MP4 / M4A を切り出す。endUs<=0 なら最後まで */
    fun trimMp4(ctx: Context, src: Uri, srcName: String, video: Boolean, startUs: Long, endUs: Long): Saved {
        val ext = if (video) "mp4" else "m4a"
        val mime = if (video) "video/mp4" else "audio/mp4"
        return remux(ctx, src, "${baseOf(srcName)}_trim.$ext", mime, video, audioOnly = false, startUs, endUs)
    }

    /** MP4 から音声トラックだけを M4A として取り出す。音が無ければ null */
    fun extractAudio(ctx: Context, src: Uri, srcName: String): Saved? {
        if (!hasAudio(ctx, src)) return null
        return remux(ctx, src, "${baseOf(srcName)}_audio.m4a", "audio/mp4", false, audioOnly = true, 0, 0)
    }

    private fun baseOf(name: String) = name.substringBeforeLast('.')

    private fun hasAudio(ctx: Context, src: Uri): Boolean {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(ctx, src, null)
            (0 until ex.trackCount).any { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        } catch (_: Exception) {
            false
        } finally {
            ex.release()
        }
    }

    private fun remux(
        ctx: Context, src: Uri, outName: String, outMime: String, outVideo: Boolean,
        audioOnly: Boolean, startUs: Long, endUs: Long,
    ): Saved {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, src, null)
        val entry = Store.create(ctx, outName, outMime, outVideo)
        var ok = false
        var muxer: MediaMuxer? = null
        try {
            val m = MediaMuxer(entry.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val map = HashMap<Int, Int>()
            var bufSize = 1 shl 20
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                val isAudio = mime.startsWith("audio/")
                val isVideo = mime.startsWith("video/")
                if (!(isAudio || (isVideo && !audioOnly))) continue
                ex.selectTrack(i)
                map[i] = m.addTrack(f)
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) bufSize = maxOf(bufSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            }
            check(map.isNotEmpty())
            m.start()
            // 映像があればキーフレームの位置まで戻ってから始める（そこからしか正しく再生できない）。
            // 音もそのキーフレームの時刻から入れて、頭に無音ができないようにする
            if (startUs > 0) ex.seekTo(keyFrameBefore(ctx, src, startUs, audioOnly) ?: startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buf = ByteBuffer.allocate(maxOf(bufSize, 4 shl 20))
            val info = MediaCodec.BufferInfo()
            val last = HashMap<Int, Long>()
            var baseUs = -1L
            var written = 0
            while (true) {
                val t = ex.sampleTrackIndex
                if (t < 0) break
                val time = ex.sampleTime
                if (endUs > 0 && time > endUs) break
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                if (baseUs < 0) baseUs = time
                var pts = (time - baseUs).coerceAtLeast(0)
                val prev = last[t] ?: -1
                if (pts <= prev) pts = prev + 1
                last[t] = pts
                val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, pts, flags)
                m.writeSampleData(map.getValue(t), buf, info)
                written++
                ex.advance()
            }
            check(written > 0)
            m.stop()
            ok = true
        } finally {
            runCatching { muxer?.release() }
            ex.release()
            runCatching { entry.pfd.close() }
            if (!ok) Store.discard(ctx, entry.uri)
        }
        check(Store.publish(ctx, entry.uri))
        return Saved(entry.uri, outName, outMime)
    }

    /** startUs 以前で最後の映像キーフレームの時刻（映像が無ければ null） */
    private fun keyFrameBefore(ctx: Context, src: Uri, startUs: Long, audioOnly: Boolean): Long? {
        if (audioOnly) return null
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(ctx, src, null)
            val v = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: return null
            ex.selectTrack(v)
            ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            ex.sampleTime.takeIf { it >= 0 }
        } catch (_: Exception) {
            null
        } finally {
            ex.release()
        }
    }

    /**
     * MP3 を切り出す。このアプリの MP3 は固定ビットレートなので、時間からバイト位置を出して
     * フレームの区切り（同期ワード）にそろえてコピーする。
     */
    fun trimMp3(ctx: Context, src: Uri, srcName: String, durationUs: Long, startUs: Long, endUs: Long): Saved {
        val outName = "${baseOf(srcName)}_trim.mp3"
        val inPfd = checkNotNull(ctx.contentResolver.openFileDescriptor(src, "r"))
        val entry = Store.create(ctx, outName, "audio/mpeg", false)
        var ok = false
        try {
            FileInputStream(inPfd.fileDescriptor).use { input ->
                val total = inPfd.statSize
                val ch = input.channel
                val bytesPerUs = total.toDouble() / durationUs.coerceAtLeast(1)
                val from = findSync(ch, (startUs * bytesPerUs).toLong(), total)
                val to = if (endUs <= 0 || endUs >= durationUs) total else findSync(ch, (endUs * bytesPerUs).toLong(), total)
                check(to > from)
                FileOutputStream(entry.pfd.fileDescriptor).use { out ->
                    ch.position(from)
                    val buf = ByteArray(64 * 1024)
                    var left = to - from
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        left -= n
                    }
                }
            }
            ok = true
        } finally {
            inPfd.close()
            runCatching { entry.pfd.close() }
            if (!ok) Store.discard(ctx, entry.uri)
        }
        check(Store.publish(ctx, entry.uri))
        return Saved(entry.uri, outName, "audio/mpeg")
    }

    /** pos 以降で最初の MP3 フレームの頭（0xFFE の同期ワード）を探す */
    private fun findSync(ch: java.nio.channels.FileChannel, pos: Long, total: Long): Long {
        if (pos <= 0) return 0
        val win = ByteBuffer.allocate(8192)
        var p = pos.coerceAtMost(total)
        while (p < total - 1) {
            win.clear()
            ch.position(p)
            val n = ch.read(win)
            if (n <= 1) break
            for (i in 0 until n - 1) {
                val b0 = win.get(i).toInt() and 0xFF
                val b1 = win.get(i + 1).toInt() and 0xFF
                // 同期ワード 11 ビット＋ビットレート欄が「使えない値」でないこと
                if (b0 == 0xFF && b1 and 0xE0 == 0xE0 && b1 and 0x06 != 0) return p + i
            }
            p += n - 1
        }
        return total
    }
}

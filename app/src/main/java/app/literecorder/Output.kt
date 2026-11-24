package app.literecorder

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/** MediaStore への保存まわり（Movies/LiteRecorder か Music/LiteRecorder） */
object Store {
    class Entry(val uri: Uri, val pfd: ParcelFileDescriptor, val name: String)

    fun ext(f: OutFormat) = when (f) {
        OutFormat.MP4 -> "mp4"
        OutFormat.MP3 -> "mp3"
        OutFormat.M4A -> "m4a"
    }

    fun mime(f: OutFormat) = when (f) {
        OutFormat.MP4 -> "video/mp4"
        OutFormat.MP3 -> "audio/mpeg"
        OutFormat.M4A -> "audio/mp4"
    }

    private fun collection(video: Boolean) = if (video) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }

    /** 書き込み中（他のアプリから見えない）のファイルを作る */
    fun create(ctx: Context, displayName: String, mime: String, video: Boolean): Entry {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                (if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC) + "/LiteRecorder",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(ctx.contentResolver.insert(collection(video), values)) { ctx.getString(R.string.err_storage) }
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw")
        if (pfd == null) {
            discard(ctx, uri)
            error(ctx.getString(R.string.err_storage))
        }
        return Entry(uri, pfd, displayName)
    }

    /** 書き終わったファイルを公開する */
    fun publish(ctx: Context, uri: Uri) = runCatching {
        ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }.isSuccess

    fun discard(ctx: Context, uri: Uri) {
        runCatching { ctx.contentResolver.delete(uri, null, null) }
    }

    /** 端末の空き容量（バイト） */
    @Suppress("DEPRECATION")
    fun freeBytes(): Long = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    /**
     * 強制終了などで書きかけのまま残ったファイルを片付ける。
     * MP3 は途中まででも再生できるので公開し、MP4 / M4A は最後の情報が無く再生できないので消す。
     */
    fun cleanupPending(ctx: Context): Int {
        var n = 0
        for (video in listOf(true, false)) {
            val base = collection(video)
            val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE)
            val sel = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING} = 1"
            val args = arrayOf(if (video) "Movies/LiteRecorder%" else "Music/LiteRecorder%")
            val cursor = runCatching {
                if (Build.VERSION.SDK_INT >= 30) {
                    ctx.contentResolver.query(base, cols, Bundle().apply {
                        putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
                        putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
                    }, null)
                } else {
                    @Suppress("DEPRECATION")
                    ctx.contentResolver.query(MediaStore.setIncludePending(base), cols, sel, args, null)
                }
            }.getOrNull() ?: continue
            cursor.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(base, c.getLong(0))
                    // 強制終了したファイルは MediaStore の SIZE が更新されていないので、実際の大きさを見る
                    val size = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: 0L
                    if (c.getString(1) == "audio/mpeg" && size > 0) publish(ctx, uri) else discard(ctx, uri)
                    n++
                }
            }
        }
        return n
    }
}

/** 保存できたファイル */
data class Saved(val uri: Uri, val name: String, val mime: String)

/**
 * MP4 / M4A の書き出し先。分割保存がオンなら、決めた時間ごとに次のファイルへ切り替える。
 * 映像があるときはキーフレームの位置で切り替えるので、どのファイルも頭から再生できる。
 * 録画中に強制終了されても、それまでに切り替えたファイルは保存済みで残る。
 */
class SegmentedMuxer(
    private val ctx: Context,
    private val format: OutFormat,
    private val baseName: String,
    private val expectedTracks: Int,
    private val splitUs: Long,
) : MuxSink {
    private val formats = arrayOfNulls<MediaFormat>(2) // 0=映像 1=音声
    private val curTrack = intArrayOf(-1, -1)
    private val lastPts = longArrayOf(-1, -1)
    private var segStartUs = 0L
    private var nextSplitUs = if (splitUs > 0) splitUs else Long.MAX_VALUE
    private var part = 1
    private var prevBytes = 0L
    @Volatile private var wantKey = false
    private lateinit var cur: Store.Entry
    private lateinit var mux: Muxer
    val saved = ArrayList<Saved>()

    /** 全ファイル合計の書き込みバイト数 */
    val bytes: Long get() = prevBytes + mux.bytes

    init {
        open()
    }

    private fun open() {
        val name = if (splitUs > 0) "${baseName}_$part" else baseName
        cur = Store.create(ctx, "$name.${Store.ext(format)}", Store.mime(format), format.isVideo)
        mux = Muxer(cur.pfd.fileDescriptor, expectedTracks)
        curTrack.fill(-1)
        lastPts.fill(-1)
    }

    @Synchronized
    override fun addTrack(format: MediaFormat, video: Boolean): Int {
        val id = if (video) 0 else 1
        formats[id] = format
        curTrack[id] = mux.addTrack(format)
        return id
    }

    @Synchronized
    override fun write(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val pts = info.presentationTimeUs
        if (pts >= nextSplitUs && mux.started) {
            val boundary = if (format.isVideo) track == 0 && info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 else true
            if (boundary) rotate(pts) else if (format.isVideo) wantKey = true
        }
        var p = (pts - segStartUs).coerceAtLeast(0)
        if (p <= lastPts[track]) p = lastPts[track] + 1
        lastPts[track] = p
        info.presentationTimeUs = p
        mux.write(curTrack[track], buf, info)
    }

    private fun rotate(pts: Long) {
        finishCurrent()
        part++
        open()
        formats.forEachIndexed { i, f -> if (f != null) curTrack[i] = mux.addTrack(f) }
        segStartUs = pts
        nextSplitUs = pts + splitUs
        wantKey = false
    }

    override fun wantsKeyFrame() = wantKey

    private fun finishCurrent() {
        val ok = mux.started && mux.samples > 0
        prevBytes += mux.bytes
        mux.release()
        runCatching { cur.pfd.close() }
        if (ok && Store.publish(ctx, cur.uri)) saved += Saved(cur.uri, cur.name, Store.mime(format)) else Store.discard(ctx, cur.uri)
    }

    /** 最後のファイルを閉じて、保存できたファイルの一覧を返す */
    @Synchronized
    fun finish(): List<Saved> {
        finishCurrent()
        return saved
    }
}

/** MP3 の書き出し先（分割保存に対応）。MP3 はフレームが独立しているので、どこで切っても再生できる */
class Mp3Output(private val ctx: Context, private val baseName: String, private val splitUs: Long) {
    private var part = 1
    private var nextSplitUs = if (splitUs > 0) splitUs else Long.MAX_VALUE
    private lateinit var cur: Store.Entry
    lateinit var stream: BufferedOutputStream
        private set
    private var curBytes = 0L
    private var prevBytes = 0L
    val saved = ArrayList<Saved>()
    val bytes get() = prevBytes + curBytes

    init {
        open()
    }

    private fun open() {
        val name = if (splitUs > 0) "${baseName}_$part" else baseName
        cur = Store.create(ctx, "$name.mp3", "audio/mpeg", false)
        // 強制終了されても失うのは 1 秒ほどで済むよう、小さめのバッファで書く
        stream = BufferedOutputStream(FileOutputStream(cur.pfd.fileDescriptor), 16 * 1024)
        curBytes = 0
    }

    fun added(n: Int) {
        curBytes += n
    }

    /** 切り替えの時刻なら次のファイルを開いて true を返す */
    fun rotateIfNeeded(ptsUs: Long): Boolean {
        if (ptsUs < nextSplitUs) return false
        finishCurrent()
        part++
        open()
        nextSplitUs = ptsUs + splitUs
        return true
    }

    private fun finishCurrent() {
        val ok = runCatching { stream.flush() }.isSuccess && curBytes > 0
        runCatching { stream.close() }
        runCatching { cur.pfd.close() }
        prevBytes += curBytes
        if (ok && Store.publish(ctx, cur.uri)) saved += Saved(cur.uri, cur.name, "audio/mpeg") else Store.discard(ctx, cur.uri)
    }

    fun finish(): List<Saved> {
        finishCurrent()
        return saved
    }
}

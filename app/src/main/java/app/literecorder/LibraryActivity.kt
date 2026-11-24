package app.literecorder

import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.view.ContextThemeWrapper
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import java.util.concurrent.Executors

/** 録画一覧（このアプリで録ったファイルを MediaStore から読む） */
class LibraryActivity : Activity() {

    private data class Item(val uri: Uri, val name: String, val mime: String, val size: Long, val dateSec: Long, val durationMs: Long) {
        val isVideo get() = mime.startsWith("video")
    }

    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2)
    private lateinit var list: LinearLayout
    private var pendingDelete: Item? = null

    private fun dp(v: Float) = Ui.dp(this, v)
    private fun dpi(v: Int) = Ui.dpi(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "‹"
                textSize = 30f
                setTextColor(Ui.TEXT)
                setPadding(0, 0, dpi(14), dpi(4))
                contentDescription = "Back"
                setOnClickListener { finish() }
            })
            addView(TextView(context).apply {
                text = getString(R.string.library_title)
                textSize = 24f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Ui.TEXT)
            })
        }
        col.addView(header)
        col.addView(Ui.hint(this, getString(R.string.lib_note)))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list, Ui.matchWrap(dpi(14)))
        setContentView(ScrollView(this).apply {
            addView(col)
            setOnApplyWindowInsetsListener { _, insets ->
                val (top, bottom) = if (Build.VERSION.SDK_INT >= 30) {
                    val b = insets.getInsets(WindowInsets.Type.systemBars())
                    b.top to b.bottom
                } else {
                    @Suppress("DEPRECATION")
                    insets.systemWindowInsetTop to insets.systemWindowInsetBottom
                }
                col.setPadding(dpi(20), dpi(16) + top, dpi(20), dpi(24) + bottom)
                insets
            }
        })
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun reload() {
        pool.execute {
            val items = query()
            ui.post { render(items) }
        }
    }

    private fun query(): List<Item> {
        val out = ArrayList<Item>()
        val targets = listOf(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Movies/LiteRecorder%",
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Music/LiteRecorder%",
        )
        val cols = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DURATION,
        )
        for ((collection, path) in targets) {
            runCatching {
                contentResolver.query(collection, cols, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf(path), null)?.use { c ->
                    while (c.moveToNext()) {
                        out += Item(
                            uri = ContentUris.withAppendedId(collection, c.getLong(0)),
                            name = c.getString(1) ?: "",
                            mime = c.getString(2) ?: "",
                            size = c.getLong(3),
                            dateSec = c.getLong(4),
                            durationMs = c.getLong(5),
                        )
                    }
                }
            }
        }
        return out.sortedByDescending { it.dateSec }
    }

    private fun render(items: List<Item>) {
        list.removeAllViews()
        if (items.isEmpty()) {
            list.addView(TextView(this).apply {
                text = getString(R.string.lib_empty)
                setTextColor(Ui.SUB)
                gravity = Gravity.CENTER
                setPadding(0, dpi(60), 0, 0)
            }, Ui.matchWrap())
            return
        }
        for (item in items) list.addView(row(item), Ui.matchWrap(dpi(10)))
    }

    private fun row(item: Item): View {
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Ui.rounded(Ui.SEG_BG, dp(10f))
            clipToOutline = true
        }
        val thumbBox = FrameLayout(this).apply {
            addView(thumb, FrameLayout.LayoutParams(dpi(64), dpi(64)))
            if (!item.isVideo) {
                // 音声はサムネイルが無いので、拡張子を大きく表示する
                addView(TextView(context).apply {
                    text = item.name.substringAfterLast('.', "").uppercase()
                    setTextColor(Ui.ACCENT)
                    setTypeface(typeface, Typeface.BOLD)
                    textSize = 13f
                    gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(dpi(64), dpi(64)))
            }
        }
        if (item.isVideo) {
            pool.execute {
                val bmp: Bitmap? = runCatching { contentResolver.loadThumbnail(item.uri, Size(192, 192), null) }.getOrNull()
                if (bmp != null) ui.post { thumb.setImageBitmap(bmp) }
            }
        }
        val dur = item.durationMs / 1000
        val meta = buildString {
            if (dur > 0) append("%d:%02d ・ ".format(dur / 60, dur % 60))
            append(Formatter.formatShortFileSize(this@LibraryActivity, item.size))
            append(" ・ ")
            append(DateFormat.format("M/d HH:mm", Date(item.dateSec * 1000)))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(12), 0, dpi(4), 0)
            addView(TextView(context).apply {
                text = item.name
                setTextColor(Ui.TEXT)
                textSize = 14f
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE // 拡張子が見えるよう真ん中を省略
            })
            addView(TextView(context).apply {
                text = meta
                setTextColor(Ui.SUB)
                textSize = 12f
            })
        }
        fun action(label: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
            text = label
            setTextColor(color)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dpi(8), dpi(10), dpi(8), dpi(10))
            setOnClickListener { onClick() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(Ui.CARD, dp(14f))
            setPadding(dpi(10), dpi(10), dpi(6), dpi(10))
            addView(thumbBox)
            addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val more = action("•••", Ui.TEXT) {}
            more.contentDescription = getString(R.string.more)
            more.setOnClickListener { showMenu(more, item) }
            addView(more)
            setOnClickListener { open(item) }
        }
    }

    /** 「•••」のメニュー */
    private fun showMenu(anchor: View, item: Item) {
        val menu = PopupMenu(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault), anchor)
        menu.menu.add(0, 1, 0, R.string.share)
        menu.menu.add(0, 2, 1, R.string.rename)
        menu.menu.add(0, 3, 2, R.string.trim)
        if (item.isVideo) menu.menu.add(0, 4, 3, R.string.extract_audio)
        menu.menu.add(0, 5, 4, R.string.delete)
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> share(item)
                2 -> rename(item)
                3 -> trim(item)
                4 -> runEdit { Editor.extractAudio(this, item.uri, item.name) ?: error(getString(R.string.no_audio_track)) }
                5 -> confirmDelete(item)
            }
            true
        }
        menu.show()
    }

    /** 編集を裏で実行して、終わったら結果を知らせて一覧を読み直す */
    private fun runEdit(work: () -> Saved) {
        Toast.makeText(this, R.string.working, Toast.LENGTH_SHORT).show()
        pool.execute {
            val r = runCatching(work)
            ui.post {
                r.onSuccess { Toast.makeText(this, getString(R.string.toast_saved, it.name), Toast.LENGTH_SHORT).show() }
                r.onFailure { Toast.makeText(this, it.message ?: getString(R.string.edit_failed), Toast.LENGTH_LONG).show() }
                reload()
            }
        }
    }

    private fun rename(item: Item) {
        val ext = item.name.substringAfterLast('.', "")
        val input = EditText(this).apply {
            setText(item.name.substringBeforeLast('.'))
            setSelectAllOnFocus(true)
            setSingleLine()
        }
        val box = FrameLayout(this).apply {
            setPadding(dpi(20), dpi(8), dpi(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.rename)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                // ファイル名に使えない文字は _ にする
                val name = input.text.toString().trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (name.isEmpty()) return@setPositiveButton
                val ok = runCatching {
                    contentResolver.update(item.uri, ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, if (ext.isEmpty()) name else "$name.$ext")
                    }, null, null) > 0
                }.getOrDefault(false)
                if (!ok) Toast.makeText(this, R.string.rename_failed, Toast.LENGTH_LONG).show()
                reload()
            }
            .show()
    }

    /** トリミング: 開始・終了をスライダーで選ぶ（秒単位） */
    private fun trim(item: Item) {
        val durSec = (item.durationMs / 1000).toInt()
        if (durSec < 2) {
            Toast.makeText(this, R.string.edit_failed, Toast.LENGTH_SHORT).show()
            return
        }
        fun fmt(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)
        var start = 0
        var end = durSec
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(8), dpi(20), 0)
        }
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = Ui.rounded(Color.BLACK, dp(10f))
            clipToOutline = true
        }
        // 動画なら開始位置のコマを表示する
        val retriever = if (item.isVideo) runCatching { MediaMetadataRetriever().apply { setDataSource(this@LibraryActivity, item.uri) } }.getOrNull() else null
        fun showFrame(sec: Int) {
            val r = retriever ?: return
            pool.execute {
                val bmp = runCatching { r.getScaledFrameAtTime(sec * 1_000_000L, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC, 480, 480) }.getOrNull()
                ui.post { if (bmp != null) preview.setImageBitmap(bmp) }
            }
        }
        if (retriever != null) {
            col.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(200)))
            showFrame(0)
        }
        lateinit var endSlider: Slider
        val startSlider = Slider(this, { getString(R.string.trim_start, fmt(it)) }, 0, durSec - 1, { true }) {
            start = it
            if (end <= start) { end = start + 1; endSlider.set(end) }
            showFrame(start)
        }
        endSlider = Slider(this, { getString(R.string.trim_end, fmt(it)) }, 1, durSec, { true }) {
            end = it
            if (start >= end) { start = end - 1; startSlider.set(start) }
        }
        startSlider.set(0)
        endSlider.set(durSec)
        col.addView(startSlider.root)
        col.addView(endSlider.root)
        if (item.isVideo) col.addView(Ui.hint(this, getString(R.string.trim_hint_video)))
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.trim)
            .setView(col)
            .setOnDismissListener { runCatching { retriever?.release() } }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val sUs = start * 1_000_000L
                val eUs = if (end >= durSec) 0L else end * 1_000_000L
                runEdit {
                    if (item.mime == "audio/mpeg") Editor.trimMp3(this, item.uri, item.name, item.durationMs * 1000, sUs, eUs)
                    else Editor.trimMp4(this, item.uri, item.name, item.isVideo, sUs, eUs)
                }
            }
            .show()
    }

    private fun open(item: Item) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, item.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure { Toast.makeText(this, R.string.toast_no_app, Toast.LENGTH_SHORT).show() }
    }

    private fun share(item: Item) {
        val send = Intent(Intent.ACTION_SEND).setType(item.mime)
            .putExtra(Intent.EXTRA_STREAM, item.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.share)))
    }

    private fun confirmDelete(item: Item) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage(getString(R.string.delete_confirm, item.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> delete(item) }
            .show()
            .getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Ui.ACCENT)
    }

    @Suppress("DEPRECATION")
    private fun delete(item: Item) {
        try {
            contentResolver.delete(item.uri, null, null)
            Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show()
            reload()
        } catch (e: SecurityException) {
            // 入れ直した後など、アプリが持ち主でないファイルはシステムの確認画面から消す
            pendingDelete = item
            val sender = when {
                Build.VERSION.SDK_INT >= 30 -> MediaStore.createDeleteRequest(contentResolver, listOf(item.uri)).intentSender
                e is RecoverableSecurityException -> e.userAction.actionIntent.intentSender
                else -> null
            }
            if (sender != null) startIntentSenderForResult(sender, 1, null, 0, 0, 0) else Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Activity の標準 API を使う")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1) return
        val item = pendingDelete
        pendingDelete = null
        if (resultCode == RESULT_OK && item != null) {
            // Android 10 は許可だけなので、もう一度消す
            if (Build.VERSION.SDK_INT < 30) runCatching { contentResolver.delete(item.uri, null, null) }
            Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show()
        }
        reload()
    }
}

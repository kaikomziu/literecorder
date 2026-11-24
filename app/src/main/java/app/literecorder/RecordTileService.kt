package app.literecorder

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * クイック設定（通知パネル）の録画タイル。
 * 録画中なら止める。止まっていれば、画面キャプチャの許可を出すためにアプリを開いて録画を始める。
 */
class RecordTileService : TileService() {

    override fun onStartListening() {
        val tile = qsTile ?: return
        val active = RecorderService.state != RecorderService.State.IDLE
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (active) getString(R.string.tile_recording) else null
        }
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        if (RecorderService.state == RecorderService.State.RECORDING ||
            RecorderService.state == RecorderService.State.COUNTDOWN
        ) {
            RecorderService.stop(this)
            return
        }
        if (RecorderService.state != RecorderService.State.IDLE) return
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_QUICK_START)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

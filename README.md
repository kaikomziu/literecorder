# LiteRecorder

Kotlin 製の超軽量な Android 録画・録音アプリ（APK 約 260KB、AndroidX なし、インターネット権限なし）。

- サイト: https://kaikomziu.github.io/literecorder/
- ダウンロード: [最新版の APK](https://github.com/kaikomziu/literecorder/releases/latest/download/LiteRecorder.apk)（[すべてのバージョン](https://github.com/kaikomziu/literecorder/releases)）

## できること
- **MP4**: 画面録画（H.264）＋音声（AAC）。向き（開始時のまま / 縦 / 横）・解像度・30/60fps・画質を選べる
- **MP3 / M4A**: 音だけ録音（96/128/192kbps）。MP3 は jump3r（LAME の Java 移植）、M4A は端末の AAC エンコーダ
- 録る音: なし / マイク / 内部音 / 両方。それぞれの音量（0〜200%）とマイクのノイズ除去
- ボイスチェンジャー（マイクにだけかかる）: 高い声 / 低い声（半音 1〜12 で調整）/ ロボ / エコー。イヤホンで試し聴きできる
- 一時停止・再開（アプリ・通知・浮かぶボタン）
- 画面に浮かぶ操作ボタン（「他のアプリの上に表示」の許可が要る）
- 録画前のカウントダウン（なし / 3秒 / 5秒）
- クイック設定タイルから録画開始・停止
- 自動停止（時間 5分〜1時間 / サイズ 100MB〜1GB）
- 録画一覧（再生・共有・削除）
- 日本語 / English（Android 13 以降はアプリごとの言語設定にも対応）

保存先: 動画 `Movies/LiteRecorder`、音声 `Music/LiteRecorder`

## 動作環境
Android 10 以上（内部音の録音に Android 10 の AudioPlaybackCapture を使うため）

## ビルド
```
gradlew assembleRelease
```
→ `app/build/outputs/apk/release/app-release.apk`（デバッグ鍵で署名済み、そのまま入れられる）

## リリース
1. `app/build.gradle.kts` の `versionCode` と `versionName` を上げる
2. `powershell -ExecutionPolicy Bypass -File tools/release.ps1 "変更点"` を実行  
   （ビルド → `LiteRecorder.apk` と `LiteRecorder-<版>.apk` を GitHub Releases に上げる）

サイトは `docs/`（GitHub Pages）。スクショやアイコンは `python tools/make_assets.py <スクショ1> <スクショ2>` で作り直せる。

## ライセンス
MP3 エンコードに [jump3r](https://github.com/Sciss/jump3r)（LGPL）を使っています。

## ファイル構成
| ファイル | 役割 |
|---|---|
| `MainActivity.kt` | メイン画面（コードで組み立て） |
| `RecorderService.kt` | フォアグラウンドサービス・通知・開始/停止 |
| `Session.kt` | 1 回分の録画/録音と MediaStore への保存 |
| `AudioEngine.kt` | マイク・内部音の取り込みとミックス |
| `VoiceChanger.kt` | ボイスチェンジャー |
| `Encoders.kt` | H.264 / AAC / MP3 エンコーダ、MP4 マルチプレクサ、一時停止の時計 |
| `Overlay.kt` | 画面に浮かぶ操作ボタンとカウントダウン |
| `RecordTileService.kt` | クイック設定タイル |
| `VoicePreview.kt` | ボイスチェンジャーの試し聴き |
| `LibraryActivity.kt` | 録画一覧 |
| `Ui.kt` | 画面部品（カード・切り替えボタン・スライダー） |
| `Settings.kt` | 設定 |

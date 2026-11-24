# LiteRecorder

Kotlin 製の超軽量な Android 録画・録音アプリ（APK 約 220KB、AndroidX なし）。

- サイト: https://kaikomziu.github.io/literecorder/
- ダウンロード: [最新版の APK](https://github.com/kaikomziu/literecorder/releases/latest/download/LiteRecorder.apk)（[すべてのバージョン](https://github.com/kaikomziu/literecorder/releases)）

## できること
- **MP4**: 画面録画（H.264）＋音声（AAC）
- **MP3**: 音だけ録音（jump3r = LAME の Java 移植でエンコード）
- 録る音: なし / マイク / 内部音 / 両方（MP3 は「なし」以外）
- ボイスチェンジャー（マイクにだけかかる）: 高い声 / 低い声 / ロボ / エコー
- 解像度（短い辺 1080/720/480）・30/60fps・画質 3 段階、MP3 は 96/128/192kbps
- 通知の「停止して保存」かアプリのボタンで停止

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
| `MainActivity.kt` | 画面（コードで組み立て） |
| `RecorderService.kt` | フォアグラウンドサービス・通知・開始/停止 |
| `Session.kt` | 1 回分の録画/録音と MediaStore への保存 |
| `AudioEngine.kt` | マイク・内部音の取り込みとミックス |
| `VoiceChanger.kt` | ボイスチェンジャー |
| `Encoders.kt` | H.264 / AAC / MP3 エンコーダと MP4 マルチプレクサ |
| `Settings.kt` | 設定 |

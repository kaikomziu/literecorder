# LiteRecorder のリリース用スクリプト
# 使い方: app/build.gradle.kts の versionCode / versionName を上げてから
#   powershell -ExecutionPolicy Bypass -File tools/release.ps1 "変更点のメモ"
param([string]$Notes = "")

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

# build.gradle.kts からバージョンを読む
$gradle = Get-Content "app/build.gradle.kts" -Raw
if ($gradle -notmatch 'versionName = "([^"]+)"') { throw "versionName が見つかりません" }
$ver = $Matches[1]
$tag = "v$ver"
Write-Host "LiteRecorder $ver をビルドします"

& ./gradlew.bat assembleRelease --console=plain -q
if ($LASTEXITCODE -ne 0) { throw "ビルドに失敗しました" }

# サイトのダウンロードリンクは固定名 LiteRecorder.apk を指すので、両方の名前で上げる
New-Item -ItemType Directory -Force release | Out-Null
$apk = "app/build/outputs/apk/release/app-release.apk"
Copy-Item $apk "release/LiteRecorder.apk" -Force
Copy-Item $apk "release/LiteRecorder-$ver.apk" -Force

if (-not $Notes) { $Notes = "LiteRecorder $ver" }
gh release create $tag "release/LiteRecorder.apk" "release/LiteRecorder-$ver.apk" --title "LiteRecorder $ver" --notes $Notes
if ($LASTEXITCODE -ne 0) { throw "リリースの作成に失敗しました" }
Write-Host "公開しました: https://github.com/kaikomziu/literecorder/releases/tag/$tag"

<#
  Jarvis in-app update — update.json generator (Windows PowerShell)
  Hinglish guide: docs/in-app-update.md

  Usage:
    ./scripts/make_update_json.ps1 -Apk <apk> -LatestVersionCode <n> -LatestVersionName <x.y.z> `
        -ApkUrl <https://...> [-MinSupportedVersionCode <n>] [-Mandatory <bool>] `
        [-ReleaseNotesFile <file>] [-OutFile update.json]

  Example:
    ./scripts/make_update_json.ps1 -Apk app\build\outputs\apk\release\app-release.apk `
        -LatestVersionCode 162 -LatestVersionName 1.16.0 `
        -ApkUrl https://github.com/red-code-cli/Jarvis/releases/download/v1.16.0/jarvis-v1.16.0.apk `
        -MinSupportedVersionCode 160 -Mandatory:$true -ReleaseNotesFile notes.txt
#>
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [Parameter(Mandatory = $true)][int]$LatestVersionCode,
    [Parameter(Mandatory = $true)][string]$LatestVersionName,
    [Parameter(Mandatory = $true)][string]$ApkUrl,
    [int]$MinSupportedVersionCode = 0,
    [switch]$Mandatory,
    [string]$ReleaseNotesFile = "",
    [string]$OutFile = "update.json"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -Path $Apk -PathType Leaf)) {
    Write-Error "APK nahi mila: $Apk"
    exit 1
}
if (-not ($ApkUrl -match '^https://')) {
    Write-Error "apkUrl https:// se shuru hona chahiye (HTTP reject hota hai)."
    exit 1
}

$hash = (Get-FileHash -Path $Apk -Algorithm SHA256).Hash.ToLower()
$size = (Get-Item -Path $Apk).Length

if ($ReleaseNotesFile -ne "" -and (Test-Path -Path $ReleaseNotesFile)) {
    $notes = Get-Content -Path $ReleaseNotesFile -Raw
} else {
    $notes = "Jarvis $LatestVersionName update."
}

$payload = [ordered]@{
    latestVersionCode        = $LatestVersionCode
    latestVersionName        = $LatestVersionName
    minSupportedVersionCode  = $MinSupportedVersionCode
    apkUrl                   = $ApkUrl
    apkSha256                = $hash
    apkSizeBytes             = $size
    releaseNotes             = $notes
    mandatory                = [bool]$Mandatory
    publishedAt              = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
}

$json = $payload | ConvertTo-Json -Depth 5
$json | Set-Content -Path $OutFile -Encoding UTF8

Write-Host "✅ $OutFile likh diya:"
Write-Host "   versionCode=$LatestVersionCode versionName=$LatestVersionName mandatory=$([bool]$Mandatory)"
Write-Host "   sha256=$hash"
Write-Host "   size=$size bytes"

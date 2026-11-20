#!/usr/bin/env bash
#
# Jarvis in-app update — update.json generator (Hinglish guide: docs/in-app-update.md)
#
# Ye script kisi bhi APK ka SHA-256 + size nikaal kar ek valid update.json banata hai jo
# app ke in-app updater ko feed hota hai.
#
# Usage:
#   scripts/make_update_json.sh <apk> <latestVersionCode> <latestVersionName> <apkUrl> \
#       [minSupportedVersionCode] [mandatory:true|false] [releaseNotesFile] [outFile]
#
# Example:
#   scripts/make_update_json.sh app/build/outputs/apk/release/app-release.apk \
#       162 1.16.0 https://github.com/red-code-cli/Jarvis/releases/download/v1.16.0/jarvis-v1.16.0.apk \
#       160 true notes.txt update.json
#
set -euo pipefail

APK="${1:?usage: make_update_json.sh <apk> <versionCode> <versionName> <apkUrl> [minSupported] [mandatory] [notesFile] [outFile]}"
CODE="${2:?latestVersionCode chahiye}"
NAME="${3:?latestVersionName chahiye}"
URL="${4:?apkUrl chahiye (https only)}"
MIN="${5:-0}"
MAND="${6:-false}"
NOTES_FILE="${7:-}"
OUT="${8:-update.json}"

if [ ! -f "$APK" ]; then
    echo "ERROR: APK nahi mila: $APK" >&2
    exit 1
fi

case "$URL" in
    https://*) ;;
    *) echo "ERROR: apkUrl https:// se shuru hona chahiye (HTTP reject hota hai)." >&2; exit 1 ;;
esac

case "$MAND" in
    true|false) ;;
    *) echo "ERROR: mandatory sirf true ya false ho sakta hai." >&2; exit 1 ;;
esac

# SHA-256 (sha256sum ya macOS shasum)
if command -v sha256sum >/dev/null 2>&1; then
    SHA="$(sha256sum "$APK" | awk '{print $1}')"
else
    SHA="$(shasum -a 256 "$APK" | awk '{print $1}')"
fi

# File size in bytes (GNU stat ya BSD stat)
if SIZE="$(stat -c%s "$APK" 2>/dev/null)"; then
    :
else
    SIZE="$(stat -f%z "$APK")"
fi

if [ -n "$NOTES_FILE" ] && [ -f "$NOTES_FILE" ]; then
    NOTES="$(cat "$NOTES_FILE")"
else
    NOTES="Jarvis $NAME update."
fi

# Minimal JSON string escaping (backslash, quote, newline, tab).
escape() {
    printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' | awk 'BEGIN{ORS="\\n"} {gsub(/\t/,"\\t"); print} END{}' | sed -e 's/\\n$//'
}

NOTES_ESC="$(escape "$NOTES")"
NAME_ESC="$(escape "$NAME")"

cat > "$OUT" <<JSON
{
  "latestVersionCode": $CODE,
  "latestVersionName": "$NAME_ESC",
  "minSupportedVersionCode": $MIN,
  "apkUrl": "$URL",
  "apkSha256": "$SHA",
  "apkSizeBytes": $SIZE,
  "releaseNotes": "$NOTES_ESC",
  "mandatory": $MAND,
  "publishedAt": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
JSON

echo "✅ $OUT likh diya:"
echo "   versionCode=$CODE versionName=$NAME mandatory=$MAND minSupported=$MIN"
echo "   sha256=$SHA"
echo "   size=$SIZE bytes"

#!/usr/bin/env bash
set -euo pipefail

UPSTREAM_REF="${1:-master}"
BUILD_TASK="${2:-assembleAfatDebug}"

if [ ! -d "telegram-src" ]; then
  echo "[*] Cloning DrKLO/Telegram ($UPSTREAM_REF) with submodules..."
  git clone --depth 1 --branch "$UPSTREAM_REF" --recurse-submodules --shallow-submodules \
    https://github.com/DrKLO/Telegram.git telegram-src
fi

echo "[*] Copying ParentalControlManager and applying source patches..."
cp -r TMessagesProj/* telegram-src/TMessagesProj/
python3 scripts/apply_parental_patch.py telegram-src

echo "[*] Running Gradle task :TMessagesProj:${BUILD_TASK}..."
cd telegram-src
chmod +x ./gradlew
./gradlew ":TMessagesProj:${BUILD_TASK}" --no-daemon
echo "[+] APK build complete! Output in telegram-src/TMessagesProj/build/outputs/apk/"

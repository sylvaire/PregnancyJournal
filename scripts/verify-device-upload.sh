#!/bin/bash
# 真机复验：保存连接 → 立即上传备份 → curl 核对云端 snapshot/commit 的 SHA-256。
#
# 用法（设备解锁后）：
#   ADB_DEVICE="<设备>:<端口>" DAV_USER="..." DAV_PASS="..." ./scripts/verify-device-upload.sh
#
# 前置：设备已解锁；本机已安装最新 debug 与 androidTest APK。
set -u
ADB_DEVICE="${ADB_DEVICE:?需要 ADB_DEVICE，例如 <设备>:<端口>}"
DAV_ENDPOINT="${DAV_ENDPOINT:-https://dav.jianguoyun.com/dav/}"
DAV_USER="${DAV_USER:?需要 DAV_USER}"
DAV_PASS="${DAV_PASS:?需要 DAV_PASS}"
PKG=cn.yunji.journal
RUNNER=cn.yunji.journal.test/androidx.test.runner.AndroidJUnitRunner
WORK=".local/device-verify-$(date +%s)"

say() { printf '\n=== %s ===\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

say "0. 设备可用性"
adb -s "$ADB_DEVICE" get-state >/dev/null 2>&1 || fail "设备未连接：$ADB_DEVICE"
LOCKED=$(adb -s "$ADB_DEVICE" shell dumpsys trust 2>/dev/null | grep -m1 'deviceLocked=' | grep -o 'deviceLocked=[01]' | cut -d= -f2)
[ "$LOCKED" = "0" ] || fail "设备仍处于锁屏（deviceLocked=$LOCKED）。请先在手机上解锁后重试。"
adb -s "$ADB_DEVICE" shell input keyevent 224 >/dev/null 2>&1
echo "deviceLocked=0，唤醒完成"

say "1. 安装最新构建"
adb -s "$ADB_DEVICE" install -r -t app/build/outputs/apk/debug/app-debug.apk | tail -1
adb -s "$ADB_DEVICE" install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk | tail -1

say "2. 真机连接测试（父目录留空，连续两轮）+ 保存连接"
adb -s "$ADB_DEVICE" shell am instrument -w \
  -e class cn.yunji.journal.JianguoRepeatedProbeTest \
  -e davEndpoint "$DAV_ENDPOINT" -e davUser "$DAV_USER" -e davPass "$DAV_PASS" \
  "$RUNNER" | tail -6

say "3. 上传并核对（复用已保存连接）"
adb -s "$ADB_DEVICE" shell am instrument -w \
  -e class cn.yunji.journal.JianguoUploadVerifyTest \
  -e davEndpoint "$DAV_ENDPOINT" -e davUser "$DAV_USER" -e davPass "$DAV_PASS" \
  "$RUNNER" | tail -8

say "4. curl 独立核对云端 snapshot/commit"
mkdir -p "$WORK"
VAULT=$(adb -s "$ADB_DEVICE" shell run-as "$PKG" cat no_backup/vault/active-generation.json 2>/dev/null \
        | tr -d '\r' | grep -o '"activeId":"[^"]*"' | cut -d'"' -f4)
if [ -n "${VAULT:-}" ]; then
  echo "本机 active 代际：$VAULT"
fi
curl -s -u "$DAV_USER:$DAV_PASS" -X PROPFIND -H "Depth: 1" \
  "$DAV_ENDPOINT/pregnancy-journal/" --max-time 25 \
  | grep -oE '<d:href>[^<]*</d:href>' | sed 's/<[^>]*>//g'
echo "（在以上 vault 中按最新时间取 snapshots/<id>.bin 与 commits/<id>.json，"
echo "  下载后计算 sha256sum，与 commit 的 cipherSha256 比对即可。）"

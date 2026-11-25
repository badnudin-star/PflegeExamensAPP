#!/usr/bin/env bash
# Only run on a fresh Android emulator: uses adb root and creates synthetic data.
# Neither personal data nor a connected physical device should be used.
set -euo pipefail
[ "$#" -eq 2 ] || { echo 'Expected old and new APK paths.' >&2; exit 1; }
serial=$(adb get-serialno)
case "$serial" in emulator-*) ;; *) echo 'Only an emulator is allowed.' >&2; exit 1;; esac
pkg=de.pflegeexamen.app
prefs=/data/user/0/$pkg/shared_prefs/card_progress.xml
adb root
adb wait-for-device
# Keep both app launches offline. ADB itself is provided by the emulator host.
adb shell svc wifi disable
adb shell svc data disable
adb install "$1"
adb shell am start -W -n "$pkg/.MainActivity"
adb shell am force-stop "$pkg"
uid=$(adb shell stat -c %u "/data/user/0/$pkg" | tr -d '\r')
[[ "$uid" =~ ^[0-9]+$ ]]
# This is a real app preference format, but its ID is entirely synthetic.
printf '%s\n' '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>' '<map><set name="learned_card_ids"><string>synthetic-upgrade-probe</string></set></map>' > "$RUNNER_TEMP/upgrade-card-progress.xml"
adb push "$RUNNER_TEMP/upgrade-card-progress.xml" /data/local/tmp/upgrade-card-progress.xml >/dev/null
adb shell "mkdir -p /data/user/0/$pkg/shared_prefs && cp /data/local/tmp/upgrade-card-progress.xml $prefs && chown $uid:$uid /data/user/0/$pkg/shared_prefs $prefs && chmod 700 /data/user/0/$pkg/shared_prefs && chmod 600 $prefs && restorecon -R /data/user/0/$pkg/shared_prefs"
adb shell rm /data/local/tmp/upgrade-card-progress.xml
adb install -r "$2"
test "$(adb shell stat -c %u "/data/user/0/$pkg" | tr -d '\r')" = "$uid"
adb shell am start -W -n "$pkg/.MainActivity"
adb shell am force-stop "$pkg"
adb pull "$prefs" "$RUNNER_TEMP/upgrade-card-after.xml" >/dev/null
cmp "$RUNNER_TEMP/upgrade-card-progress.xml" "$RUNNER_TEMP/upgrade-card-after.xml"
printf '%s\n' 'Signiertes Update erfolgreich; synthetische Kartenfortschrittsdatei blieb erhalten. Kein Test auf einem physischen Gerät.'

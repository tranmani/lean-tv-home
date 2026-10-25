#!/bin/sh
# Installs Lean TV Home on an Android TV / Google TV box over the network and makes it the Home app.
#   scripts/install.sh <box-ip> [lean-tv-home.apk]
# Needs adb on this computer and "Network debugging" (or USB debugging) on the box.
set -e
BOX=${1:?usage: scripts/install.sh <box-ip> [apk]}
APK=${2:-lean-tv-home.apk}
case "$BOX" in *:*) ;; *) BOX="$BOX:5555" ;; esac
PKG=org.lean.tvhome
GOOGLE_HOME=com.google.android.tvlauncher

adb connect "$BOX"
A="adb -s $BOX"
echo "Waiting for the box. If the TV asks \"Allow USB debugging?\", choose Always allow."
$A wait-for-device
$A install -r "$APK"

# Make it the Home app.
$A shell cmd role add-role-holder android.app.role.HOME $PKG || true
$A shell cmd package set-home-activity --user 0 $PKG/.HomeEntry || true

# Google TV / Android TV keeps sending the Home button to its own home while that app is on.
for p in $GOOGLE_HOME com.google.android.leanbacklauncher; do
  if $A shell pm list packages -e | grep -q "^package:$p\$"; then
    $A shell pm disable-user --user 0 $p
  fi
done

# Optional Wake-on-LAN: let the app turn the screen on and keep it out of battery limits.
$A shell appops set $PKG TURN_SCREEN_ON allow || true
$A shell dumpsys deviceidle whitelist +$PKG >/dev/null || true

$A shell input keyevent KEYCODE_HOME
echo "Done. The Home button now opens Lean TV Home."
echo "Undo: scripts/restore-google-home.sh $BOX"

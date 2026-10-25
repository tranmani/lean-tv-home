#!/bin/sh
# Gives the Home button back to the box's own home and, with --uninstall, removes Lean TV Home.
#   scripts/restore-google-home.sh <box-ip> [--uninstall]
set -e
BOX=${1:?usage: scripts/restore-google-home.sh <box-ip> [--uninstall]}
case "$BOX" in *:*) ;; *) BOX="$BOX:5555" ;; esac
adb connect "$BOX"
A="adb -s $BOX"
for p in com.google.android.tvlauncher com.google.android.leanbacklauncher; do
  if $A shell pm list packages -d | grep -q "^package:$p\$"; then
    $A shell pm enable $p
    $A shell cmd role add-role-holder android.app.role.HOME $p || true
  fi
done
if [ "${2:-}" = "--uninstall" ]; then
  $A uninstall org.lean.tvhome
fi
$A shell input keyevent KEYCODE_HOME
echo "Done. The Home button opens the box's own home again."

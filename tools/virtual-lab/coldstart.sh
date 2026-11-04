#!/usr/bin/env bash
# Kaltstart N-mal (Release, Emulator mit root): TTID (am start -W), TTFD (Fully drawn), App-Messpunkte aus dem Protokoll.
# Aufruf: coldstart.sh <anzahl> [wartezeit_s]
A=${ADB:-adb}; N=${1:-10}; WAIT=${2:-40}; PKG=com.mg.player
M=$($A shell "wc -l < /data/data/$PKG/files/logs/app.log" | tr -d '\r')
for i in $(seq 1 "$N"); do
  $A shell am force-stop $PKG; sleep 3; $A logcat -c
  t=$($A shell am start -W -n $PKG/.ui.MainActivity | awk -F': ' '/TotalTime/{print $2}' | tr -d '\r')
  sleep "$WAIT"
  f=$($A logcat -d -s ActivityManager:I | grep -o "Fully drawn.*" | sed 's/.*+//' | tail -1)
  echo "lauf $i TTID=${t}ms TTFD=$f"
done
$A shell "tail -n +$M /data/data/$PKG/files/logs/app.log" | grep -oE "Start bis (Startbildschirm|Inhalt sichtbar): [0-9]+" | awk -F': ' '{a[$1]=a[$1]" "$2} END {for (k in a) print k":"a[k]}'

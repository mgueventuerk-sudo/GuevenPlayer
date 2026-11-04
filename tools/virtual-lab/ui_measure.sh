#!/usr/bin/env bash
# UI-Messungen auf Emulator/Gerät per adb (Release-Build installiert, Test-Playlist eingerichtet).
# Hinweis: `input keyevent` startet je Taste einen Prozess (im Emulator ~1 s). Für Scroll-Messungen im Emulator
# Tasten über die Konsole senden, je Ereignis ein Aufruf: `adb emu event send EV_KEY:KEY_DOWN:1`, dann `…:0` und `EV_SYN:SYN_REPORT:0`.
# Aufruf: ui_measure.sh <ausgabeordner> [coldstart|jank|zap|monkey|lifecycle|memory|all]
set -u
OUT=$1; WHAT=${2:-all}; mkdir -p "$OUT"
ADB=${ADB:-adb}; PKG=com.mg.player; ACT=$PKG/.ui.MainActivity
key() { $ADB shell input keyevent "$@"; }
log() { echo "$*" | tee -a "$OUT/summary.txt"; }

coldstart() {  # Testfall 1: 10 Kaltstarts, TTID (TotalTime) und TTFD (Fully drawn)
  for i in $(seq 1 10); do
    $ADB shell am force-stop $PKG; sleep 3; $ADB logcat -c
    total=$($ADB shell am start -W -n $ACT | awk -F': ' '/TotalTime/{print $2}' | tr -d '\r')
    sleep 25
    full=$($ADB logcat -d -s ActivityManager:I ActivityTaskManager:I | grep -o "Fully drawn $ACT: +[0-9a-z]*" | tail -1 | sed 's/.*+//')
    app=$($ADB logcat -d | grep -o "Start bis Inhalt sichtbar: [0-9]* ms" | tail -1)
    log "coldstart.$i TTID=${total}ms TTFD=${full:-keins} app='${app}'"
  done
}

jank() {  # Testfall 2: 60 s Senderliste scrollen (240 × Runter, alle 250 ms)
  $ADB shell am force-stop $PKG; $ADB shell am start -W -n $ACT > /dev/null; sleep 30
  key KEYCODE_DPAD_RIGHT; sleep 2
  $ADB shell dumpsys gfxinfo $PKG reset > /dev/null; $ADB logcat -c
  for i in $(seq 1 240); do key KEYCODE_DPAD_DOWN; sleep 0.25; done
  sleep 2
  $ADB shell dumpsys gfxinfo $PKG > "$OUT/gfxinfo_scroll.txt"
  grep -E "Total frames|Janky frames|percentile|Number Missed Vsync|Number Slow" "$OUT/gfxinfo_scroll.txt" | sed 's/^/jank.gfx /' | tee -a "$OUT/summary.txt"
  key KEYCODE_HOME; sleep 3
  $ADB logcat -d | grep "Bildzeiten" | tail -3 | sed 's/^/jank.app /' | tee -a "$OUT/summary.txt"
}

zap() {  # Testfall 3: 20 Kanalwechsel im Player (Hoch/Runter), Zeit bis Wiedergabe aus dem App-Protokoll
  $ADB logcat -c
  key KEYCODE_DPAD_CENTER; sleep 20
  for i in $(seq 1 20); do key KEYCODE_DPAD_DOWN; sleep 8; done
  $ADB logcat -d | grep -o "Start bis Wiedergabe: [0-9]* ms" | sed 's/^/zap.ui /' | tee -a "$OUT/summary.txt"
  key KEYCODE_BACK; sleep 2
}

monkey() {  # Stabilität: Zufallsbedienung, mehrere Startwerte
  for seed in 11 22 33 44 55; do
    $ADB logcat -c
    $ADB shell monkey -p $PKG -s $seed --throttle 150 --pct-syskeys 0 --pct-appswitch 5 --ignore-security-exceptions -v 3000 > "$OUT/monkey_$seed.txt" 2>&1
    crash=$(grep -c "CRASH:" "$OUT/monkey_$seed.txt"); anr=$(grep -c "NOT RESPONDING" "$OUT/monkey_$seed.txt")
    fatal=$($ADB logcat -d -b crash | grep -c "FATAL EXCEPTION")
    log "monkey.seed$seed events=3000 crash=$crash anr=$anr fatal_logcat=$fatal ende='$(tail -1 "$OUT/monkey_$seed.txt" | tr -d '\r')'"
    $ADB logcat -d -b crash > "$OUT/crash_$seed.txt"
  done
}

lifecycle() {  # Testfall 6: Home und zurück, Prozess-Tod im Hintergrund
  $ADB logcat -c
  $ADB shell am start -W -n $ACT > /dev/null; sleep 15
  key KEYCODE_DPAD_CENTER; sleep 15
  key KEYCODE_HOME; sleep 10
  $ADB shell am start -W -n $ACT > /dev/null; sleep 15
  pid1=$($ADB shell pidof $PKG | tr -d '\r')
  key KEYCODE_HOME; sleep 5; $ADB shell am kill $PKG; sleep 3
  pid_after_kill=$($ADB shell pidof $PKG | tr -d '\r')
  $ADB shell am start -W -n $ACT > /dev/null; sleep 20
  pid2=$($ADB shell pidof $PKG | tr -d '\r')
  fatal=$($ADB logcat -d -b crash | grep -c "FATAL EXCEPTION")
  log "lifecycle pid_vorher=$pid1 nach_kill='${pid_after_kill}' pid_neu=$pid2 fatal=$fatal"
  $ADB logcat -d | grep -E "GuevenPlayer/(Messung|Fehler|Player|Ende)" | tail -20 > "$OUT/lifecycle_log.txt"
}

memory() {  # Speicher nach Start und nach 10 Runden durch die Hauptbereiche
  $ADB shell am force-stop $PKG; $ADB shell am start -W -n $ACT > /dev/null; sleep 30
  $ADB shell dumpsys meminfo $PKG | grep -E "TOTAL|Java Heap|Native Heap|Graphics" | head -5 | sed 's/^/mem.start /' | tee -a "$OUT/summary.txt"
  for r in $(seq 1 10); do
    key KEYCODE_DPAD_UP; for t in 1 2 3 4; do key KEYCODE_DPAD_RIGHT; sleep 3; done
    for t in 1 2 3 4; do key KEYCODE_DPAD_LEFT; sleep 2; done
  done
  sleep 5
  $ADB shell dumpsys meminfo $PKG | grep -E "TOTAL|Java Heap|Native Heap|Graphics" | head -5 | sed 's/^/mem.nach10runden /' | tee -a "$OUT/summary.txt"
}

case $WHAT in
  all) coldstart; jank; memory; lifecycle; monkey ;;
  *) $WHAT ;;
esac

#!/usr/bin/env bash
# Testfall 2 (Plan B3): Senderliste 240 × Runter (alle 0,22 s) per Emulator-Konsole, danach framestats auswerten.
# Aufruf: scroll_frames.sh <ausgabe.txt>   (App mit Test-Playlist installiert, Emulator mit root)
A=${ADB:-adb}; OUT=$1
key() { for v in 1 0; do $A emu event send EV_KEY:$1:$v >/dev/null; $A emu event send EV_SYN:SYN_REPORT:0 >/dev/null; done; }
$A shell am force-stop com.mg.player; $A shell am start -n com.mg.player/.ui.MainActivity >/dev/null; sleep 35
key KEY_DOWN; sleep 3; key KEY_RIGHT; sleep 4
$A shell dumpsys gfxinfo com.mg.player reset >/dev/null
for i in $(seq 1 240); do key KEY_DOWN; sleep 0.22; done
sleep 3
$A shell dumpsys gfxinfo com.mg.player framestats > "$OUT"
python3 -I - "$OUT" <<'PY'
import sys
hdr=None; rows=[]
for l in open(sys.argv[1]).read().splitlines():
    if l.startswith('Flags,IntendedVsync'): hdr=l.strip(',').split(','); continue
    if hdr and l and l[0].isdigit() and l.count(',')>10:
        v=l.strip(',').split(',')
        if len(v)>=len(hdr): rows.append(dict(zip(hdr,map(int,v[:len(hdr)]))))
    elif hdr and l.startswith('---'): hdr=None
rows=[r for r in rows if r['Flags']==0]
def p(x,q): x=sorted(x); return round(x[min(len(x)-1,int(len(x)*q))],1) if x else 0
ui=[(r['SyncQueued']-r['HandleInputStart'])/1e6 for r in rows]
anim=[(r['PerformTraversalsStart']-r['AnimationStart'])/1e6 for r in rows]
trav=[(r['SyncQueued']-r['PerformTraversalsStart'])/1e6 for r in rows]
rt=[(r['FrameCompleted']-r['SyncStart'])/1e6 for r in rows]
tot=[(r['FrameCompleted']-r['IntendedVsync'])/1e6 for r in rows]
print('Bilder',len(rows))
for n,x in [('UI-Thread',ui),('  Recompose/Animation',anim),('  Layout/Draw',trav),('RenderThread+GPU',rt),('Gesamt',tot)]:
    print(f'{n:22s} p50 {p(x,.5):7.1f} ms  p90 {p(x,.9):7.1f} ms')
PY

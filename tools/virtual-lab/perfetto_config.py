#!/usr/bin/env python3
"""Binäre Perfetto-TraceConfig für Android 9 (kennt noch kein Text-Format). Aufruf: perfetto_config.py <dauer_ms> <datei>"""
import sys


def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)


def field(num, value):
    if isinstance(value, int):
        return varint(num << 3) + varint(value)
    data = value.encode() if isinstance(value, str) else value
    return varint(num << 3 | 2) + varint(len(data)) + data


duration = int(sys.argv[1])
# Android 9 kennt nur Ereignisnamen ohne Gruppe ("sched_switch" statt "sched/sched_switch").
events = ["sched_switch", "sched_wakeup", "sched_waking", "cpu_frequency", "cpu_idle", "print", "task_newtask", "task_rename"]
atrace = ["gfx", "view", "am", "wm", "dalvik", "input", "res", "sched", "freq", "binder_driver", "hal", "video", "audio"]
ftrace = b"".join(field(1, e) for e in events) + b"".join(field(2, c) for c in atrace) + field(3, "com.mg.player") + field(10, 8192)
ds_ftrace = field(1, field(1, "linux.ftrace") + field(100, ftrace))
ds_proc = field(1, field(1, "linux.process_stats"))
config = field(1, field(1, 32768)) + field(2, ds_ftrace) + field(2, ds_proc) + field(3, duration)
open(sys.argv[2], "wb").write(config)

#!/usr/bin/env python3
"""Explizite Baseline-Profil-Regeln für alle App-Methoden (com.mg.player.*) aus der R8-Mapping-Datei.

Ersatz für die Platzhalter-Regel `HSPLcom/mg/player/**;->**(**)**`, die beim R8-Umschreiben im Labor 3.24 nicht
übernommen wurde. Aufruf: app_methods_profile.py <mapping.txt> <ausgabe.txt>
"""
import re, sys

PRIM = {"void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S", "int": "I", "long": "J", "float": "F", "double": "D"}


def desc(t):
    dims = t.count("[]")
    base = t.replace("[]", "")
    return "[" * dims + (PRIM.get(base) or "L" + base.replace(".", "/") + ";")


rules, current, synthesized = set(), None, False
for line in open(sys.argv[1], encoding="utf-8"):
    if not line.startswith(" ") and not line.startswith("#"):
        m = re.match(r"(\S+) -> \S+:", line)
        current = m.group(1) if m and m.group(1).startswith("com.mg.player.") else None
        synthesized = False
        if current:
            rules.add(desc(current))
        continue
    if current is None:
        continue
    if "com.android.tools.r8.synthesized" in line:
        synthesized = True
        rules.discard(desc(current))
        current = None
        continue
    m = re.match(r"\s+(?:\d+:\d+:)?(\S+) ([^\s(]+)\(([^)]*)\)(?::\d+(?::\d+)?)? -> \S+$", line)
    if not m:
        continue
    ret, name, args = m.groups()
    if "." in name:  # eingebettete Methode einer anderen Klasse
        continue
    arg_desc = "".join(desc(a) for a in args.split(",") if a)
    rules.add(f"HSP{desc(current)}->{name}({arg_desc}){desc(ret)}")
open(sys.argv[2], "w", encoding="utf-8").write("\n".join(sorted(rules)) + "\n")
print(len(rules), "Regeln")

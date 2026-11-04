#!/usr/bin/env python3
"""Baseline-Profil (HRF) aus einem R8-Release-Lauf in Quellnamen zurückübersetzen (für app/src/main/baseline-prof.txt).

AGP übersetzt baseline-prof.txt beim nächsten R8-Lauf selbst in die neuen Kurznamen – dafür muss die Datei die
Original-Namen enthalten. Aufruf: deobfuscate_profile.py <mapping.txt> <profil.txt> <ausgabe.txt>
Zeilen, die sich nicht eindeutig zurückführen lassen, werden verworfen (Anzahl wird ausgegeben).
"""
import re, sys

mapping_path, profile_path, out_path = sys.argv[1:4]
PRIM = {"void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S", "int": "I", "long": "J", "float": "F", "double": "D"}

classes = {}   # obf -> orig (Punktschreibweise)
methods = {}   # (orig_class, obf_name) -> list of (orig_name, orig_args(list), orig_ret)
current = None
for line in open(mapping_path, encoding="utf-8"):
    if line.startswith("#"):
        continue
    if not line.startswith(" "):
        m = re.match(r"(\S+) -> (\S+):", line)
        if m:
            current = m.group(1)
            classes[m.group(2)] = m.group(1)
        continue
    m = re.match(r"\s+(?:\d+:\d+:)?(\S+) ([^\s(]+)\(([^)]*)\)(?::\d+(?::\d+)?)? -> (\S+)", line)
    if m and current:
        ret, name, args, obf = m.groups()
        if "." in name:  # eingebettete Methode aus anderer Klasse
            continue
        methods.setdefault((current, obf), []).append((name, [a for a in args.split(",") if a], ret))

def java_to_desc(t):
    dims = t.count("[]")
    base = t.replace("[]", "")
    d = PRIM.get(base) or "L" + base.replace(".", "/") + ";"
    return "[" * dims + d

def deobf_type_desc(desc):
    """Descriptor mit obfuskierten Klassennamen -> Original-Descriptor."""
    def sub(m):
        name = m.group(1).replace("/", ".")
        return "L" + classes.get(name, name).replace(".", "/") + ";"
    return re.sub(r"L([^;]+);", sub, desc)

def split_args(desc):
    out, i = [], 0
    while i < len(desc):
        j = i
        while desc[j] == "[":
            j += 1
        if desc[j] == "L":
            j = desc.index(";", j)
        out.append(desc[i:j + 1])
        i = j + 1
    return out

kept, dropped, out = 0, 0, []
for raw in open(profile_path, encoding="utf-8"):
    line = raw.strip()
    if not line:
        continue
    m = re.match(r"^([HSP]*)(L[^;]+;)(?:->([^(]+)\(([^)]*)\)(.+))?$", line)
    if not m:
        dropped += 1
        continue
    flags, cls, name, args, ret = m.groups()
    obf_cls = cls[1:-1].replace("/", ".")
    orig_cls = classes.get(obf_cls, obf_cls)
    if name is None:
        out.append(f"{flags}L{orig_cls.replace('.', '/')};")
        kept += 1
        continue
    d_args = [deobf_type_desc(a) for a in split_args(args)]
    d_ret = deobf_type_desc(ret)
    if obf_cls not in classes:
        # nicht umbenannte Klasse (Bibliothek/Keep): Methode unverändert übernehmen
        out.append(f"{flags}L{orig_cls.replace('.', '/')};->{name}({''.join(d_args)}){d_ret}")
        kept += 1
        continue
    candidates = [c for c in methods.get((orig_cls, name), []) if [java_to_desc(a) for a in c[1]] == d_args and java_to_desc(c[2]) == d_ret]
    names = {c[0] for c in candidates}
    if len(names) == 1:
        out.append(f"{flags}L{orig_cls.replace('.', '/')};->{names.pop()}({''.join(d_args)}){d_ret}")
        kept += 1
    elif name in ("<init>", "<clinit>"):
        out.append(f"{flags}L{orig_cls.replace('.', '/')};->{name}({''.join(d_args)}){d_ret}")
        kept += 1
    else:
        dropped += 1
open(out_path, "w", encoding="utf-8").write("\n".join(out) + "\n")
print(f"übernommen {kept}, verworfen {dropped}")

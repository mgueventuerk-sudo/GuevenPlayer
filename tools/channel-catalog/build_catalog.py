#!/usr/bin/env python3
"""Baut app/src/main/assets/channel-catalog.dat aus der iptv-org-Datenbank (gemeinfrei, Unlicense).

Aufruf: python3 build_catalog.py <pfad-zu-iptv-org/database> <ausgabe.tsv.gz>
Format je Zeile: id<TAB>land<TAB>name;alt1;alt2  (geschlossene Sender und Sender ohne Land fallen weg).
"""
import csv, gzip, sys

src, out = sys.argv[1], sys.argv[2]
rows = 0
with open(f"{src}/data/channels.csv", newline="", encoding="utf-8") as f, gzip.open(out, "wt", encoding="utf-8", compresslevel=9) as g:
    g.write("# iptv-org/database channels.csv – gemeinfrei (Unlicense). Spalten: id, land, namen (;)\n")
    for r in csv.DictReader(f):
        if r["closed"].strip() or not r["country"].strip():
            continue
        names = [r["name"].strip()] + [a.strip() for a in r["alt_names"].split(";") if a.strip()]
        names = [n.replace("\t", " ").replace(";", ",") for n in names if n]
        g.write(f"{r['id'].strip()}\t{r['country'].strip().upper()}\t{';'.join(dict.fromkeys(names))}\n")
        rows += 1
print(rows, "Sender")

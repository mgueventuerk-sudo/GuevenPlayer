#!/usr/bin/env python3
"""EXPLAIN QUERY PLAN für jede @Query der DAOs gegen das exportierte Room-Schema (app/schemas/…/<version>.json).

Meldet je Query, ob eine große Tabelle ohne Index durchsucht wird ("SCAN <tabelle>" ohne "USING ... INDEX") und ob
temporäre Sortierungen nötig sind ("USE TEMP B-TREE"). Mit Testdaten (ROWS je Tabelle), damit der Planer echte
Statistiken hat (ANALYZE). Aufruf: python3 -I query_plans.py <schema.json> <quellordner> [ROWS]
"""
import json, re, sqlite3, sys, pathlib

schema_path, source_root = sys.argv[1], pathlib.Path(sys.argv[2])
rows = int(sys.argv[3]) if len(sys.argv) > 3 else 20000
BIG = {"channels", "movies", "series", "episodes", "epg_programs", "search_index", "channel_flags", "favorites", "progress"}

schema = json.load(open(schema_path))["database"]
db = sqlite3.connect(":memory:")
for entity in schema["entities"]:
    table = entity["tableName"]
    db.execute(entity["createSql"].replace("${TABLE_NAME}", table))
    for index in entity.get("indices", []):
        db.execute(index["createSql"].replace("${TABLE_NAME}", table))
for view in schema.get("views", []):
    db.execute(view["createSql"].replace("${VIEW_NAME}", view["viewName"]))

# Statistiken: sqlite_stat1 so setzen, als hätten große Tabellen ROWS Zeilen (Planer wählt dann wie auf dem Gerät).
db.execute("ANALYZE")
for entity in schema["entities"]:
    table = entity["tableName"]
    n = rows if any(b in table for b in BIG) else 50
    db.execute("INSERT INTO sqlite_stat1 VALUES (?,NULL,?)", (table, str(n)))
    for index in entity.get("indices", []):
        cols = len(index["columnNames"])
        db.execute("INSERT INTO sqlite_stat1 VALUES (?,?,?)", (table, index["name"], " ".join([str(n)] + ["10"] * cols)))
db.execute("ANALYZE sqlite_master")

STRING = r'"""(.*?)"""|"((?:[^"\\]|\\.)*)"'
consts = {}
for path in source_root.rglob("*.kt"):
    for m in re.finditer(r'const val ([A-Z_][A-Z0-9_]*)\s*=\s*((?:(?:' + STRING + r')\s*\+?\s*)+)', path.read_text(), re.S):
        consts[m.group(1)] = "".join(a or b for a, b in re.findall(STRING, m.group(2), re.S))


def resolve(sql, depth=0):
    def sub(m):
        name = m.group(1) or m.group(2)
        return resolve(consts[name], depth + 1) if name in consts and depth < 5 else "1"
    return re.sub(r"\$\{([A-Za-z_]\w*)\}|\$([A-Za-z_]\w*)", sub, sql).replace('\\"', '"')


def query_args(text):
    """Alle @Query(...)-Argumente: verkettete String-Literale bis zur schließenden Klammer."""
    for m in re.finditer(r"@Query\(", text):
        i, depth, pieces = m.end(), 1, []
        while i < len(text) and depth:
            if text.startswith('"""', i):
                j = text.index('"""', i + 3)
                pieces.append(text[i + 3 : j]); i = j + 3; continue
            c = text[i]
            if c == '"':
                j = i + 1
                while text[j] != '"':
                    j += 2 if text[j] == "\\" else 1
                pieces.append(text[i + 1 : j]); i = j + 1; continue
            depth += c == "("
            depth -= c == ")"
            i += 1
        yield m, "".join(pieces)


findings, total = [], 0
for path in sorted(source_root.rglob("*.kt")):
    text = path.read_text()
    for match, raw in query_args(text):
        sql = re.sub(r":[A-Za-z_]\w*", "?", resolve(raw))
        line = text[: match.start()].count("\n") + 1
        total += 1
        try:
            plan = db.execute("EXPLAIN QUERY PLAN " + sql, [None] * sql.count("?")).fetchall()
        except sqlite3.Error as e:
            findings.append(("NICHT PRÜFBAR", f"{path.name}:{line}", str(e), sql))
            continue
        details = [p[3] for p in plan]
        scans = [d for d in details if d.startswith("SCAN") and "INDEX" not in d and any(b in d.split()[1] for b in BIG)]
        temp = [d for d in details if "TEMP B-TREE" in d]
        if scans:
            findings.append(("SCAN GROSS", f"{path.name}:{line}", "; ".join(details), " ".join(sql.split())[:160]))
        elif temp:
            findings.append(("TEMP-SORT", f"{path.name}:{line}", "; ".join(details), " ".join(sql.split())[:160]))

print(f"Queries geprüft: {total}")
for kind in ("SCAN GROSS", "TEMP-SORT", "NICHT PRÜFBAR"):
    items = [f for f in findings if f[0] == kind]
    print(f"\n## {kind}: {len(items)}")
    for _, where, plan, sql in items:
        print(f"- {where}\n  Plan: {plan}\n  SQL: {sql}")

# Performance-Budget

Schwellen für die Macrobenchmarks aus
`benchmark/src/main/java/com/mg/player/benchmark/AppMacrobenchmark.kt`.
Die Regeln dazu stehen in `CLAUDE.md`, Abschnitt „Budget-Datei".

**Keine Zahl in dieser Datei ist geraten.** Eine Metrik ohne Messung steht als
`unbekannt — nie gemessen`. Ein Wert kommt nur aus einem Lauf, dessen Gerät,
API-Stufe, Datum und Commit danebenstehen.

Messen:

```
./gradlew :benchmark:connectedReleaseAndroidTest -PbenchmarkEmulator=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.mg.player.benchmark.AppMacrobenchmark
```

Ergebnis: `benchmark/build/outputs/connected_android_test_additional_output/*.json`

---

## Stand

Alle Werte sind unbekannt. Grund: Die Macrobenchmarks laufen nicht in der CI
(`.github/workflows/device-tests.yml` startet nur `BaselineProfileGenerator`,
und das nur bei `workflow_dispatch`). Es hat also nie ein Lauf stattgefunden,
dessen Zahlen hier stehen könnten.

Die Zielspalte ist bewusst leer: Ein Ziel ohne Ausgangswert ist eine Behauptung.
Sie wird gefüllt, sobald der erste Lauf einen Ausgangswert liefert.

### `coldStartWithoutCompilation` — Kaltstart ohne Vorkompilierung

| Metrik | Gemessen | Gerät / API | Datum | Commit | Ziel |
|---|---|---|---|---|---|
| `timeToInitialDisplayMs` (median) | unbekannt — nie gemessen | — | — | — | — |
| `timeToFullDisplayMs` (median) | unbekannt — nie gemessen | — | — | — | — |
| `frameDurationCpuMs` (P95) | unbekannt — nie gemessen | — | — | — | — |
| `memoryHeapSizeMaxKb` | unbekannt — nie gemessen | — | — | — | — |

### `coldStartWithBaselineProfile` — Kaltstart mit Baseline-Profil

| Metrik | Gemessen | Gerät / API | Datum | Commit | Ziel |
|---|---|---|---|---|---|
| `timeToInitialDisplayMs` (median) | unbekannt — nie gemessen | — | — | — | — |
| `timeToFullDisplayMs` (median) | unbekannt — nie gemessen | — | — | — | — |
| `frameDurationCpuMs` (P95) | unbekannt — nie gemessen | — | — | — | — |
| `memoryHeapSizeMaxKb` | unbekannt — nie gemessen | — | — | — | — |

Diese beiden gehören zusammen gelesen: Der Abstand zwischen ihnen sagt, was das
eingecheckte Baseline-Profil überhaupt bringt. Ist er klein, taugt das Profil
nichts — das eingecheckte ist von Hand geschrieben (23 Regeln, Stand 3.39).

### `topLevelNavigation` — Wechsel zwischen den Hauptbereichen

| Metrik | Gemessen | Gerät / API | Datum | Commit | Ziel |
|---|---|---|---|---|---|
| `frameDurationCpuMs` (P95) | unbekannt — nie gemessen | — | — | — | — |
| `frameOverrunMs` (P95) | unbekannt — nie gemessen | — | — | — | — |
| `memoryHeapSizeMaxKb` | unbekannt — nie gemessen | — | — | — | — |

### `playerStartsFromFixtureChannel` — Wiedergabe startet

| Metrik | Gemessen | Gerät / API | Datum | Commit | Ziel |
|---|---|---|---|---|---|
| `frameDurationCpuMs` (P95) | unbekannt — nie gemessen | — | — | — | — |
| `frameOverrunMs` (P95) | unbekannt — nie gemessen | — | — | — | — |
| `memoryHeapSizeMaxKb` | unbekannt — nie gemessen | — | — | — | — |

### `liveListScroll` — Scrollen in der Senderliste

| Metrik | Gemessen | Gerät / API | Datum | Commit | Ziel |
|---|---|---|---|---|---|
| `frameDurationCpuMs` (P95) | unbekannt — nie gemessen | — | — | — | — |
| `frameOverrunMs` (P95) | unbekannt — nie gemessen | — | — | — | — |
| `memoryHeapSizeMaxKb` | unbekannt — nie gemessen | — | — | — | — |

Das ist der Benchmark, an dem `AGENTS.md` P0 hängt („60-FPS-Scrolling … ist das
Abnahmekriterium jeder UI-Änderung"). 60 FPS heißt 16,7 ms je Bild; ob die App
das auf einem Fire TV Stick hält, ist bislang unbelegt.

---

## Eintragen

Ein Lauf füllt eine Zeile vollständig oder gar nicht. Fehlt Gerät, API-Stufe
oder Commit, ist die Zahl wertlos, weil sie sich mit keiner späteren
vergleichen lässt.

Das Baseline-Gerät für Abnahmeentscheidungen ist der Fire TV Stick (TV) und ein
schwaches Handy, nicht der Emulator. Emulator-Zahlen sind als solche zu
kennzeichnen: Sie zeigen Regressionen, taugen aber nicht als Abnahmewert.

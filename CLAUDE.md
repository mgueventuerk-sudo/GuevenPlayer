# CLAUDE.md

Diese Datei ergänzt `AGENTS.md`. Sie wiederholt dort nichts.

**`AGENTS.md` ist bindend** und bleibt die einzige Quelle für Architektur, Sprache,
Sicherheit, Stabilität, Performance-Grundsätze, Arbeitsregeln, Definition of Done,
Fehlersuche und Aufräumen. Bei Widerspruch zwischen dieser Datei und `AGENTS.md`
gilt `AGENTS.md`. Steht eine Regel dort, steht sie hier nicht noch einmal.

Was hier dazukommt, ist eine einzige Sparte: wie die P0-Ziele aus `AGENTS.md`
von unprüfbaren Absichten zu geprüften Zahlen werden.

---

## Performance / Benutzerfreundlichkeit / Stabilität — laufende Verbesserung

### Warum diese Sparte existiert

`AGENTS.md` P0 Performance verlangt: *„60-FPS-Scrolling und Stabilität dort sind
das Abnahmekriterium jeder UI-Änderung."* Dieses Kriterium ist heute **nicht
messbar nachprüfbar** — es wird nirgends gemessen. Diese Sparte schließt die
Lücke zwischen dem Ziel und seinem Nachweis.

### Grundsatz: keine Behauptung ohne wiederholbare Messung

Eine Verbesserung gilt als belegt, wenn **eine andere Person oder ein CI-Job die
Messung ohne dich wiederholen kann** und dieselbe Richtung herauskommt.

Nicht belegt sind: „fühlt sich schneller an", „sollte weniger Speicher brauchen",
eine Messung nur auf dem Entwicklungsrechner, eine Messung ohne Vorher-Wert, und
eine einzelne Messung bei einer Metrik mit Streuung.

Jede Änderung, die Performance, Benutzerfreundlichkeit oder Stabilität
beansprucht, nennt im Diff oder PR:

1. **Vorher-Wert** mit Messbefehl und Gerät/API-Stufe
2. **Nachher-Wert** aus demselben Befehl auf demselben Gerät
3. Bei Streuung: Anzahl der Durchläufe und die Streubreite, nicht nur den Mittelwert
4. Wenn nicht gemessen: ausdrücklich **„ungeprüft"** — nie „sollte funktionieren"

### Die vorhandenen Messungen

Fünf Macrobenchmarks liegen fertig im Repo, in
`benchmark/src/main/java/com/mg/player/benchmark/AppMacrobenchmark.kt`
(Release-Variante, nicht die langsamere Debug-Oberfläche):

| Benchmark | Metriken | Durchläufe |
|---|---|---|
| `coldStartWithoutCompilation` | Startup, FrameTiming, MemoryUsage(Max) | 10 |
| `coldStartWithBaselineProfile` | Startup, FrameTiming, MemoryUsage(Max) | 10 |
| `topLevelNavigation` | FrameTiming, MemoryUsage(Max) | 5 |
| `playerStartsFromFixtureChannel` | FrameTiming, MemoryUsage(Max) | 5 |
| `liveListScroll` | FrameTiming, MemoryUsage(Max) | 3 |

Aufruf (braucht einen Emulator oder ein angeschlossenes Gerät):

```
./gradlew :benchmark:connectedBenchmarkAndroidTest -PbenchmarkEmulator=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.mg.player.benchmark.AppMacrobenchmark
```

Die Zahlen landen als JSON unter
`benchmark/build/outputs/connected_android_test_additional_output/`.

Weitere Werkzeuge, die schon im Repo stecken:

| Zweck | Aufruf |
|---|---|
| Plattenzugriff im Hauptthread findet die App selbst | `-PlabStrict=true` (StrictMode `penaltyDeath` im Debug-Build) |
| Compose-Stabilität und Skippability | `-Pandroidx.enableComposeCompilerReports=true` |
| Oberfläche gegen Bildregression | 11 Roborazzi-Tests unter `app/src/test/java/com/mg/player/screenshots/` |
| Echtes Android, API 24/28/37 | `:app:connectedDebugAndroidTest` (CI: `device-tests.yml`) |
| Pflichtgatter vor „fertig" | `./gradlew test detekt lint` |

### Budget-Datei: Schwellen gehören eingecheckt

Eine Zahl, die niemand vergleicht, verhindert keine Regression. Darum:
**`config/perf-budget.md`** hält je Benchmark und Metrik den gemessenen Wert mit
Gerät, API-Stufe, Datum und Commit.

Regeln dafür:

- Es wird **keine Zahl erfunden**. Eine Metrik ohne Messung steht als
  `unbekannt — nie gemessen` in der Datei. Heute (Stand dieser Datei) ist
  **jede** Metrik unbekannt: Das Repo enthält keine einzige
  Performance-Schwelle.
- Ein Wert kommt nur aus einem Lauf, dessen Gerät, API-Stufe und Commit dabeistehen.
- Verschlechtert eine Änderung einen Wert, wird sie **nicht gemerged**, bis
  entweder die Ursache behoben ist oder im PR steht, warum die
  Verschlechterung in Kauf genommen wird — mit Zahl, nicht mit Prosa.
- Wird ein Wert besser, wird die Datei nachgezogen. Das Budget geht nur in eine
  Richtung.

### Baselines gehen nur nach unten

Zwei Baselines verstecken Altlasten. Ihre Größe zum Stand dieser Datei:

| Baseline | Einträge | Datei |
|---|---|---|
| detekt | 387 | `config/detekt/baseline.xml` |
| Android Lint | 5 | `app/lint-baseline.xml` |

- Ein neuer Eintrag in einer Baseline ist **keine Lösung**, sondern eine
  verschobene. Wer einen hinzufügen will, begründet es im Diff.
- Jede Änderung, die eine Datei mit Baseline-Einträgen anfasst, räumt die
  Einträge **dieser Datei** mit auf, soweit das im Rahmen der Änderung geht.
- Die Zahlen oben werden nachgezogen, wenn sie kleiner werden.

### Benutzerfreundlichkeit: was davon prüfbar ist

`AGENTS.md` verlangt adaptive UI für TV (D-Pad) und Handy (Touch). Prüfbar
davon ist:

- **Jedes Bedienelement ist mit dem D-Pad erreichbar** und sein Fokus ist
  sichtbar. Nachweis: Roborazzi-Aufnahme mit gesetztem Fokus, nicht Augenmaß.
  Ein Bildschirm, der nur per Touch bedienbar ist, ist auf dem Fernseher kaputt.
- **Kein Fehler bleibt stumm.** Jeder Fehlerzustand hat eine sichtbare, deutsche
  Meldung, die sagt, was zu tun ist (Vorbild: `syncErrorText` in
  `data/sync/SyncManager.kt`). Nachweis: Test auf den Zustand, nicht auf den Text.
- **Kein Bildschirm ohne Ausweg.** Von jedem Zustand führt ein Weg zurück, auch
  nach einem Fehler.
- **Nichts Blockierendes im Hauptthread**, was der Nutzer als Hänger merkt.
  Nachweis: `-PlabStrict=true` läuft durch, ohne die App zu beenden.

Was nicht prüfbar ist (Geschmack bei Farben, Abständen, Formulierungen), wird
als Vorschlag vorgelegt, nicht als Regel behauptet.

### Stabilität: was über `AGENTS.md` hinaus gilt

- **Abbrechbarkeit.** Lange Schleifen über fremde Daten (Parser, Importe,
  Netzwerkschleifen) brauchen einen Aufhängepunkt, auch auf dem Pfad, auf dem
  der Aufrufer die Daten verwirft. Sonst läuft die Arbeit nach `cancel()`
  weiter. Nachweis: Test, der abbricht und zählt, wie weit es noch lief
  (Vorbild: `cancellingTheParseStopsItEarly` in `ParserFuzzTest`).
- **Was im Startfenster liegt, muss der Laborlauf sehen können.** Prüfwerkzeuge
  (StrictMode, DebugChecks) werden **zuerst** installiert, vor allem anderen in
  `Application.onCreate`. Eine Prüfung, die erst nach dem zu prüfenden Code
  anläuft, ist keine.
- **Verschlüsselung geräteabhängig prüfen.** Wo ein Systemanbieter je
  Android-Version anders rechnen kann, reicht ein JVM-Test nicht — er benutzt
  den JDK-Anbieter. Nachweis gehört in `app/src/androidTest` (Vorbild:
  `BackupCryptoDeviceTest`, grün auf API 24/28/37).

### Die Verbesserungsschleife

Jede Runde nimmt **einen** Punkt, nicht fünf. Reihenfolge:

1. **Messen** — Vorher-Wert aus einem wiederholbaren Lauf, in
   `config/perf-budget.md` eintragen, falls er dort noch fehlt.
2. **Ursache benennen** — Datei und Zeile, in ein bis drei Sätzen. Vermutung
   wird als Vermutung markiert.
3. **Minimal ändern** — nur die nötigen Zeilen, kein Refactor nebenbei.
4. **Regressionstest** — deterministisch, ohne Zufall und ohne Zeitmessung, wenn
   es ohne geht.
5. **Nachher messen** — derselbe Befehl, dasselbe Gerät. Keine Verbesserung
   messbar? Änderung zurücknehmen, nicht behaupten.
6. **Gatter** — `./gradlew test detekt lint` grün, `device-tests` grün.

**Offener Punkt 1 dieser Schleife** (Stand dieser Datei): Die fünf
Macrobenchmarks oben laufen **nie in der CI**. `.github/workflows/device-tests.yml`
schaltet den `baseline-profile`-Job nur bei `workflow_dispatch` ein, und der ruft
ausschließlich `BaselineProfileGenerator` auf — nicht `AppMacrobenchmark`.
Es gibt deshalb keine einzige gemessene Performance-Zahl im Repo und keine
Schwelle, gegen die eine Regression auffallen würde. Solange das so bleibt, ist
jede Aussage über Startzeit, Bildraten oder Speicherverbrauch dieser App
**unbelegt**, egal wie plausibel sie klingt.

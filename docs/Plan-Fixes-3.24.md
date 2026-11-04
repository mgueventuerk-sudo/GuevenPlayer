# Plan: alle Befunde der Messkampagne 3.23 beheben

> **Stand 03.11.2026 (Version 3.25):** Messwerte in `docs/Messbericht-3.25.md`.
> - **B1 + B2:** „Inhalt sichtbar“ −34 % (gleiche Sitzung).
> - **B3:** Leuchtschatten und Reflex aus bei „Effekte reduzieren“ wirken (UI −9 %, GPU −16 %). Skalierung aus wurde verworfen.
> - **B4:** Suchindex im Hintergrund, Import −24 %. Pipelining wurde verworfen.
> - **A4:** nicht durchgeführt (Entscheidung).
>
> **Stand 03.11.2026 (Version 3.24):** Messwerte in `docs/Messbericht-3.24.md`.
> - **Umgesetzt und im Labor belegt:** A1, A2, A3, A5, B1, B2, B5.
> - **B4:** umgesetzt, aber die Falsifikation ist ausgelöst (−18 % statt −30 %).
> - **B3:** ohne Designänderung kein großer Hebel. Braucht eine Entscheidung: auf schwachen Geräten Glow und Skalierung der Listenzeilen abschalten?
> - **B6:** Die Annahme war falsch. Die Verzögerung liegt in der Player-Konfiguration, nicht in der Oberfläche.
> - **B7:** nach Rechnung verworfen, spart keinen Gerätespeicher.

Grundlage sind `docs/Messbericht-3.23.md` (Emulator Android 9, 1 GB RAM, Release mit R8) und das Fire-TV-Protokoll vom
27.10. bis 02.11. Vorgaben: Design bleibt unverändert, jede Änderung ist unsichtbar oder betrifft nur Texte. Jedes Paket
nennt die Metrik, die Prognose, das Messverfahren und wann es falsch ist. Werte mit [geraten] sind nicht gemessen.

**Messwerkzeug je Paket:** Labor-Rerun mit denselben Skripten:
- `VirtualLabTest` mit `-e lab true`
- `tools/virtual-lab/ui_measure.sh`
- `query_plans.py`
- `LabJvmBenchmarkTest` mit `GUEVEN_LAB=true`
- atrace + trace_processor

Abnahme immer gegen die Werte aus 3.23, im gleichen Labor und im gleichen Kompilier-Zustand (`speed-profile`).

## Reihenfolge

| Version | Pakete | Schwerpunkt |
|---|---|---|
| 3.24 | A1, A2, A3, B5 | Stabilität: Hauptthread frei, Netz-Backoff, Datenbank-Indizes |
| 3.25 | B1, B2 | Startzeit: eigenes Baseline-Profil, Startarbeit nach hinten |
| 3.26 | B3 | Flüssiges Scrollen: weniger Recomposition je Tastendruck |
| 3.27 | B4, B6 | Import- und Zapping-Tempo |
| laufend | V1–V3 | Verifikation (Labor, Cube, Stick Lite) |

---

## A – Stabilität

### A1 Hauptthread im Startpfad vollständig frei

- **Befund:** ANR im Monkey-Lauf (Startwert 55): `SharedPreferences.awaitLoadedLocked` aus `ParentalControl.<init>` während
  der Komposition. StrictMode meldet `VpnStore.rawEntries` bei jedem Start. In 3.23 behoben: Jugendschutz und VPN werden auf
  IO angelegt (in 3.23 nachgemessen: 0 Meldungen dazu). Offen: `DiskWriteViolation` in `ChannelPagingSource` und mögliche weitere Fälle.
- **Änderung:**
  1. Labor-Debug-Build mit `StrictMode.ThreadPolicy.penaltyDeath()` (nur mit `-PlabStrict=true`) und Monkey über 20 Startwerte.
     Jeder Absturz zeigt einen weiteren Hauptthread-Zugriff.
  2. Jeden Fund wie in 3.23 auf IO vorziehen: `resolveStart` → `warmUp(...)`.
  3. `AppContainer`: verzögert angelegte Dienste, die SharedPreferences lesen, als `suspend`-Fabrik anbieten oder in `warmUp` aufnehmen.
- **Metrik:** StrictMode-Meldungen je Kaltstart (3.22: 2, 3.23: Ziel 0) und App-ANRs je 10 000 Monkey-Ereignisse (3.23: 1 auf 10 000).
- **Prognose:** 0 Meldungen und 0 App-ANRs in 20 × 5 000 Ereignissen.
- **Falsch, wenn:** `penaltyDeath` im Startpfad noch auslöst oder ein Monkey-Lauf einen App-ANR mit Hauptthread im App-Code zeigt.

### A2 Start-ANR durch Tastendruck vor dem ersten Fenster

- **Befund:** Die ANR-Art „no window has focus“ entsteht, wenn der Nutzer drückt, bevor das Fenster da ist (> 5 s). Im Emulator
  liegt TTID bei 8 s, auf dem Stick Lite bei ≈ 4,8–6 s [geraten].
- **Änderung:** Die Splash-Ansicht sofort zeichnen. Das Theme mit `windowBackground` als Startbild gibt es schon; prüfen,
  ob das erste Bild durch die Startup-Initializer blockiert wird (siehe B2).
- **Metrik:** TTID (`am start -W`, 10 Läufe).
- **Prognose:** −1,5 s im Emulator, zusammen mit B1/B2 [geraten].
- **Falsch, wenn:** TTID im Labor nicht unter 6,5 s fällt.

### A3 Netz-Abrufe mit Backoff und Jitter, nicht beim Start

- **Befund:** Der Sport-Dienst fragt bei jedem Start 10 ESPN-Ligen parallel ab. Ohne Netz wiederholt er alle 60 s. Im
  Cube-Protokoll stand 404-mal derselbe Sport-Satz. Diese Abrufe laufen im Startfenster parallel zum ersten Bild.
- **Änderung:** `SportRepository.keepFresh` bekommt:
  - einen exponentiellen Backoff: 60 s · 2ⁿ, höchstens 30 min, mit ±20 % Jitter;
  - einen Neustart bei `NetworkMonitor` online;
  - den ersten Abruf erst nach „Inhalt sichtbar“ (Signal aus `FullyDrawnReporter`).
- **Metrik:** Sport-Anfragen je Stunde ohne Netz (heute 600 [aus 10 Ligen × 60/h]) und CPU im Startfenster.
- **Prognose:** ≤ 20 Anfragen je Stunde, 0 Sport-Anfragen vor TTFD.
- **Falsch, wenn:** im Mock-Protokoll bzw. App-Protokoll vor „Inhalt sichtbar“ ein ESPN-Abruf steht oder ohne Netz mehr als 20/h gezählt werden.

### A4 Dauertest Abstürze und Lecks

- **Änderung:** Kein App-Code. Ein Labor-Skript fährt 20 Monkey-Startwerte mit je 5 000 Ereignissen. Dazu 50 Runden durch
  alle Bereiche mit `dumpsys meminfo` nach jeweils 10 Runden. LeakCanary läuft nur im Labor-Debug-Build über die Eigenschaft
  `-PlabLeaks=true`, nie in der APK für den Alltag.
- **Metrik:** App-Abstürze, App-ANRs, PSS-Verlauf und Leak-Berichte.
- **Prognose:** 0 Abstürze, 0 App-ANRs, PSS nach 50 Runden ≤ 120 MB (3.23: 105 MB nach 10 Runden).
- **Falsch, wenn:** PSS linear weiterwächst (> 2 MB je 10 Runden nach Runde 20) oder LeakCanary eine Activity oder ein ViewModel meldet.

### A5 Rückkehr in den Player (Kollision K1) – **Entscheidung nötig**

- **Befund:** Nach der Home-Taste und Rückkehr baut der Player den Stream neu auf (7,9 s im Emulator). Ursache ist die
  bewusste Freigabe bei onStop (Media3-Empfehlung, spart Speicher auf 1-GB-Geräten).
- **Option a:** So lassen.
- **Option b:** Den Player 30 s nach onStop behalten und erst dann freigeben. Die Rückkehr innerhalb von 30 s geht dann ohne Neuaufbau.
  - Kosten: ≈ 40–80 MB native Puffer bleiben 30 s belegt [geraten].
  - Falsch, wenn: die Rückkehr innerhalb von 30 s weiter einen Stream-Neustart im Protokoll zeigt oder der Low-Memory-Killer die App im Hintergrund häufiger beendet (`dumpsys activity exit-info`, 10 Versuche).

---

## B – Geschwindigkeit

### B1 Eigenes Baseline-Profil (größter Start-Hebel)

- **Befund:** Die APK enthält nur die Bibliotheks-Profile (13 KB `baseline.prof`), kein Profil für den App-Code. Beim Start
  wird viel per JIT übersetzt (einzelne Methoden 2–3 s), dazu kommen 921 ms `VerifyClass` auf dem Hauptthread. Voll-AOT im
  Labor ergab TTFD 18,1 s statt 23,3 s (−22 %).
- **Änderung:**
  1. Plugin `androidx.baselineprofile` in `app` und `benchmark`.
  2. `BaselineProfileGenerator` mit den Abläufen Kaltstart → „Alle Sender“ → 30 × Runter → Sender öffnen → 3 × Zappen →
     Filme → Serien → Suche.
  3. Profil auf dem Labor-Emulator erzeugen (userdebug, root, API 28) und `src/main/generated/baselineProfiles` einchecken.
  4. Zusätzlich ein Startup-Profil (`startupProfile`), damit die Dex-Anordnung zum Startpfad passt.
- **Metrik:** TTFD (App „Inhalt sichtbar“, 10 Läufe, Median) und JIT-Zeit im Startfenster (trace_processor: Summe `JIT compiling%`).
- **Prognose:** TTFD −15 bis −20 % (Obergrenze gemessen: −22 %), JIT-Zeit −50 %.
- **Falsch, wenn:** TTFD im Labor um weniger als 10 % sinkt oder `VerifyClass` auf dem Hauptthread über 300 ms bleibt.

### B2 Startarbeit hinter das erste bedienbare Bild schieben

- **Befund:**
  - bindApplication dauert 3,5 s, davon WorkManager-Initializer 0,47 s und Startup-Initializer 0,9 s.
  - Vom Splash bis zur Startseite vergehen 7 s (`resolveStart`), von der Startseite bis zum Inhalt weitere 8 s.
  - Parallel laufen TMDB-Empfehlungen, Sport, VPN-Prüfung, DNS-über-HTTPS-Test und Suchindex-Prüfung.
- **Änderung:**
  1. WorkManager bei Bedarf initialisieren: `Configuration.Provider` in `PlayerApp` und den Initializer per
     `tools:node="remove"` aus dem Manifest nehmen.
  2. `resolveStart` mit `trace("resolveStart:…")`-Abschnitten vermessen. Alles, was nicht das Startziel bestimmt, kommt erst
     nach `FullyDrawnReporter`: `searchRepository.prepare`, EPG-Prüfung, Sport, TMDB, DoH-Probe.
  3. Die Kategorien-Abfrage der Startseite vor allen anderen Flows starten (Prefetch in `resolveStart`, Ergebnis in den ViewModel-Cache).
- **Metrik:** TTID, App „Startbildschirm“, TTFD.
- **Prognose:** Startbildschirm −25 %, TTFD −20 % zusätzlich zu B1 [geraten].
- **Falsch, wenn:** im Trace vor TTFD noch Sport- oder TMDB-Netzaufrufe liegen oder TTFD nicht um mindestens 10 % sinkt.

### B3 Weniger Recomposition je Fokusschritt (Testfall 2, 60 FPS)

- **Befund (framestats, Emulator):** Je Runter-Taste in der Senderliste braucht der UI-Thread p50 284 ms, davon 178 ms
  Recompose und 97 ms Layout/Draw. Hochgerechnet auf den Cube sind das ≈ 71 ms [geraten]. Damit ist jeder Schritt ein
  verpasstes Bild, auch auf dem stärksten Gerät.
- **Änderung, in zwei Schritten:**
  1. **Messen, was neu zusammengesetzt wird:** Labor-Build mit `androidx.compose.runtime:runtime-tracing`, dann tragen
     atrace-Abschnitte die Namen der Composables. Ergänzend Recomposition-Zähler über den Compose-Compiler-Bericht
     (`-PcomposeReports=true`, voller Neubau).
  2. **Gezielt beheben, ohne sichtbare Änderung:**
     - Fokuszustand nur dort lesen, wo er gebraucht wird (Lambda-Modifier statt Zustand im Elternteil).
     - `nowMs` als `() -> Long` an die Zeilen geben, damit der 30-s-Takt nicht alle Zeilen neu zusammensetzt.
     - `EpgDetails` und Vorschau-Kachel vom Listen-Scope trennen.
     - `contentType` und `key` überall prüfen.
     - Fokus-Skalierung per `graphicsLayer` statt Layout-Änderung.
- **Metrik:** UI-Thread je Taste (framestats p50/p90, Emulator) und `Bildzeiten live` (FrameStats).
- **Prognose:** UI-Thread p50 −50 % (≤ 140 ms im Emulator, ≈ 35 ms Cube [geraten]).
- **Falsch, wenn:** framestats-UI p50 im Emulator nicht unter 140 ms fällt oder der Trace weiter dieselben Composables je Schritt zeigt.
- **Abnahme 60 FPS:** nur auf echtem Gerät möglich (V2/V3), da der Emulator die GPU in Software rechnet.

### B4 Import 10 000 Sender < 30 s (Testfall 4)

- **Befund:**
  - Emulator: M3U 10 000 Sender in 112,8 s (19 s Laden, 77 s Verarbeiten, 16 s Nacharbeit).
  - Xtream: Sender 46,5 s, also 3,8-mal so teuer wie ein Film.
  - JVM: Parsen 64 ms, Zuordnen 88 ms (davon Gruppierung 74 ms), Schreiben 86 ms.
  - Die Rechenarbeit erklärt nur einen Teil. Verdacht: Room-Invalidierungen je Batch lösen Beobachter aus
    (Gruppen-Index, Zähler, Paging) sowie die anschließende `channel_flags`-Neuberechnung (korrelierte Unterabfrage mit temporärer Sortierung).
- **Änderung:**
  1. Importphasen mit `trace`-Abschnitten vermessen (Lesen, Zuordnen, `insertAll`, Invalidierung, Nacharbeit).
  2. `ChannelGrouping`: vorab kompilierte Regex und ein Schnellpfad ohne Regex, wenn kein Qualitätskürzel vorkommt.
  3. Schreiben in einer Transaktion je 2 000 Einträge; Beobachter während des Imports über `liveWritesGated` pausieren
     (gibt es schon für Teile) und erst am Ende einmal invalidieren.
  4. `channel_flags` mit Fensterfunktion statt korrelierter Unterabfrage. Nur wenn die Android-9-SQLite (3.22) das unterstützt;
     sonst mit einer Hilfstabelle.
- **Metrik:** `import.m3u10k.ms`, `import.xtream.phasen` (VirtualLabTest) und `jvm.live.grouping`.
- **Prognose:** M3U 10 000 im Emulator −40 % (≈ 68 s → Stick Lite ≈ 40–50 s [geraten]). Das Ziel < 30 s auf dem Stick Lite
  ist damit wahrscheinlich noch nicht erreicht. Bewertung erst nach Schritt 1.
- **Falsch, wenn:** die Phase „Verarbeite Playlist“ im Labor nicht um mindestens 30 % sinkt.

### B5 Datenbank-Indizes (Migration v10) und Query-Plan-Wächter

- **Befund (EXPLAIN, 140 Queries):** Volle Durchläufe auf `episodes` (2 DELETE ohne Index auf `playlistId`) und auf `channels`
  (Sport-Zuordnung über `normalizedName` sowie `ChannelDao.kt:122` über alle Zugänge).
- **Änderung:**
  1. `@Index("playlistId")` auf Episoden und `@Index("normalizedName")` auf Sendern, als Room-AutoMigration 9 → 10 mit Migrationstest.
  2. `query_plans.py` als Unit-Test: Ein neuer SCAN auf großen Tabellen schlägt fehl. Die Positivliste enthält Export-Queries (`SELECT * FROM progress/favorites`).
- **Metrik:** Anzahl voller Durchläufe großer Tabellen.
- **Prognose:** 4 → 0.
- **Falsch, wenn:** EXPLAIN nach der Migration weiter `SCAN channels` oder `SCAN episodes` für diese Queries zeigt oder der Migrationstest Daten verliert.

### B6 Zapping: Aufschlag der Oberfläche halbieren (Testfall 3)

- **Befund:** Im Emulator braucht die Oberfläche 6,3 s (Median), die Engine allein 3,6 s. Der Aufschlag von ≈ 2,7 s entfällt auf
  Adressauflösung, Overlay, Recomposition und Wächter-Start. Auf dem Cube liegt der Median schon bei 0,96 s (Ziel erfüllt).
  Ausreißer bis 50,7 s kommen von Netz bzw. Anbieter.
- **Änderung:**
  1. `trace`-Abschnitte in `vm.zap` → `StreamUrlResolver` → `engine.prepare` → erstes Bild.
  2. Adresse der Nachbarsender vorab auflösen (nur der URL-Bau ohne Netz, keine zweite Verbindung wegen max_connections = 1).
  3. Zap-Banner und Overlay erst nach dem ersten Bild zusammensetzen.
- **Metrik:** UI-Median minus Engine-Median (Labor) und „Start bis Wiedergabe“ (Cube-Protokoll).
- **Prognose:** Aufschlag −50 % (≤ 1,4 s im Emulator).
- **Falsch, wenn:** der UI-Median im Labor nicht unter 5 s fällt oder der Mock-Server mehr als 1 Anfrage je Kanalwechsel sieht.

### B7 Speicherplatz auf dem Gerät – **Entscheidung nötig**

- **Befund:** Die nativen Bibliotheken werden komprimiert ausgeliefert und bei der Installation entpackt (LibVLC allein 38 MB je ABI).
- **Option:** `useLegacyPackaging = false`. Das spart ≈ 85 MB Gerätespeicher, die APK wird aber ≈ 45 MB größer [geraten].
- **Falsch, wenn:** der Installationsplatz (`dumpsys diskstats` / Einstellungen) nicht um mindestens 50 MB sinkt.

---

## C – Sicherheit (Stand nach 3.23)

- **Pinning:** eingebaut und auf Android 9 belegt. Pflege laut `docs/Pinning.md`; nächste Erneuerung vor dem 01.08.2027.
- **P0 HTTP-Anbieter:** Die Zugangsdaten stehen protokollbedingt in jeder URL. Eine Lösung gibt es nur über einen
  HTTPS-fähigen Anbieter; in der App ist nichts zu beheben.

## V – Verifikation

1. **V1 Labor:** Alle Messungen aus 3.23 nach jeder Version wiederholen. Eine Verschlechterung um mehr als 10 % wird
   zurückgenommen.
2. **V2 Fire TV Cube:** Eine Woche normale Nutzung, dann das Fehlerprotokoll schicken. Auswertung: „Inhalt sichtbar“,
   „Bildzeiten …“, „Start bis Wiedergabe“ und „Ende“.
3. **V3 Stick Lite / Chromecast HD:** Nur damit lassen sich 60 FPS und TTFD < 3 s endgültig belegen.

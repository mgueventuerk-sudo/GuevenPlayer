# Virtuelle Messkampagne A (Stabilität) und B (Geschwindigkeit) – 3.23, 02.11.2026

Alle Rohdaten wurden im Labor erhoben. Werte mit [geraten] sind nicht gemessen, sondern hochgerechnet; die Rechnung steht jeweils dabei.

## 1. Labor

| Baustein | Umsetzung |
|---|---|
| Gerät | Android-TV-Emulator, **Android 9** (wie Fire OS 7), x86, 2 Kerne, 1 GB RAM (wie der Fire TV Stick Lite), 1920×1080. Ohne Hardware-Beschleunigung (reine Software-Emulation), GPU per SwiftShader in Software |
| Builds | Release mit R8 und Baseline-Profil (`speed-profile`, wie nach der Installation) plus Debug für Instrumentierung und StrictMode |
| Anbieter | `tools/virtual-lab/mock_iptv.py`: Xtream-API, M3U und XMLTV mit 10 000 Sendern, 5 000 Filmen, 2 000 Serien und 24 000 EPG-Einträgen. Live-Streams ohne Länge und endlos wie bei echten IPTV-Anbietern. Fehlermodi: abgelaufen, falsches Passwort, 500, leer, kaputt, Abbruch, langsam (8 s) und tot |
| Testvideos | H.264-TS, 576p, 720p und 1080p mit 25 fps, ffmpeg testsrc2 |
| Messwege | `VirtualLabTest` (Instrumentierung), `am start -W`, `dumpsys gfxinfo framestats`, atrace + Perfetto trace_processor v59.1 (Prüfsumme gegen das offizielle Wrapper-Skript geprüft), Monkey, `dumpsys meminfo`, `LabJvmBenchmarkTest` (JVM), `query_plans.py` (EXPLAIN QUERY PLAN aller 140 Queries), APK-Analyse |

**Kalibrierung zum echten Gerät:** Der Suchindex lief auf dem Fire TV Cube 3. Gen. (Protokoll 27.10.–02.11.) mit 2 500–3 500
Einträgen/s, im Emulator mit 700–800 Einträgen/s. Der Emulator ist also rund **4× langsamer als der Cube**. Für das Zapping
liegt der Faktor bei 6,6 (Cube p50 955 ms gegen Emulator p50 6,3 s), weil der Emulator H.264 in Software dekodiert.
Der Stick Lite ist schwächer als der Cube: 4× A53 mit 1,7 GHz gegen A73/A53. Angenommen wird ein Faktor 2,5–3 langsamer [geraten].
Daraus folgt: **Stick Lite ≈ Emulator × 0,6–0,75** bei reiner CPU-Arbeit [geraten].
**Nicht übertragbar:** Bildzeiten, weil die GPU in Software auf denselben 2 Kernen läuft, und die absolute Zapping-Zeit (Software-Decoder).

## 2. A – Stabilität

| Prüfung | Ergebnis |
|---|---|
| Ausfallpfade (8, Testfall 5) | 8/8 ohne Absturz, 0 FATAL. Falsches Passwort → „Login fehlgeschlagen“; 500 → „Server antwortet mit HTTP 500“; leer → klare Meldung; tot → „Server nicht erreichbar“. **Kaputtes JSON und Abbruch zeigten rohe englische Texte** („Unterminated string…“, „unexpected end of stream“) → **behoben** |
| Konto abgelaufen | Der Import läuft ohne Fehler (der Anbieter liefert die Listen weiter). Die App zeigt „Abo abgelaufen“ schon am Konto-Symbol, der Stream bekommt 403. Kein Absturz |
| Langsamer Server (8 s je Antwort) | Der Import ist korrekt fertig (139 s). Hauptthread höchstens 108 ms blockiert |
| Monkey, 5 Startwerte × 2 000 Tasten | 0 Abstürze der App. 1 Absturz der Google-Tastatur (fremd). 1 ANR im system_server unter Überlast (Last 14,7, kswapd aktiv). **1 ANR der App** → Ursache gefunden und **behoben** (siehe unten) |
| Home-Taste und zurück im Player (Testfall 6) | Wiedergabe läuft wieder, aber mit Neuaufbau (7,9 s im Emulator). Das ist so gewollt: Media3-Freigabe bei onStop. Siehe Kollision K1 |
| Prozess-Tod im Hintergrund (`am kill`) | Sauberer Neustart, Fokus auf „Live TV“, nach „Runter“ auf „Alle Sender“ (Fix aus 3.21 auf Android 9 bestätigt) |
| Speicher | PSS 89 MB nach dem Start, 105 MB nach 10 Runden durch alle Bereiche, 100 MB nach Player und Trim. Kein Leck-Hinweis, aber über 10 Runden nicht ausgeschlossen |
| Zugangsdaten (Testfall 7) | Passwort `Geheim#Lab-2026!`: 0 Treffer in 30 274 logcat-Zeilen (alle Puffer), im App-Protokoll und in allen App-Dateien (Datenbank verschlüsselt). **Auf der Leitung steht es protokollbedingt in jeder URL** (Mock-Protokoll) → P0 bei HTTP-Anbietern bleibt |
| XMLTV „Billion Laughs“ | Harmlos: kein Hängen, längster Titel 600 Zeichen |

**ANR-Ursache (Monkey, Startwert 55):** Der Hauptthread wartete in `SharedPreferencesImpl.awaitLoadedLocked`. Der Aufruf kam
aus `ParentalControl.<init>` (über die R8-Mapping-Datei entschlüsselt). Ausgelöst hat ihn das verzögerte Anlegen in
`AppContainer.parentalControl` während der Komposition der Startseite. Unter Last kam ein Tastendruck dazu, und nach 5 s gab
es den ANR. Zweite Hauptthread-Lesung (StrictMode im Debug-Build): `VpnStore.rawEntries`, also die VPN-Steuerung, angelegt
aus `MainActivity.onCreate`.

**Gegenprobe nach dem ersten Fix (Falsifikation ausgelöst):** Nach dem Verschieben in `MainActivity` meldete StrictMode
`VpnStore.rawEntries` weiterhin. Der eigentliche Auslöser war `AppContainer.watchTunnel()` in `Application.onCreate`. Dazu kamen
Hauptthread-Lesungen in `SportModule.guideStore`, `ChannelHabits.<init>` und `AutoRecorder.<init>`. Nach der Korrektur gab
es in 2 Kaltstarts (Debug, Emulator) keine dieser Meldungen mehr. Offen ist noch eine `DiskWriteViolation` in
`ChannelPagingSource` (Plan A1).

### Umgesetzte Maßnahmen (aus der Messung)

1. **Dienste für das erste Bild auf IO anlegen** (`resolveStart.warmUp`, `watchTunnel`, `MainActivity`): Jugendschutz, VPN, Sport, Sehgewohnheiten, Auto-Aufnahme.
   - Bewegt: StrictMode-Plattenzugriffe beim Start (vorher 2 je Start), ANR-Risiko bei Tastendruck während des Starts.
   - Prognose: 0 StrictMode-Meldungen für VpnStore und ParentalControl.
   - Messung: Debug-Build, Kaltstart, App-Protokoll `[StrictMode]`.
   - Falsch, wenn: nach dem Umbau weiterhin `VpnStore.rawEntries` oder `ParentalControl` auf dem Hauptthread gemeldet wird.
2. **Deutsche Meldung für kaputte oder abgebrochene Antworten** (`syncErrorText`).
   - Bewegt: Ausfallpfade mit verständlicher Meldung, von 6/8 auf 8/8.
   - Messung: `SyncManagerTest.brokenResponsesAreReadable` sowie die Modi broken und drop.
   - Falsch, wenn: einer der 8 Modi wieder einen englischen Rohtext zeigt.

## 3. B – Geschwindigkeit

### Kaltstart (Testfall 1), Release, 10 Läufe, Median

| Messpunkt | Emulator `speed-profile` | Emulator voll AOT (`speed`) | Cube [geraten, ÷4] | Stick Lite [geraten, ×0,6–0,75] | Ziel |
|---|---|---|---|---|---|
| TTID System (erstes Bild = Splash) | 8,0 s | 7,8 s | ≈ 2,0 s | ≈ 4,8–6,0 s | < 2,5 s |
| App „Startbildschirm“ | 15,2 s | 11,6 s | ≈ 3,8 s | ≈ 9–11 s | – |
| TTFD („Inhalt sichtbar“) | 23,3 s (System 24,0 s) | 18,1 s (System 19,2 s) | ≈ 5,8 s | ≈ 14–17 s | < 3 s |

**Aus dem Trace (atrace, Perfetto trace_processor):**
- bindApplication 3,5 s, davon Startup-Initializer 0,9 s (WorkManager 0,47 s).
- Ein einzelnes Bild dauerte 5,4 s (Recomposer 3,8 s, Measure/Layout/Draw 1,6 s).
- Viel JIT-Übersetzung beim Start (einzelne Methoden 2–3 s) und 921 ms `VerifyClass` auf dem Hauptthread.
- Voll-AOT spart 20 % TTFD. Das zeigt: Das Baseline-Profil (nur 13 KB) deckt den Startpfad unzureichend ab.

### Import (Testfall 4), Emulator

| Lauf | Dauer | Hauptthread max. | Stick Lite [geraten] | Ziel |
|---|---|---|---|---|
| M3U 10 000 Sender | 112,8 s (Laden 19 s, Verarbeiten 77 s, Nacharbeit 16 s), Suchindex 14,2 s | 171 ms | ≈ 68–85 s | < 30 s |
| Xtream komplett mit EPG | 140,8 s (Sender 46,5 s, Filme 6,1 s, Serien 2,2 s, EPG 32 + 17 s), Suchindex 21,4 s | 117 ms | – | – |

- **Kein UI-Freeze:** Der Hauptthread war höchstens 171 ms blockiert (ANR erst ab 5 s).
- **Engpass Sender:** Ein Sender kostet 3,8-mal so viel wie ein Film. Auf der JVM brauchen Parsen, Zuordnen und Schreiben von
  10 000 Sendern nur 14 + 88 + 86 ms. Größter Rechenposten ist die Gruppierung (`ChannelGrouping`, Regex): 74 ms.

### Zapping (Testfall 3), Emulator

| Messweg | Median | Max | > 2,5 s | > 5 s |
|---|---|---|---|---|
| Engine (Media3, ein Player wiederverwendet, 20 Wechsel) | 3,6 s | 12,4 s (erster) | 18/20 | 3/20 |
| Oberfläche (Release, Runter-Taste, 18 Messungen) | 6,3 s | 8,5 s | 18/18 | 13/18 |
| Echtes Gerät (Cube, Protokoll, n = 51) | 0,96 s | 50,7 s | 8 | 1 |

Je Kanalwechsel gibt es genau 1 Stream-Anfrage (wichtig bei Anbietern mit nur 1 Verbindung). Der Wächter hat im Emulator
einmal „Daten, aber keine Wiedergabe“ erkannt und neu verbunden, ohne Absturz.

### Scrollen (Testfall 2), Emulator, 240 × Runter in 57 s

- Gemessen: 133 Bilder in 57 s, p50 828 ms. Diese absoluten Werte sind **nicht übertragbar**, weil die GPU in Software läuft.
- **Aufschlüsselung je Taste (framestats):**
  - UI-Thread p50 284 ms, davon Recompose/Animation 178 ms und Measure/Layout/Draw 97 ms.
  - RenderThread/GPU 135 ms.
- Hochgerechnet auf den Cube (UI-Thread ÷ 4): p50 71 ms je Tastendruck, also deutlich über 16,7 ms [geraten].
- **Befund:** Jeder Fokusschritt setzt sehr viel neu zusammen. Bei der Recomposition liegt der größte Hebel für Testfall 2.

### Datenbank (`query_plans.py`, 140 Queries, Schema v9)

- **Volle Durchläufe großer Tabellen:**
  - `DELETE FROM episodes WHERE playlistId = ?` (2 Queries): kein Index auf `episodes.playlistId`.
  - Sport-Zuordnung `channels WHERE normalizedName IN (…)` und `name LIKE ?`: läuft über alle Sender aller Zugänge; es fehlt ein Index auf `normalizedName`.
  - `ChannelDao.kt:122` (Sender in anderen Zugängen): ebenfalls voller Durchlauf.
- 20 Queries mit temporärer Sortierung, fast alle über kleine Mengen.
- Alle Paging-Queries der Listen nutzen Indizes.

### Größe

- Release ohne x86: **≈ 51 MB** (gepackt).
  - LibVLC: 38 MB je ABI entpackt.
  - WireGuard: 3 MB.
  - FFmpeg: 1,3 MB.
  - Dex: 3,1 MB.
- 35 109 Methoden in 2 Dex-Dateien.
- Native Bibliotheken werden komprimiert ausgeliefert (`useLegacyPackaging`). Das kostet Speicherplatz auf dem Gerät, nicht Startzeit.

## 4. Kollisionen

- **K1, Testfall 6 gegen A:** „Ohne Neuaufbau weiterspielen“ widerspricht „Release an onStop“. Die App gibt den Player frei (spart Speicher auf 1-GB-Geräten); die Rückkehr kostet einen Neustart des Streams.
- **K2, 60 FPS gegen die Messbarkeit:** Ohne echtes Gerät mit GPU lässt sich „Janky Frames < 5 %“ weder belegen noch widerlegen. Belegt ist nur die UI-Thread-Last je Tastendruck.

## 5. Nächste Maßnahmen (nicht umgesetzt, nach Hebel)

| # | Maßnahme | Metrik | Prognose | Falsch, wenn |
|---|---|---|---|---|
| 1 | Baseline-Profil neu erzeugen (Startpfad, Live-Liste, Player) | TTFD | −15 bis −20 % (Voll-AOT-Obergrenze gemessen: −20 %) | TTFD im Labor um weniger als 10 % sinkt |
| 2 | Recomposition je Fokusschritt senken (Zustand tiefer lesen, Vorschau/Infofeld entkoppeln) | UI-Thread je Taste | −50 % [geraten] | framestats-UI p50 nicht unter 140 ms (Emulator) fällt |
| 3 | Startseite: Kategorien vor TMDB/Sport laden, Sport-Abruf erst nach TTFD | TTFD | −20 % [geraten] | „Inhalt sichtbar“ im Labor um weniger als 10 % sinkt |
| 4 | Sender-Gruppierung beschleunigen (Regex vorab, Schnellpfad) | Import Sender | −30 % [geraten] | `jvm.live.grouping` nicht unter 40 ms fällt |
| 5 | Index `episodes(playlistId)`, `channels(normalizedName)` (Migration v10) | Volle Durchläufe | 4 → 0 | EXPLAIN zeigt weiter SCAN |
| 6 | Messung auf echtem Stick Lite | alle | – | – |

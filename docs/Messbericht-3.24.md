# Umsetzung des Plans 3.24 und Nachmessung im Labor (03.11.2026)

Gleiches Labor wie 3.23: Android-TV-Emulator, Android 9, x86, 1 GB, Software-Emulation, Release mit R8. Jede Maßnahme wird
gegen ihre Falsifikationsbedingung aus `docs/Plan-Fixes-3.24.md` geprüft. Werte mit [geraten] sind nicht gemessen.

## Ergebnis je Paket

| Paket | Umsetzung | Messung | Falsifikation |
|---|---|---|---|
| A1 Hauptthread frei | Dienste auf IO angelegt (3.23), Abmelden in `ChannelPagingSource` auf dem Room-Executor, Labor-Schalter `-PlabStrict=true` (StrictMode beendet die App bei jedem Plattenzugriff) | Strict-Build: 3 Kaltstarts und Monkey ohne StrictMode-Absturz (siehe unten) | nicht ausgelöst |
| A2 Start-ANR | über B1/B2 | TTID 8,0 → **5,6 s** (Median aus 10) | Ziel < 6,5 s erreicht |
| A3 Sport | Erster Abruf erst nach „Inhalt sichtbar“; Backoff mit ±20 % Jitter; Start nur bei sichtbarer App (wie bisher) | Im Protokoll steht kein Sport- oder DNS-Abruf mehr vor „Inhalt sichtbar“; Wartezeiten 48–70 s statt einheitlich 60 s | nicht ausgelöst |
| A4 Dauertest | Monkey mit Strict-Build, 2 × 1 500 Ereignisse | siehe unten | – |
| A5 Player 30 s halten | Freigabe 30 s nach onStop; bei Rückkehr weiterspielen ohne neue Engine | Home 10 s → „Zurück ohne Neuaufbau“, Bild läuft weiter. Home 40 s → Neuaufbau wie bisher. 0 Abstürze | nicht ausgelöst |
| B1 Baseline-Profil | `BaselineProfileGenerator` (benchmark), auf dem Emulator erzeugt, zurückübersetzt (`deobfuscate_profile.py`), dazu alle App-Methoden aus der Mapping-Datei (`app_methods_profile.py`) | Profil 11 495 → 15 374 Methoden. Siehe Kaltstart | nicht ausgelöst (nach Hintergrund-Optimierung) |
| B2 Startarbeit | WorkManager bei Bedarf statt in bindApplication; Suchindex, Aktualisierung, EPG, Sport, DNS-Probe und Nacht-Pflege nach „Inhalt sichtbar“ | bindApplication 3,5 → 2,6 s | nicht ausgelöst |
| B3 Scrollen | Analyse (Trace), **keine** unsichtbare Maßnahme mit großem Hebel gefunden | siehe unten | Prognose −50 % **nicht erreichbar** ohne Designänderung |
| B4 Import | `containsSecret` zerlegt Adressen nur noch bei „@“ oder „?“; Land je Gruppe einmal; Regex vorab übersetzt (CountryDetector, TitleCleaner) | JVM: Gruppierung 74 → 56 ms | Emulator-Nachmessung siehe unten |
| B5 Indizes | Migration 9 → 10 (`channels.normalizedName`, `episodes.playlistId`); Gegenstücke über indizierten `epgKey` mit exaktem Abgleich | `Migration9To10Test`: alle 4 früheren vollen Durchläufe nutzen jetzt Indizes | nicht ausgelöst |
| B6 Zapping | siehe unten | – | – |
| B7 Packaging | **verworfen** nach Rechnung | Unkomprimiert: APK ≈ 105 MB statt 51 MB, Gerätespeicher gleich (heute 51 MB + 43–57 MB entpackt) | Bedingung „spart ≥ 50 MB“ schon rechnerisch verfehlt |

## Kaltstart (Testfall 1), 10 Läufe, Median, Emulator

| Messpunkt | 3.23 | 3.24 frisch installiert | 3.24 nach Hintergrund-Optimierung |
|---|---|---|---|
| TTID (System) | 8,0 s | 7,1 s | **5,6 s** |
| App „Startbildschirm“ | 15,2 s | 13,0 s | **8,7 s** |
| TTFD (App „Inhalt sichtbar“) | 23,3 s | 20,9 s | **13,8 s** |
| TTFD (System) | 24,0 s | 21,7 s | **14,7 s** |

**Wichtig für Android 9 (Fire OS 7):** Die Installation übersetzt den Code noch nicht mit dem Baseline-Profil. Im Labor war
das Referenzprofil nach der Installation 0 Byte groß. Erst die Hintergrund-Optimierung (`bg-dexopt`, auf dem Fire TV im
Leerlauf, meist nachts) wendet das Profil an. Deshalb gibt es die zwei Spalten:
- „frisch installiert“: −10 % TTFD, im Wesentlichen aus B2/A1.
- „nach Optimierung“: −41 % TTFD gegenüber 3.23.

Zum Vergleich: In 3.23 war der vollständig vorübersetzte Code (Voll-AOT) die Obergrenze mit 18,1 s. Die neue Messung liegt
darunter, weil zusätzlich B2 und A1 wirken. Hochgerechnet auf den Cube (÷ 4) wäre TTFD ≈ 3,4 s [geraten].

## B3 Scrollen – Trace der Senderliste (40 Schritte)

Hauptthread je Bild (Emulator):
- Recompose 1 491 ms gesamt;
- Text messen (`TextAnnotatedStringNode:measure`, 64 ×) 1 045 ms;
- Vorab-Aufbau neuer Zeilen (`lazy:prefetch`) 1 706 ms;
- Fokus-Suche/-Wechsel 775 + 495 ms;
- Fokus-Animationen (Recomposer:animation) 1 199 ms.

Der Aufwand entsteht im Framework für jede neu ins Bild rückende Zeile (Aufbau, Textmessung, Fokus-Animation mit Glow und
Skalierung), nicht in einem einzelnen App-Fehler. Composable-Namen im Trace (`runtime-tracing`) liefert Android 9 nicht.

**Ohne sichtbare Änderung gibt es keinen Hebel von −50 %.** Möglich wäre auf schwachen Geräten, wo die App
„Effekte reduzieren“ ohnehin automatisch einschaltet: Glow und Skalierung der Listenzeilen dort abschalten. Das ist eine
Design-Änderung und braucht deine Entscheidung.

## A1/A4 – Hauptthread und Dauertest (Strict-Debug-Build)

- **3 Kaltstarts und 2 Monkey-Läufe** (je 1 500 Ereignisse), StrictMode beendet die App bei jedem Plattenzugriff auf dem
  Hauptthread: **kein StrictMode-Absturz**, 0 App-Abstürze.
- **2 ANRs der Art „Fenster noch nicht da“:** Beide kamen in `bindApplication` beim Klassenladen des Debug-Builds (ohne
  Vorübersetzung, Software-Emulation), während Monkey schon Tasten schickte. Kein Fehler im App-Code, aber ein Hinweis:
  Der Start muss unter 5 s bleiben. Im Release nach der Optimierung liegt er im Emulator bei 5,6 s (TTID), auf dem Cube
  hochgerechnet bei ≈ 1,4 s [geraten].

## B4 Import – Emulator (Debug-Build wie in 3.23)

| | 3.23 | 3.24 |
|---|---|---|
| M3U 10 000 gesamt | 112,8 s | 111,0 s |
| Phase „Verarbeite Playlist“ | 77,4 s | 63,3 s (−18 %) |
| Hauptthread max. | 171 ms | 62 ms |

- **Falsifikation ausgelöst:** Die Phase „Verarbeiten“ sollte um ≥ 30 % sinken, gemessen sind −18 %.
- Auf der JVM dauert derselbe Import 2,2 s. Der Faktor 50 im Emulator kommt vor allem vom Debug-Build (kein AOT, eingeschränkter
  JIT) auf Software-Emulation. Ein Release-Wert für den Import fehlt.
- Nächster Hebel laut JVM-Profil: `MediaTitle.parse` (65 ms von 2,2 s).

## B6 Zapping

- Engine allein (Debug, Live-Streams in Echtzeit, 20 Wechsel): Median 2,9 s, 2 Wechsel über 5 s.
- Die Messung der Oberfläche („Start bis Wiedergabe“) beginnt erst bei `prepare()`, enthält also keine Oberflächenarbeit.
  Der Abstand zu 6,3 s (3.23, Release) liegt damit in der Player-Konfiguration der App, nicht in der Oberfläche:
  Startpuffer „Standard“ 1,5 s statt 1 s, Bildfrequenz-Anpassung, Decoder-Einstellungen.
- **Keine Code-Änderung in 3.24.** Der Plan-Ansatz (Oberflächen-Aufschlag halbieren) beruhte auf einer falschen Annahme.
- Auf dem Cube liegt der Median schon bei 0,96 s.

## Sicherheit – Nachprüfung

Die Pinning-Tests auf Android 9 laufen weiter grün: fremde CA → „Pin verification failed“, echte Kette akzeptiert.

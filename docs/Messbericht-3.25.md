# Umsetzung B1–B4 und Nachmessung im Labor (03.11.2026, Version 3.25)

Gleiches Labor wie 3.23/3.24: Android-TV-Emulator, Android 9, x86, 1 GB, Software-Emulation. Alle Vergleiche sind
**A/B in derselben Emulator-Sitzung** (abwechselnd installiert), weil die Werte zwischen Sitzungen um bis zu 20 % schwanken.
A4 (Dauertest) wurde wie gewünscht nicht durchgeführt. Werte mit [geraten] sind nicht gemessen.

## Ergebnis je Paket

| Paket | Umsetzung | Messung (gleiche Sitzung) | Bewertung |
|---|---|---|---|
| B1 Startprofil | Eigenes Startprofil (`baselineProfiles/startup-prof.txt`); R8 ordnet Startcode in der ersten DEX-Datei an | zusammen mit B2 gemessen | wirkt |
| B2 Kategorien vorab | Beim Start werden die Live-Kategorien des aktiven Zugangs schon geladen (`StartCache`), die Senderliste zeigt sie ohne zweite Abfrage | „Inhalt sichtbar“ 18,8 → **12,4 s (−34 %)**; TTFD (System) 20,0 → **13,5 s** | wirkt |
| B3 Schwache Geräte | Mit „Effekte reduzieren“ (auf schwachen Geräten automatisch an): kein Leuchtschatten, kein Lichtreflex | Scrollen: UI-Thread −9 %, Recompose −21 %, RenderThread −16 % (p50) | wirkt, nur Stick-Lite-Klasse |
| B3 Skalierung aus | Listenzeilen ohne Vergrößern | UI-Thread 250 → 290 ms (p50), kein Gewinn | **verworfen**, zurückgebaut |
| B4 Pipelining | Schreiben eines Blocks parallel zum Lesen des nächsten | laden 83–88 s ohne, 98–101 s mit Pipelining | **verworfen**, zurückgebaut |
| B4 Suchindex | Aufbau nach dem Import im Hintergrund; Suche wartet bis 3 s, sonst alter Index | M3U 10 000: **117,6 / 121,5 → 88,8 / 92,3 s (−24 %)** | wirkt |
| B4 Phasen | Protokoll „Import-Phasen: laden (davon schreiben), aufräumen, Gruppen, Suchindex“ | Messwerkzeug | – |

## Kaltstart (Testfall 1), 6 Läufe je Version, Median, Release nach `bg-dexopt`

| Messpunkt | 3.24 | 3.25 |
|---|---|---|
| TTID (System) | 7,2 s | 7,3 s (unverändert) |
| App „Startbildschirm“ | 12,0 s | 14,5 s (+2,5 s) |
| App „Inhalt sichtbar“ | 18,8 s | **12,4 s** |
| TTFD (System) | 20,0 s | **13,5 s** |

- „Startbildschirm“ kommt später, weil das Vorab-Laden der Kategorien in derselben Startphase läuft. Der Inhalt steht
  trotzdem 6,4 s früher, also vor dem Startbildschirm-Messpunkt. Für dich zählt „Inhalt sichtbar“.
- Die 3.24-Werte dieser Sitzung liegen über denen aus `Messbericht-3.24.md` (13,8 s). Das ist Sitzungsschwankung.
  Deshalb wird nur innerhalb einer Sitzung verglichen.

## Import (Testfall 3), M3U mit 10 000 Sendern, Debug-Build

| Lauf | 3.25-Zwischenstand (Pipelining, Index synchron) | 3.25 final |
|---|---|---|
| gesamt | 117,6 s / 121,5 s | 88,8 s / 92,3 s |
| davon laden | 97,9 s / 100,6 s | 83,3 s / 87,9 s |
| davon schreiben | 31,0 s / 32,7 s | 14,1 s / 17,8 s |
| Gruppen | 2,9 s / 3,3 s | 4,0 s / 3,1 s |
| Suchindex | 15,5 s / 16,6 s (blockiert) | im Hintergrund (14–22 s danach) |

- Drei weitere Läufe von 3.25 final in der Sitzung davor: 95,2 s, 91,0 s, 115,2 s. Median aller fünf: **92,3 s**.
  3.24 im Bericht: 111,0 s.
- Hauptthread max.: 111–466 ms in beiden Versionen, ohne Zusammenhang mit der Änderung.
- Die meiste Zeit bleibt beim Lesen und Aufbereiten (Debug-Build ohne AOT auf Software-Emulation). Auf der JVM dauert
  derselbe Import 2,2 s. Auf dem Cube [geraten]: ≈ 4–6 s Ersparnis je großer Aktualisierung.

## Scrollen (Testfall 2), Senderliste 240 × Runter, Release mit „Effekte reduzieren“

| p50 | 3.24 | Leuchtschatten/Reflex aus | zusätzlich Skalierung aus |
|---|---|---|---|
| UI-Thread | 294 ms | 268 / 251 ms | 291 ms |
| Recompose/Animation | 209 ms | 165 / 150 ms | 192 ms |
| RenderThread+GPU | 154 ms | 129 / 123 ms | 126 ms |

- Der Leuchtschatten kostet die GPU am meisten. Abschalten wirkt in zwei Sitzungen gleich.
- Skalierung aus brachte nichts. Die Änderung ist zurückgebaut, Listenzeilen vergrößern sich wie bisher.
- Der vierte Messlauf blieb bei der Installation im Emulator hängen. Er fehlt.

## Was das für deine Geräte heißt

- **Cube:** schnellerer Start (B1/B2) und kürzeres Aktualisieren (B4). Die Optik bleibt gleich.
- **Stick Lite** (automatisch „Effekte reduzieren“): zusätzlich ruhigeres Scrollen ohne Leuchtschatten.
- **Profil:** Erst nach der nächtlichen Hintergrund-Optimierung von Fire OS greift es voll, wie in 3.24.

# Overhaul – Schritt 0: Baseline, Messkampagne, Threat Model (Stand 3.22, 02.11.2026)

Grundlage: Fire-TV-Protokoll 27.10.–02.11. (AFTGAZL = Fire TV Cube 3. Gen., Android 9, 2 415 Zeilen, Debug-Build) und der Code-Stand 3.21/3.22.
Nichts hier ist auf einem Fire TV Stick Lite oder Chromecast gemessen. Werte ohne Messung sind mit [geraten] markiert.

## 1. Baseline (Ist)

| Bereich | Ist-Wert | Quelle | Ziel (Prompt) | Lücke |
|---|---|---|---|---|
| Abstürze | 2 Abstürze (beide WireGuard, 3.14), behoben und per javap geprüft | Gerätelog | ≥ 99,5 % absturzfreie Sitzungen | Keine Sitzungszahl, kein 90-Tage-Zeitraum, nur 1 Gerät |
| ANR | 0 Einträge „App reagierte nicht“ (ExitReasons erst ab Android 11, Fire TV hat 9) | Gerätelog | ≥ 99,0 % ANR-freie Sitzungen | **Auf Android 9 nicht messbar**, ANR-Traces fehlen |
| Kaltstart TTID | 3.18: 14 714 ms; plausible Werte 988–55 996 ms (n = 18 Starts, gemischt kalt/warm) | Gerätelog | < 2,5 s | Kein Median aus 10 Kaltstarts, Debug-Build |
| Kaltstart TTFD | nicht gemessen | – | < 3 s | **Ab 3.22 im Log** („Inhalt sichtbar“) |
| Zapping | n = 51, p50 955 ms, p90 2 552 ms, max 50 731 ms; 8 > 2,5 s, 1 > 5 s | Gerätelog | Median < 2,5 s, keiner > 5 s | Median erfüllt; Ausreißer offen |
| Janky Frames | nicht gemessen | – | < 5 % über 60 s Scroll | **Ab 3.22 im Log** („Bildzeiten …“); Perfetto fehlt |
| Import | Suchindex 59–103 s bei 209k–255k Einträgen | Gerätelog | 10k-Kanäle < 30 s | Kein 10k-Lauf; Import-Dauer allein nicht getrennt geloggt |
| Hauptthread | 24 StrictMode-Plattenzugriffe im Startpfad (vor 3.19) | Gerätelog | 0 | Nach 3.19 nicht neu gemessen |
| VPN | 485 Neuverbindungen in 6 Tagen (vor 3.19) | Gerätelog | – | Nach 3.19 nicht neu gemessen |
| APK / Methoden / Heap | nicht erhoben | – | – | Release-APK-Größe, Heap-Profil fehlen |
| Leaks | DebugChecks (StrictMode-VM-Policy) meldet Leaks; kein LeakCanary | Code | – | Kein Leak-Bericht vorhanden |
| Sicherheit | siehe Abschnitt 3 | Code | – | Proxy-Mitschnitt fehlt |
| UI | Fotos einzelner Screens (Fire TV 4K-Klasse, Handy) | Chat | Screens/Video auf Stick Lite | **Kein Stick Lite, kein Chromecast** |

## 2. Messkampagne (vor jeder Optimierung)

**Instrumentierung (in 3.22 eingebaut, nur lokales Protokoll, keine Daten nach außen):**
- `Messung: Start bis Startbildschirm` = TTID (bestand), `Messung: Start bis Inhalt sichtbar` = TTFD (neu, `reportFullyDrawn`
  sobald Kategorien bzw. Startseiten-Reihen geladen sind).
- `Messung: Bildzeiten <Bereich>` je Bildschirm (neu, FrameMetrics wie JankStats): Anteil verpasster Bilder (> 1/Hz), ruckeliger
  Bilder (> 2/Hz), p50/p90/p99, eingefrorene Bilder (> 700 ms). Debug-Builds sind markiert, weil sie deutlich langsamer sind.
- `Umschaltzeit` (Zapping) und `Ende` (ExitReasons, ab Android 11) bestehen schon.

**Labormessung (adb, Release-Build):**
1. `./gradlew :benchmark:connectedBenchmarkAndroidTest` gegen das Gerät: `coldStartWithoutCompilation`, `coldStartWithBaselineProfile`
   (je 10 Läufe → Median TTID/TTFD), `liveListScroll` (neu: 240 × Runter in 60 s → FrameTimingMetric), `playerStartsFromFixtureChannel`.
2. Perfetto: `adb shell perfetto -c - --txt -o /data/misc/perfetto-traces/scroll.pftrace` mit `gfx view input am dalvik sched freq`
   für 60 s Scroll, 10 × Zapping, 10 × Kaltstart; Auswertung in ui.perfetto.dev (Actual vs. Expected Frame Timeline).
3. Testzugang: dieselbe Playlist auf allen Geräten, ≥ 10 000 Sender, ≥ 500 in einer Liste, 1 Sender `BENCHMARK_TEST_CHANNEL`.

**Gerätematrix:** Fire TV Stick Lite (Pflicht, schwächstes Gerät), Fire TV Stick 4K (Max), Chromecast HD, Chromecast 4K, 1 Handy.
Vorhanden ist nur AFTGAZL und ein Handy. AFTGAZL ist laut aftvnews der **Fire TV Cube 3. Generation** (Codename „Gazelle“) –
das stärkste Fire-TV-Gerät, kein Stick. Alle Werte aus Abschnitt 1 sind damit eine **Obergrenze**; auf dem Stick Lite sind
Start, Zapping und Bildzeiten schlechter [geraten: Faktor unbekannt, nur durch Messung klärbar].

**Was ich vom Nutzer brauche:** (a) Release-APK eine Woche normal nutzen, dann Fehlerprotokoll schicken; (b) wenn möglich
einen Stick Lite oder Chromecast HD; (c) für den Laborteil einen Rechner mit adb im selben Netz wie der Stick und eine Verbindung
dieses Chats mit dem Rechner (dann kann ich Benchmark und Perfetto selbst ausführen).

## 3. Threat Model (STRIDE)

Werte: Xtream-/M3U-Zugangsdaten, WireGuard-Privatschlüssel, API-Schlüssel (TMDB, Bzzoiro), Sicherungsdatei, Sehverlauf.
Angreifer: (M) MitM im Netz, (P) kompromittierte Playlist-/EPG-Quelle, (A) andere Apps auf dem Gerät, (U) Mitnutzer.

| | Bedrohung | Ist-Schutz | Restrisiko / Maßnahme |
|---|---|---|---|
| **S** Spoofing | (M) falscher Server/Zertifikat | System-CAs only (keine Nutzer-CAs), HTTPS-only für Schlüssel-Dienste | **HTTP-Anbieter:** keine Server-Echtheit. Pinning nur für eigene Dienste sinnvoll; Anbieter wechseln Zertifikate ohne Ankündigung |
| | (U) fremde Bedienung | App-Sperre/Jugendschutz-PIN (PBKDF2) | gering |
| **T** Tampering | (P) manipulierte M3U/XMLTV/JSON | Streaming-Parser, Feld-/Größenlimits (JsonExt 128 Felder, 8 KB), Header-Prüfung (StreamTarget) | XML-Entity-Expansion im XMLTV-Parser prüfen [geraten: XmlPullParser ohne DTD ist sicher, nicht verifiziert] |
| | (M) Stream-/EPG-Austausch über HTTP | – | nur mit HTTPS lösbar |
| | Sicherungsdatei verändert | AES-GCM, Kopf als AAD, Rundenzahl begrenzt | gering |
| **R** Repudiation | – | lokales Protokoll, kein Server | nicht relevant (Einzelnutzer-App) |
| **I** Information Disclosure | (M) **Xtream-Zugangsdaten stehen protokollbedingt in jeder URL** (`player_api.php?username=…&password=…`, `/live/user/pass/id.ts`) | Klartext-Verkehr je Zugang nur mit Freigabe und Warnung (Einstellungen) | **P0 bei HTTP-Anbietern: Jeder im selben WLAN/beim Provider kann die Zugangsdaten mitlesen. Keine App-Änderung behebt das, nur ein HTTPS-fähiger Anbieter.** |
| | (A) andere Apps lesen Daten | Zugangsdaten mit Keystore-Schlüssel verschlüsselt (SecretCipher), `allowBackup=false`, Protokoll geschwärzt (LogRedactor: user/pass/token/Pfad-Zugangsdaten/PrivateKey/E-Mail) | logcat ist ab Android 4.1 für Fremd-Apps gesperrt; per adb lesbar → Schwärzung ist die Schutzschicht |
| | (U) Mitnutzer sieht Verlauf | „Ohne Verlauf“-Modus, App-Sperre | Löschfunktion für Verlauf/Protokoll vorhanden [geraten: Vollständigkeit nicht geprüft] |
| | Heimnetz-Freigabe (DeviceShareServer) | 16-stelliges Token, Vergleich in konstanter Zeit, Sicherheits-Header | gering, nur LAN |
| **D** Denial of Service | (P) Riesen-Playlist, Endlos-Stream, 2^31 PBKDF2-Runden | Streaming-Import, Paging, Rundenlimit, Timeouts | 10k-Import ohne Freeze ist ungemessen |
| **E** Elevation of Privilege | (A) exportierte Komponenten | Nur Launcher-Activity und 2 Boot-Empfänger exportiert (geschützte System-Broadcasts, Aktion wird geprüft); kein WebView; R8 im Release | gering |

## 4. Regelkollisionen – brauchen eine Entscheidung (keine stille Opferung)

1. **Startziel Netflix-Home ↔ Wunsch vom 02.11.:** Kaltstart soll auf „Alle Sender“ in Live TV landen. Beides gleichzeitig geht nicht.
2. **EPG-Grid entfernen ↔ vorhandener TV-Guide-Tab:** Er ist heute schon ausblendbar („TV-Guide ausblenden“). Entfernen löscht eine
   genutzte Funktion; Ausblenden als Voreinstellung erfüllt „nicht in der UI“ ohne Codeverlust.
3. **HTTPS erzwingen ↔ eigener Anbieter nur über HTTP:** Erzwingen = Anbieter funktioniert nicht mehr. Ist-Zustand erfüllt Testfall 8
   („isoliert mit Warnung“).
4. **Zertifikats-Pinning ↔ Fremd-APIs/IPTV-Anbieter:** Pins brechen beim Zertifikatswechsel des Dritten → App ohne Daten bis zum Update.
5. **Crashlytics ↔ Datenschutz:** Heute bewusst nur lokales Protokoll. Crashlytics schickt Gerätedaten an Google.
6. **RecyclerView/ConstraintLayout ↔ Compose-App:** Die App ist durchgehend Compose; Entsprechung (LazyColumn + Paging 3 + Keys/contentType)
   ist vorhanden. Umbau auf Views wäre ein Neubau der UI ohne belegten Nutzen.
7. **Stalker-Portal:** nicht vorhanden; eigenes Protokoll (MAC-Adresse, Token-Handshake) – großes neues Feature.
8. **Große Hero-Banner ↔ 60 FPS (Testfall 11):** beide erfüllbar mit fester Zielgröße beim Laden (Coil `size`), Hardware-Bitmaps
   und Vorladen des nächsten Banners; Wirkung erst nach Messung belegbar.

## 5. Falsifikation der Instrumentierung selbst

- Die TTFD-Messung ist falsch, wenn „Inhalt sichtbar“ vor „Startbildschirm“ im Protokoll steht oder bei leerer Playlist auftaucht.
- Die Bildzeiten-Messung ist falsch, wenn Macrobenchmark `liveListScroll` (FrameTimingMetric) und die Protokollzeile für denselben
  Ablauf um mehr als 3 Prozentpunkte beim Anteil verpasster Bilder abweichen [Toleranz geraten].
- Kosten: Die Messung ist zu teuer, wenn TTID mit und ohne `FrameStats.attach` (A/B, 10 Kaltstarts) um mehr als 50 ms abweicht.

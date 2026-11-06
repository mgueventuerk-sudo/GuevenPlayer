# GuevenPlayer – IPTV & Mediathek für Android TV und Handy

Xtream Codes & M3U (mehrere Zugänge mit Schnellwechsel) · Live-TV mit EPG und TV-Guide · Catch-up ·
Aufnahmen · Filme · Serien mit „Intro überspringen“ und „Nächste Folge“ · Favoriten (auch ganze Gruppen) ·
TMDB-Infos · Kindersicherung · Sicherung per Handy · Suche über alle Playlists · Erinnerungen · Sleep-Timer ·
Live-Sport (immer online, eigene Ligen).

## APK bauen

**Automatisch (GitHub Actions):** Jeder Push auf `main` führt die Tests aus und baut eine Release- und eine Debug-APK.
Unter *Actions → letzter Lauf → Artifacts → `Player-APK-<Nr>`* herunterladen:

- `Player-<Nr>.apk` – optimierte Release-Version (zum Installieren empfohlen)
- `Player-<Nr>-debug.apk` – Debug-Version als Rückfall, falls die Release-Version Probleme macht

Die Debug-APK verwendet den festen Debug-Schlüssel aus `keystore/`. Die Release-APK wird ausschließlich mit einem
extern bereitgestellten Release-Schlüssel gebaut.

**Release-Schlüssel:** Repository-Secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` und
`SIGNING_KEY_PASSWORD` anlegen. Lokal dieselben vier Werte als Umgebungsvariablen setzen. Ohne Release-Schlüssel bricht
`assembleRelease` ab; der Debug-Schlüssel wird nie als Ersatz verwendet. Ein Wechsel des Release-Schlüssels braucht
einen Updateplan für bestehende Installationen.
Neuen Schlüssel anlegen: `tools/release-key.sh erzeugen ~/sicher/guevenplayer.jks` (Passwörter werden abgefragt,
nichts landet im Repository); Signatur einer APK prüfen: `tools/release-key.sh pruefen GuevenPlayer.apk`.
**Sicherung:** .jks-Datei und beide Passwörter an zwei getrennten Orten aufbewahren – ohne sie sind keine Updates der
installierten Release-App mehr möglich (nur Neuinstallation, Daten weg).

**Lokal (Windows):** `build.bat` doppelt anklicken. Es nutzt Java und SDK aus Android Studio, schreibt das Protokoll
nach `build-log.txt` und legt die fertigen Dateien als `GuevenPlayer.apk` und `GuevenPlayer-debug.apk` in den
Projektordner. Alternativ in Android Studio: *Build → Generate APKs*.

## Performance auf Geräten messen

Das Macrobenchmark-Modul misst Kaltstart ohne/mit Baseline Profile und einen Rundgang durch Live-TV, Filme, Serien und
Suche; Start, Navigation und Player erfassen zusätzlich den maximalen Speicherverbrauch. Für den Vergleich auf
schwacher und starker Android-TV-Hardware dieselbe Test-Playlist einrichten und dann
`tools/benchmark-device-info.sh <ADB-Seriennummer>` sowie `./gradlew :benchmark:connectedReleaseAndroidTest` auf jedem
Gerät ausführen. Ein cloud-only Lauf benötigt eine Remote-Device-Farm; die Messwerte vom 31.10.2026 und der offene
Gerätevergleich stehen in [benchmark/RESULTS-2026-10-31.md](benchmark/RESULTS-2026-10-31.md).

## Architektur

| Paket | Inhalt |
|---|---|
| `core` | `AppContainer` (manuelle DI), Netzwerk, Mini-Webserver, Verschlüsselung, Kindersicherung, App-Protokoll |
| `domain` | Modelle (`@Immutable`), Repository-Interfaces, Use Cases, Binge-Logik |
| `data` | Room (Paging, Batch-Inserts, Generationen-Import), Streaming-Parser (M3U, Xtream, XMLTV/.gz), 3-Stufen-EPG, Catch-up, TMDB, Sicherung |
| `player` | `VideoPlayerEngine`-Strategy: Media3, LibVLC, System-Player, externer Player; Auto-Frame-Rate, Reconnect |
| `ui` | Compose for TV: Live-TV, TV-Guide, Filme, Serien, Suche, Player-Overlays, Einstellungen |
| `ui/theme`, `ui/common` | Design-System: Paletten hell/dunkel × 5 Akzentfarben, Glas-Flächen, animierte Icon-Familie (`AppIcons`), Fokus-Flächen, obere Navigationsleiste |

## Sicherheit

- Passwort und M3U-Adresse liegen mit dem Android Keystore verschlüsselt in der Datenbank. Ist der Keystore
  defekt (manche TV-Boxen), dient ein zufälliger Schlüssel in einer app-privaten Datei als Ersatz – nie Klartext.
- Xtream-Stream-Adressen werden ohne Zugangsdaten gespeichert (`xt://…`) und erst beim Abspielen zusammengesetzt.
- Sicherungen werden mit einem selbst gewählten Kennwort verschlüsselt (PBKDF2 + AES-256-GCM). Das Kennwort wird
  nur am Fernseher eingegeben – übers Heimnetz zum Handy geht ausschließlich die verschlüsselte Datei.
  Wiederherstellen prüft erst die ganze Datei und schreibt dann alles in einer Transaktion.
- Keine Android-Cloud-Sicherung der App-Daten (Zugangsdaten bleiben auf dem Gerät).
- PIN-Abfrage und Bestätigungen sind eigene Fenster: Der Fokus kann nicht auf den geschützten Inhalt dahinter springen.
- Gesperrte Gruppen erscheinen auch nicht in Favoriten, „Zuletzt gesehen“ und „Weiterschauen“, und „Mit zuletzt
  geschautem Sender starten“ überspringt Sender aus gesperrten Gruppen – Zugang nur über die Gruppe mit PIN.
- Zugangsdaten erscheinen nie im Protokoll (Adressen werden auf Server und Port gekürzt).
- Nur System-Zertifikate gelten als vertrauenswürdig; HTTP ist je IPTV-Zugang standardmäßig gesperrt und muss bewusst freigegeben werden.
- PIN als PBKDF2-HMAC-SHA256 (100.000 Runden, Format wie 3.1); M3U-Adressen ohne Zugangsdaten gespeichert.
- Einrichtungs- und Kennwortbildschirme sind gegen Screenshots geschützt; die QR-Einrichtung antwortet nur Geräten
  im eigenen Netz und erst nach geprüftem Token.
- Sportmonks-, Bzzoiro- und TMDB-Schlüssel werden mit dem Android Keystore verschlüsselt gespeichert. Zugangsdaten in M3U-Stream-URLs oder Authentifizierungsheadern werden ebenfalls verschlüsselt abgelegt.
- „Automatisch optimieren“ passt Player, Puffer und Oberflächeneffekte an die erkannte Geräteklasse an; HTTP-Freigaben und andere Sicherheitsoptionen bleiben unberührt.

### Eingebautes VPN (ab 3.6)

- Eigener WireGuard-Client (`com.wireguard.android:tunnel`, Android-VpnService, kein Root). **Nur GuevenPlayer**
  läuft durch den Tunnel (`includedApplications`), andere Apps bleiben unberührt. DNS und IPv6 gehen mit durch den
  Tunnel, Keepalive 25 s. Braucht Android 7 oder neuer.
- **Immer an:** startet mit der App, nach dem Einschalten des Geräts und nach Updates; Android-Option
  „Durchgehend aktives VPN“ wird unterstützt. Überwachung über den letzten Handshake (tot nach 185 s),
  Neuverbindung sofort, dann nach 2, 5, 10, 30 s und danach jede Minute.
- **Keine Notbremse:** Steht der Tunnel nicht, wird er abgebaut und die Wiedergabe läuft über die normale Leitung
  weiter. Dafür überall sichtbar: rotes Band oben über allem (auch im Vollbild), Schild oben
  (grün „DE“, gelb, rot, grau durchgestrichen) und im Player, Benachrichtigung „VPN getrennt“ mit „Neu verbinden“,
  grüne Meldung „VPN wieder verbunden“, optional Ton/Vibration.
- **Surfshark-Server per Klick (ab 3.8):** Einmal eine WireGuard-Datei aus dem Surfshark-Konto übertragen
  (*VPN einrichten*). Der Schlüssel darin gilt für alle Surfshark-Server; *Surfshark-Server wählen* zeigt dann die
  öffentliche Serverliste (Deutschland zuerst, Auslastung) und übernimmt einen Server mit einem Klick (ersetzt den
  vorigen gewählten). Anmeldung mit E-Mail/Passwort ist als Versuch dabei – Surfshark blockiert sie für Apps per
  Cloudflare meist; dann kommt eine klare Meldung. Passwort nie gespeichert, Schlüssel nur verschlüsselt.
- Serveradressen (z. B. `de-ber.prod.surfshark.com`) löst die App selbst auf, mit DNS über HTTPS als Rückfall.
- Einrichtung: *Einstellungen → Sicherheit & VPN → VPN einrichten* → QR-Code scannen → .conf (z. B. Surfshark)
  am Handy wählen oder einfügen. Der Browser verschlüsselt die Datei mit ChaCha20; der Schlüssel steht nur im
  QR-Code (URL-Fragment) und geht nie übers Netz. Am Handy auch direkt: Datei wählen oder Zwischenablage.
- Die Konfiguration (mit privatem Schlüssel) liegt nur Keystore-verschlüsselt vor, in der Sicherung nur mit dem
  Sicherungskennwort verschlüsselt; im Protokoll stehen nur Zeitpunkt, Standortname und Grund.

### Datenschutz-Schalter (Sicherheit & VPN)

- **Online-Zusatzinfos** (Standard an): aus = keine Anfragen an Sport- und TMDB-Dienste.
- **Verschlüsseltes DNS** (Standard an): solange das VPN nicht verbunden ist, DNS über HTTPS (Cloudflare, Quad9).
  Heimnetz-Namen und IP-Adressen gehen ans normale DNS; ist DoH blockiert, 10 Minuten normales DNS. Gilt für
  ExoPlayer, EPG, Sport und TMDB (LibVLC löst Namen selbst auf).
- **Ohne Verlauf:** kein Fortschritt, kein „Zuletzt gesehen“, kein letzter Sender, keine Aktivitäten im Protokoll
  (Fehler, Abstürze und VPN-Abbrüche werden weiter erfasst).
- **App-Sperre:** PIN (die der Kindersicherung) beim Öffnen und nach jedem Wechsel in den Hintergrund; Vorschau in
  „Zuletzt verwendet“ verdeckt (Android 13+, darunter am Handy per Bildschirmschutz). Gesperrte Kategorien bleiben gesperrt.
- **HTTP-Warnung:** Zugänge mit `http://` zeigen bei der Einrichtung und in den Einstellungen einen roten Hinweis.

## Stabilität und Tempo

- **Neu in 3.15 – Wiedergabe-Wächter** (*Einstellungen → Wiedergabe → Wiedergabe-Wächter*, Standard: an). Ein
  zentraler Wächter prüft bei Start und Störungen alle 0,5 s, bei stabiler Wiedergabe jede Sekunde (Daten, Bilder,
  Ton, Puffer, Live-Abstand) und erkennt
  u. a. Start ohne Daten, Hänger, Standbild, Ton ohne Bild, Bild ohne Ton, wachsenden Live-Abstand, Serverüberlastung
  (HTTP 429 inkl. `Retry-After`), Decoder- und Tonausgabe-Fehler. Je Befund arbeitet er eine feste Stufenleiter ab
  (z. B. Standbild: Bildspur neu → neu verbinden → sicherer Decoder → LibVLC), höchstens 6 Eingriffe je Sendung und
  3 pro Minute, 10 s Pause zwischen Eingriffen, bei Netzverlust wartet er ohne Zählen. Was geholfen hat, merkt er
  sich 14 Tage je Sender und startet beim nächsten Mal gleich damit. Fallen 3 Sender eines Anbieters innerhalb von
  2 min aus, gilt der Anbieter als gestört (kein Durchprobieren aller Quellen). Schalter aus = bisherige Logik bis 3.14.
- Ab 3.15: Code-Prüfung bei jedem Build (Spotless/ktlint, detekt, Android-Lint ohne neue Warnungen,
  Architekturtests), Protokoll schwärzt Adressen und Zugangsdaten schon beim Schreiben, Textabrufe auf 8 MB begrenzt,
  Verbindungsprüfung mit wachsenden Abständen (15 s bis 5 min) statt Dauerversuchen bei Serverausfall.

- Ab 3.9: Hänger-Erkennung (ExoPlayer meldet 12 s kein neues Bild, obwohl er spielt → neu verbinden; nie bei
  Radiosendern), Bild-Cache wird bei wenig Speicher freigegeben, Adresse des aktiven Anbieters wird beim Start
  vorab aufgelöst. „Effekte reduzieren“ entlastet schwache Sticks.

- **Kein Datenverlust bei Anbieter-Störungen:** Liefert ein Bereich 0 Einträge oder weniger als 20 % des Bestands,
  bleibt die bisherige Liste erhalten (übernommen wird erst, wenn die nächste Aktualisierung dasselbe liefert).
- **EPG je Zugang und nur für sichtbare Länder** – bei großen Anbietern ein Bruchteil des Speichers und der Ladezeit.
  Älter als 24 Stunden wird es von selbst erneuert (Prüfung beim Start und alle 6 Stunden). Öffentliche
  Ersatzquellen werden höchstens einmal am Tag geladen, nach einem Versuch ohne Treffer eine Woche lang nicht.
- **Senderliste** lädt seitenweise; die Gruppierung (beste Variante je Sender) ist vorberechnet, „läuft gerade" wird
  gebündelt pro Seite abgefragt. Während einer Aktualisierung springen Sender-, Film- und Serienlisten nicht bei
  jedem Block, sondern laden einmal danach neu.
- **Suche** über einen Volltext-Index (FTS4): Wortanfänge, Groß/Klein und Umlaute egal; bei wenigen Treffern
  zusätzlich Suche im Titel („neo“ findet auch „ZDFneo“). Der Index wird in Blöcken aufgebaut.
- **Suche über alle Zugänge** (ab zwei Zugängen, umschaltbar auf „Nur aktiver Zugang“): Derselbe Titel aus
  mehreren Zugängen steht nebeneinander, die beste Qualität zuerst (bei Gleichstand der aktive Zugang); unter jedem
  Treffer steht sein Zugang. Ein Sender aus einem anderen Zugang zappt in dessen Senderliste.
- **Player:** wird beim Minimieren/Standby komplett freigegeben und danach automatisch fortgesetzt (auch wenn man
  schon während des Ladens wegschaltet); Neuversuche je Fehlerart (403/404 mit klarer Meldung), Warten auf Netz
  statt Aufgeben, Watchdog für hängendes Puffern, HLS ohne `.m3u8` in der Adresse, Header aus
  `#EXTVLCOPT`/`#EXTHTTP`/`url|User-Agent=…`. Der Puffer hat eine Obergrenze passend zum Arbeitsspeicher des Geräts.
- **TV-Guide:** höchstens vier gleichzeitige Anfragen an den Anbieter, Abbruch beim Wegscrollen.
- **Ton:** ExoPlayer gibt den Ton als PCM aus und dekodiert, wofür das Gerät keinen Decoder hat (MP2 bei ARD/ZDF,
  AC3/E-AC3/DTS am Handy), mit FFmpeg. Kein Umweg mehr über LibVLC und keine stummen Sender in der Vorschau.
  Durchreichen an AV-Receiver: *Einstellungen → Dolby/DTS durchreichen*. Hardware-Tunneling ist ab 3.3 aus.
- **Quellenwechsel:** Hat ein Sender mehrere Qualitäten, startet nach 3 s ohne Bild – oder wenn ein laufender Stream
  länger als 3 s puffert – die nächste. Welche Qualität beim Start funktioniert, merkt sich die App 3 Tage lang
  (auch über einen Neustart), danach wird die beste wieder probiert. Ladefehler werden intern bis zu 5× wiederholt.
- **Tonspur je Sender:** Die zuletzt gewählte Tonspur (z. B. Originalton) wird je Sender, Serie bzw. Film gemerkt.
- **Vorschau ↔ Vollbild:** Der Sender läuft beim Öffnen weiter und nach „Zurück“ in der Vorschau weiter.
  Die Senderliste steht dann auf dem zuletzt gesehenen Sender (auch nach dem Umschalten im Vollbild).

## TMDB

Diese App nutzt die TMDB-API, ist aber nicht von TMDB unterstützt oder zertifiziert. Für die Nutzung einen
kostenlosen API-Schlüssel auf themoviedb.org beantragen und in den Einstellungen eintragen.

## Sport

Spiele kommen immer online: ESPN (ohne Schlüssel), optional Bzzoiro Sports Data (empfohlen, kostenloser Schlüssel von
sports.bzzoiro.com – liefert die TV-Sender je Spiel für DE/AT/CH/TR) und Sportmonks (*Einstellungen → Sport*). Mit
Bzzoiro-Schlüssel werden die ESPN-Ligen, die es dort auch gibt, automatisch umgestellt (Bundesliga, 2. Liga, DFB-Pokal,
Champions/Europa/Conference League, Nations League, ab 3.12.1 auch Premier League, FA Cup, LaLiga, Serie A, Ligue 1,
Eredivisie, österreichische Bundesliga) und die Süper Lig dazugenommen (bei Bzzoiro „Trendyol Super Lig“ – bis 3.12 wurde
sie wegen des fehlenden „ü“ nicht erkannt). Bzzoiro nennt je Spiel 8–13 Einträge; ab 3.12.1 fallen Apps und Streaming
(Sky Go, WOW, RTL+, OneFootball, Mediatheken, …-News) und Sammelsender weg, die mehrere gleichzeitige Spiele zeigen
(„Sky Sport Top Event“, „Sky Sport Mix“ bei allen Pokalspielen eines Abends) – sonst landeten alle Spiele auf demselben
Sender. Gemessen am 28.10.2026 mit echtem Schlüssel: DFB-Pokal 16/16 und Bundesliga 9/9 Spiele mit Sender, Premier League
7/10, Süper Lig nur 1/9 (Bzzoiro hat kaum türkische Sender – dort hilft das Sport-Programm aus dem Internet).
Nachmessen: `BZZOIRO_KEY=… ./gradlew testDebugUnitTest --tests "*BzzoiroLiveTest"` (der Schlüssel bleibt in der Umgebung).
Welche Ligen erscheinen, legt man unter „Ligen wählen“ fest. Der Bereich ist da, solange
man mindestens einer Liga folgt.

**Sender automatisch finden (ab 3.12):** Die App sucht den Sender selbst, sicherste Quelle zuerst:
1. Event-Sender des Anbieters, deren Name das Spiel nennt („DAZN EVENT 3: Galatasaray – Fenerbahçe“, „PPV 2: UFC …“).
   Ab 3.12.1 fragt die App 15 min vor Anstoß die Event- und Sport-Gruppen jedes Xtream-Zugangs neu ab (nur diese Gruppen,
   höchstens 12, je Zugang höchstens alle 10 min): Benennt der Anbieter einen Event-Sender erst kurz vor dem Spiel um,
   wird das Spiel trotzdem noch vor Anstoß zugeordnet. Übernommen werden nur neue Namen und neue Sender – gelöscht wird
   dabei nie etwas. Läuft gerade eine volle Aktualisierung, entfällt die Abfrage. M3U-Listen haben keine Gruppen-Abfrage.
2. Kontrolle 15 min vor Anstoß beim Anbieter (Xtream get_short_epg): Läuft dort das Spiel? Sonst wird still korrigiert
3. öffentliches Sport-Programm der echten Sender (epgshare01, frei, ohne Schlüssel): Sendung am Anstoß nennt beide Teams
   bzw. Serie + Kämpfer/Grand Prix. Geladen werden einmal am Tag nur die Länder, aus denen der Zugang Sportsender hat
   (DE, AT, CH, TR, UK …), gefiltert auf Sport, im Cache. Wiederholungen und Zusammenfassungen zählen nicht.
4. EPG des Anbieters (beide Mannschaften im Titel, ±45 min)
5. Sendernamen aus ESPN/Bzzoiro
6. von Hand gewählter Ersatz je Liga (nur noch Korrektur)

Der Sender des Programms wird über tvg-id bzw. den bereinigten Namen im Zugang gefunden („Sky.Sport.Premier.League.de“ =
„DE| SKY SPORT PREMIER LEAGUE UHD“), nie aus einem anderen Land. Fehlt er im Zugang, steht beim Spiel „läuft auf … – nicht
in deinem Zugang“; aus einem anderen Zugang steht der Zugang dabei (gewechselt wird nie von selbst). Im Zweifel lieber kein
Sender als ein falscher. Das Programm reicht etwa 4–5 Tage voraus. Schalter: *Sicherheit & VPN → Sport-Programm aus dem Internet*.
**Gemessen am 31.10.2026** mit der aktuellen Schweiz-Datei CH1 (6,1 MB gepackt): gestreamtes Einlesen in 1.139 ms,
192 Sport-Sender und 4.268 Sendungen, zusätzlicher maximal lebender Heap 7,2 MB; Cache 0,2 MB und nach dem Lesen
1,5 MB Heap. Die Messung lief auf der Cloud-VM, nicht auf Android-Hardware. Nachmessen:
`GUIDE_FILE=/pfad/CH1.xml.gz ./gradlew testDebugUnitTest --tests "*GuideMemoryTest"`.

**Übersicht (ab 3.12.1):** „Jetzt live“, „Heute“, „Morgen“, „Diese Woche“ – sind mehrere Sportarten dabei, darin je
Sportart eine Unterüberschrift (Fußball, Kampfsport, Motorsport, US-Sport, Tennis, Weitere Sportarten; Reihenfolge wie in
„Ligen wählen“). Auch „Ohne Sender in deinem Zugang“ ist so unterteilt.

**Ligen und Events:** Fußball (Bundesliga, 2. Liga, DFB-Pokal, Premier League, FA Cup, LaLiga, Serie A, Ligue 1, Eredivisie,
Süper Lig, Türkiye Kupası, Champions/Europa/Conference League, Nations League, WM, EM, Qualifikation, Länderspiele
Deutschland/Türkei), UFC, Boxen, Formel 1, MotoGP, NFL, NBA, NHL, Tennis (Grand Slams, Finals), EuroLeague, Handball,
Darts, Olympia. Ohne Spielplan-Quelle (MotoGP, Boxen, Darts, Handball, EuroLeague, Olympia, Türkiye Kupası) kommen die
Events direkt aus dem Sport-Programm (als live gekennzeichnet bzw. erste Ausstrahlung einer Paarung). Abgerufen wird nur, solange die App sichtbar ist
(alle 30 min, bei laufenden Spielen alle 75 s nur deren Ligen), mit Wartezeit bei Fehlern.

## Bedienung

- **Navigation:** Oben eine Leiste im Stil von Apple TV. Links/Rechts blättert durch die Bereiche, kurz verweilen
  öffnet den Bereich, OK öffnet ihn sofort und springt in den Inhalt. Hoch aus dem Inhalt führt in die Leiste.
- **Zugang wechseln:** Oben rechts auf den Avatar – alle Zugänge bleiben gespeichert.
- **Verbindungen je Zugang:** Überall, wo Zugänge erscheinen (Avatar, Umschalter, Einstellungen), zeigt ein
  Abzeichen die belegten Verbindungen laut Anbieter, z. B. grün „1/1“, sobald jemand darüber schaut; grau = frei.
  Geht bei Xtream und bei M3U-Links im Xtream-Format; aktualisiert alle 30 s, solange die Anzeige sichtbar ist.
- **Länder & Sprachen:** Nach dem Einrichten eines Zugangs fragt GuevenPlayer, welche Länder angezeigt werden sollen
  (erkannt an Gruppennamen wie „DE | Sport“, „TR: Ulusal“, „|AR| News“). Gruppen anderer Länder werden ausgeblendet,
  auch neue nach einer Aktualisierung. Ändern unter *Einstellungen → Länder & Sprachen*.
- **Favoriten sortieren:** *Einstellungen → Favoriten sortieren* (Sender, Filme, Serien). Neue Favoriten kommen oben dazu.
- **Verlauf:** Im Live-Bild Rechts kurz = vorheriger Sender, Rechts gedrückt halten (oder Overlay → „Verlauf“) = Liste
  der zuletzt gesehenen Sender.
- **Einstellungen:** in Bereiche gegliedert – am Fernseher links die Bereiche, rechts der Inhalt; am Handy Chips oben.
- **Sport-Sender von Hand:** *Sport → Sender zuordnen* bzw. *Einstellungen → Sport*: Ligen ohne EPG-Treffer bekommen
  einen Sender, der für alle ihre Spiele gilt.
- **Kategorien sortieren:** *Einstellungen → Kategorien verwalten* → „Verschieben“: OK, dann hoch/runter, OK zum
  Ablegen (am Handy die Pfeile). Gilt für Live, Filme und Serien; „Standard-Reihenfolge“ stellt die des Anbieters her.
- **Favoriten:** OK gedrückt halten auf Sender, Film oder Serie. Auf einer Gruppe gedrückt halten nimmt die ganze
  Gruppe in die Favoriten auf (steht dann oben mit Stern, ihre Inhalte erscheinen unter „Favoriten“).
- **Aufnehmen:** Im Live-TV OK → „Aufnehmen“ → bis Sendungsende oder feste Dauer. Im TV-Guide OK auf einer Sendung
  gedrückt halten: laufende Sendung wird sofort aufgenommen, kommende Sendung eingeplant (1 Min. Vorlauf,
  2 Min. Nachlauf). Alle Aufnahmen unter „Aufnahmen“ in der oberen Leiste. Hinweis: Eine Aufnahme braucht eine
  eigene Verbindung zum Anbieter; geplante Aufnahmen starten nur, wenn das Gerät nicht komplett ausgeschaltet ist.

## Bedienung im Player

- **Live:** Hoch/Runter = Sender wechseln, Links = Senderliste mit EPG-Vorschau (Bild läuft weiter),
  Rechts bzw. „Letzter Sender“-Taste = Ping-Pong zum vorherigen Sender, Ziffern = Sendernummer,
  OK = Overlay (EPG, Favorit, Von Anfang an, Senderliste, Quelle, Ton, Untertitel, Player-Engine)
- **Quellen:** Sender in mehreren Qualitäten (z. B. ZDF UHD/FHD/HD/SD) sind eine Kachel. Gestartet wird die beste
  Variante, die das Gerät darstellen kann (4K nur mit 4K-Bildschirm und -Decoder); lädt sie nicht (Fehler der
  Quelle oder nach 3,5 s kein Bild), wechselt GuevenPlayer zur nächstbesten und merkt sie sich bis zum
  App-Neustart. Reine Netzwerkaussetzer schalten nicht um. Kann keine Variante abgespielt werden, übernimmt
  LibVLC. Manuell über den Overlay-Button „Quelle 1/3 · FHD“.
- **Filme, Serien, Catch-up:** Links/Rechts = −10/+10 s, OK = Overlay, Play/Pause-Taste
- **Serien:** „Intro überspringen“ unten rechts (OK), „Nächste Folge“ in den letzten 30 Sekunden
- **Stream-Info:** OK → „Stream-Info": VPN-Status, tatsächliche Auflösung, Codecs, Bitrate, Downloadrate,
  Verbindung (WLAN-Geschwindigkeit bzw. LAN), Puffer – jede Sekunde aktualisiert.
- **10 Min zurück (ab 3.10):** Bei Sendern mit Archiv im Overlay; jedes Drücken geht weitere 10 Minuten zurück,
  „Zurück zu Live“ beendet das.
- **Live-Ergebnis (ab 3.10):** Läuft auf dem Sender ein Spiel einer gefolgten Liga, steht der Spielstand oben neben der
  Uhr; bei einem Tor erscheint er 10 s lang auch ohne Overlay. Daten aus dem Bereich „Sport“ (nur im Speicher).
- **Bild im Bild (Fernseher, ab 3.10):** Overlay → „Bild im Bild“: aktueller Sender groß, zweiter klein unten rechts.
  Steuerkreuz wechselt zwischen beiden (Ton folgt), Menü-Taste schaltet auf das 2×2-Raster um.
- **Schneller Senderwechsel (ab 3.10):** Die Nachbarsender werden vorab aufgelöst (Adresse, DNS) – ohne zweite
  Verbindung zum Anbieter.
- **Zurück-Taste:** Overlay/Liste schließen → Senderliste bzw. Poster-Raster → Kategorien → obere Leiste →
  Live-TV → „Beenden?"
- **Bildwiederholrate:** Mit „Auto Frame Rate“ schaltet der Fernseher bei Filmen sofort (z. B. 24 Hz), im Live-TV
  erst nach 3 Sekunden auf einem Sender um – schnelles Zappen löst keinen HDMI-Wechsel aus.

## Neu in 3.10

- **Sprachsuche:** Mikrofon-Knopf neben dem Suchfeld (nutzt die Spracherkennung des Geräts, die App nimmt selbst nichts
  auf und braucht keine Mikrofon-Berechtigung).
- **Oft gesehen:** Live-TV-Liste nach Sehgewohnheiten je Tageszeit (zählt erst ab 2 Minuten auf einem Sender); liegt nur
  auf dem Gerät, wird mit dem Verlauf gelöscht, „Ohne Verlauf“ zählt nichts.
- **Automatisch aufnehmen:** Unter „Aufnahmen“ Stichwörter (Mannschaft, Serie) – passende Sendungen der nächsten 24 h aus
  dem EPG werden eingeplant (nach EPG-Abrufen und alle 6 h, solange die App läuft). Überschneidungen mit anderen
  Aufnahmen werden ausgelassen (viele Anbieter erlauben nur eine Verbindung). Gelöschte kommen nicht wieder.
- **Handy → TV weiterschauen:** noch nicht enthalten (braucht eine sichere Kopplung beider Geräte, eigener Schritt).

## Neu in 3.57 (Spiel-Finder: Pairing, ehrliche Quoten, nachvollziehbare Beweise)

- **Prüfgerät: Geheimnis statt Zifferncode.** Das Prüfgerät zeigt jetzt ein Geheimnis wie `X4K9-2M7P-0ABC-DEFG`
  (80 Bit Zufall, Zeichen ohne Verwechslungsgefahr – kein I, L, O, U; Groß-/Kleinschreibung und Gruppenstriche sind
  beim Eintippen egal). **Warum:** Der 8-stellige Code war am Gerät gegen Durchprobieren gesperrt, aber eine einzige
  mitgeschnittene Anfrage ließ sich **ohne** das Prüfgerät durchprobieren – dort greift keine Sperre. 10⁸ Codes sind
  auf einer Grafikkarte in einer Stunde erschöpft, und weil der Salzwert für alle Installationen derselbe war, hätte
  eine einmal berechnete Tabelle für jedes Gerät gegolten. Mit 80 Bit ist das keine Frage der Rechenzeit mehr.
  **Bestehende 8-stellige Codes funktionieren weiter**, bis man im Prüfgerät ein neues Geheimnis erzeugt.
- **Neues Geheimnis auf Knopfdruck:** Einstellungen → Sport → „Neues Geheimnis erzeugen“ (mit Rückfrage). Nötig, weil
  ein Geheimnis nur angelegt wird, wenn noch keines gespeichert ist – ein bestehendes Paar mit altem 8-stelligem Code
  käme sonst nie an das stärkere Verfahren. Das Prüfgerät stellt sich sofort darauf um, ohne App-Neustart; an jedem
  anderen Gerät muss das neue Geheimnis danach neu eingetragen werden.
- **Quoten mit Stichprobe:** Statt „Vorhersage 80 %“ steht jetzt „Vorhersage 80 % (4 von 5 bewiesenen Spielen)“ –
  4 von 5 bedeutet statistisch alles zwischen 38 % und 96 %, und das soll man sehen. Sortiert wird nach der
  vorsichtigen Untergrenze, damit „1 von 1“ nicht über „17 von 20“ landet.
- **Schwache Quellen erholen sich wieder:** Eine Quelle mit 0 von 5 wird übersprungen, aber jedes achte Spiel trotzdem
  gefragt. Vorher war das eine Dauersperre: ohne Anfragen keine neuen Daten, also auch nach einer Programmumstellung
  des Anbieters keine Rückkehr.
- **„Falscher Sender“ zählt:** Eine Korrektur wird als Fehlschlag der Quelle gewertet, die den Sender genannt hatte.
  Vorher entstanden die Quoten nur aus bewiesenen Spielen – aus Korrekturen lernte die App nichts.
- **Anstoßzeiten im Viertelstunden-Raster** und in der Zeitzone des Wettbewerbs: Ein TV-Pick von 20:30 auf 20:45 ist
  dasselbe Zeitfenster, und 15:00 am Samstag ist Premier-League-Zeit in London, nicht in Berlin.
- **Beweis nachvollziehbar:** „Letzte Prüfungen“ zeigt den gelesenen Text (z. B. `BAY 2:1 DOR · 67'`), auf dem ein
  Ergebnis beruht. Bleibt auf dem Gerät und wird nicht mit anderen Geräten geteilt.

## Neu in 3.56 (Spiel-Finder L5/L6: Selbstkalibrierung, Zeitfenster, Logo-Prüfung)

- **Trefferquote je Quelle:** Jedes bewiesene Spiel zählt, ob eine Quelle (TV-Programm, Sendeliste, EPG …) den richtigen
  Sender genannt hatte – getrennt nach Wettbewerb und Playlist. Ab 5 Spielen steht „Vorhersage NN %“ in Einstellungen →
  Sport und im Spiel-Dialog. Eine Quelle mit 0 von 5 zählt dort nicht mehr (bewiesene Sender bleiben immer).
- **Zeitfenster-Gedächtnis:** Wurde ein Sender im selben Zeitfenster (Wettbewerb, Wochentag, Anstoßzeit) mindestens
  zweimal bewiesen, wird er für die nächsten Spiele vorgeschlagen („laut Zeitfenster (bewährt)“, abschaltbar).
- **Logo-Prüfung:** Zeigt das Bild mehrmals das Logo einer anderen Senderfamilie (und nie das eigene), gilt der Sender
  für dieses Spiel als widerlegt.
- **Behoben:** Der Wecker für die Hintergrundsuche folgt dem Schalter „Dieses Gerät prüft im Hintergrund“ zuverlässig.
- **Prüfgerät sicherer:** Der 8-stellige Code wird nicht mehr übertragen; Anfragen und Antworten sind damit signiert
  (gilt nur einmal, 5 Minuten). Ein fremdes Gerät im WLAN, das sich als Prüfgerät ausgibt, bekommt keinen Code und kann
  keine Ergebnisse unterschieben; nach 20 Fehlversuchen in 10 Minuten lehnt das Prüfgerät alles ab. **Nach dem Update
  müssen Prüfgerät und Stick/Handy beide 3.56 haben.**
- **Review:** Vor Anpfiff speichert die Suche nur Beweise (Platzhalter vor dem Spiel verstecken den Sender nicht mehr);
  Texterkennung mit Frist; Zugangs-Abfrage vor dem Abspielen bricht nach 1,5 s wirklich ab; „Falscher Sender“ wird auch
  nach dem Schließen des Dialogs fertig; abgelaufene Beweise gelten nach Neustart nicht mehr; Sportmonks-Schlüssel nur an
  api.sportmonks.com.

## Neu in 3.55 (Spiel-Finder L4: Suche, Mosaik, Hintergrund, Prüfgerät)

- **Suche über die Sport-Gruppen:** Findet die Prüfung bei den Kandidaten nichts, sieht sie Sport-Sender der Familien mit
  Rechten an (bis 10 je Spiel) – so wird ein Spiel auch auf Sendern gefunden, die keine Liste nennt.
- **„Sender suchen“:** OK auf einem Spiel ohne Sender (und im Spiel-Dialog) öffnet ein Live-Mosaik der wahrscheinlichsten
  Sender; deine Wahl ist der Beweis.
- **Hinweis:** „Spiel gefunden: … auf … (geprüft)“; eine Erinnerung an das Spiel zeigt danach auf den bewiesenen Sender.
- **Im Hintergrund:** Einstellungen → Sport → „Dieses Gerät prüft im Hintergrund“ (nur auf einem Gerät, z. B. Fire TV
  Cube). Ein Wecker 2 Minuten vor Anpfiff startet die Suche für höchstens 15 Minuten.
- **Prüfgerät im Heimnetz:** Das Prüfgerät zeigt einen 8-stelligen Code; auf Stick/Handy unter „Code des Prüfgeräts“
  eintragen. Sie übernehmen dann seine Beweise (nur Spiel, Sender, Ergebnis) und melden beim Losschauen „Ich brauche
  diese Playlist“ – das Prüfgerät gibt sie sofort frei (Regel „Freie Playlist“).

## Neu in 3.54 (Spiel-Finder L2 + L3: Senderechte und Bildbeweis)

- **Rechte-Tabelle 2026/27** (DE, AT, TR; Quellen im Code): Sender, deren Familie den Wettbewerb laut Tabelle nicht
  überträgt, stehen als „widerspricht den Senderechten“ da (kein harter Filter – Rechte sind oft geteilt).
- **Bildbeweis:** Bei laufenden Spielen sieht die App über **freie Playlists** (laut Anbieter frei, nicht von diesem
  Gerät genutzt, kurz davor nochmals gefragt) bis zu 3 Kandidaten je Minute stumm und unsichtbar an. Texterkennung auf dem
  Gerät (ML Kit): Spielstand-Einblendung passt zum Live-Stand → „● bewiesen“; Standbild oder „Kein Event“ → nie
  angezeigt; andere Partie oder falsche Spielminute (Wiederholung) → verworfen. Event-Kanäle werden so geprüft.
- **Beweis gilt für alle Playlists** mit demselben Sender (außer Event-Kanälen).
- **Lernen von dir:** 5 Minuten Zuschauen auf einem Kandidaten = bewiesen; „Falscher Sender“ im Spiel-Dialog sperrt den
  Sender für den Wettbewerb.
- Einstellungen → Sport → Quellen: „Letzte Prüfungen“ mit Ergebnis.

## Neu in 3.53 (Spiel-Finder L1: ehrliche Sender-Anzeige)

- **Event-Sender nur noch Kandidaten:** „PRIME DE 016 [Alkmaar vs. Juventus]“ zeigte am 05.11. nur „Kein Event“. Event-
  Sender des Anbieters werden ohne Beweis nicht mehr angezeigt (der Bildbeweis folgt in L3).
- **„Kein Event“ / „No Event“** im Programm des Anbieters widerlegt einen Sender bei der Kontrolle vor Anstoß.
- **Herkunft sichtbar:** Jedes Spiel nennt seine Quelle („laut TV-Programm“, „laut Sendeliste“, „laut EPG des
  Anbieters“, „vom Anbieter bestätigt“, „von dir zugeordnet“ …); der Spiel-Dialog erklärt sie.
- **Quellen abschaltbar** (Einstellungen → Sport → Quellen der Sender), mit Anzahl der Spiele je Quelle.
- **Länder** (Einstellungen → Sport → Länder): Senderlisten von Bzzoiro für die markierten Länder.
- **Voreinstellung der Wettbewerbe:** Premier League, LaLiga, Bundesliga, Serie A, Ligue 1, Süper Lig, Champions League,
  Europa League, WM, EM, UFC, Boxen. Wer selbst gewählt hat, behält seine Auswahl.

## Neu in 3.52 (App-Updates aus dem privaten Repo)

- Jeder Build mit Release-Schlüssel legt die APK als Release „build-<versionCode>“ im privaten Repo ab (die letzten 5
  bleiben). Der Code bleibt privat.
- *Einstellungen → System → App-Updates*: GitHub-Zugriffsschlüssel eintragen (Fine-grained token, nur Repo „Player“,
  „Contents: Read-only“). Verschlüsselt gespeichert, nie im Backup, geht nur an api.github.com.
- Beim Start (höchstens alle 6 h) fragt die App bei einer neueren Version einmal nach; „Installieren“ lädt die APK und
  öffnet den Android-Installer. Einmal bestätigen musst du immer (Android-Regel). Beim ersten Mal fragt Android, ob
  GuevenPlayer Apps installieren darf.
- Android nimmt das Update nur an, wenn es mit demselben Schlüssel signiert ist – eine fremde APK wird abgelehnt.

## Neu in 3.51 (Hänger: unsichtbar, kurz, nahtlos)

- **Live-Puffer** (*Einstellungen → Wiedergabe → Player*, Aus / 4 s / 8 s, Standard 4 s): Der Player baut nach dem Start
  einen Vorrat vor dem Live-Rand auf, indem er kaum hörbar 5 % langsamer spielt (Tonhöhe bleibt), bis der Vorrat steht
  – bei 4 s etwa 80 s lang. Kurze Aussetzer des Anbieters zehren nur am Vorrat und werden nicht sichtbar. Preis: Das
  Bild läuft so viele Sekunden hinter Live. HLS-Live hielt schon vorher Abstand; dort gilt der Wert als Mindestabstand.
- **Hänger höchstens 3 s:** Live-Sender mit Ersatz-Variante wechseln ohne Datennachschub nach 2 s, sonst spätestens nach
  2,5 s – auch wenn noch Daten kommen, bei LibVLC/System-Player und bei stehender Position. Geprüft wird bei solchen
  Sendern immer im 500-ms-Takt (vorher bei ruhiger Wiedergabe 1 s), damit die 3 s auch eingehalten werden. Nach einem
  Wechsel wartet der Wächter 3 s statt 10 s, bevor er erneut eingreift. Filme/Serien und Sender ohne Ersatz unverändert.
- **Kein Schwarzbild beim Wechsel:** Das letzte Bild bleibt stehen, bis die neue Variante läuft.
- **Ersatz auf Vorrat:** Beginnt ein Hänger und hat der Zugang der nächsten Variante nachweislich einen freien Platz,
  lädt eine zweite Engine diese Variante still vor (ohne Ton, ohne Abspielen). Der Wechsel übernimmt sie samt Puffer.
  Fängt sich der Sender, wird sie sofort freigegeben. Nicht auf schwachen Geräten (Speicher) und nie ohne freien Platz
  (sonst „Zugang belegt“).
- Gedächtnis des Wächters wird beim App-Start im Hintergrund geladen (nicht mehr beim ersten Öffnen des Players).
- **Obere Leiste passt sich an:** Keine festen Breiten mehr. VPN, Uhr und Zugang bekommen immer ihren Platz – auch bei
  großer Schrift/Anzeigegröße des Fernsehers (vorher brach die Uhr um und das Zugangs-Symbol verschwand). Die Bereiche
  bleiben mittig, solange Platz ist, sonst rücken sie zwischen Logo und rechte Ecke.

## Neu in 3.50 (Wächter merkt sich schlechte Quellen)

- **Sofort gemerkt:** Jeder Hänger einer Sender-Variante bzw. Quelle – schon ab 1 Sekunde –, jeder langsame Start
  (über 4 s), jeder gescheiterte Start und jeder Fehler gibt Strafpunkte. Gespeichert sofort und über Neustarts hinweg.
- **Gute zuerst:** Beim nächsten Aufruf des Senders startet die Variante mit den wenigsten Problemen; die Qualität
  entscheidet erst danach (eine störungsfreie HD-Variante vor einer hängenden FHD-Variante). Gilt auch für Filme und
  Folgen aus „Alle Zugänge“.
- **Erholt sich:** Punkte verfallen (Halbwertszeit 12 h) und halbieren sich nach 2 Minuten störungsfreier Wiedergabe.
- **Nicht gezählt:** ohne Internet (auch wenn es während des Hängers zurückkommt), „Zugang belegt“/Drosselung (liegt am
  Zugang), Decoder-Fehler (liegt an Player/Gerät), Spulen bei Filmen, Wechsel von Hand bei laufendem Bild, Zappen während
  des Starts, Start unterbrochen durch Home-Taste oder Player-Wechsel. 4K/8K bleibt auf 1080p-Geräten trotzdem hinten.
- Engines ohne Angabe der geladenen Datenmenge (Nachschub nicht messbar): Wechsel weiter erst nach 6 s.
- **Schneller wechseln:** Hängt ein Live-Sender mit Ersatzquelle und kommen keine Daten mehr, wechselt der Wächter
  nach 2 s (vorher 6 s). Kommen noch Daten, wartet er weiter (sonst würde er Quellen wechseln, die sich selbst fangen).

## Neu in 3.49 (Sport: Sender-Diagnose)

- *Einstellungen → Sport → Sender von Bzzoiro* zeigt nach jedem Abruf, was ankommt, z. B.
  „Bzzoiro-Senderliste: 9 Spiele mit Sender, 2 zugeordnet“ und „Nicht gefunden: ‚beIN SPORTS 1 Türkiye‘, ‚beIN SPORTS‘“
  – die Namen genau so, wie Bzzoiro sie liefert und wie sie in der eigenen Senderliste fehlen. OK öffnet „Sender korrigieren“.
- Fehler nennen den HTTP-Code („Bzzoiro-Senderliste TR nicht abrufbar: Schlüssel ungültig oder Liga nicht im Tarif (HTTP 403)“);
  eine Antwort ohne lesbaren Sender steht mit Feldnamen ebenfalls dort, nicht nur im Protokoll.
- **Land zählt:** „beIN SPORTS 1“ gibt es in der Türkei, in Frankreich, in Nahost … Bzzoiro-Sender tragen jetzt das
  abgefragte Land („TR| beIN SPORTS 1“, angezeigt als „beIN SPORTS 1 (TR)“). Zugeordnet wird nur ein Kanal desselben
  Landes oder einer ohne Länderangabe – nie „|AR| BEIN SPORTS 1“ oder „|FR| …“. Hat der Zugang nur fremde Länder,
  bleibt das Spiel lieber ohne Sender. Auch die Senderfamilie (1, 2, 3 …) bleibt im Land des gewählten Ersatzsenders.
- Verglichen wird weiterhin über einen internen Schlüssel (klein, ohne Leer- und Sonderzeichen, ohne Länder-Vorsatz und
  FHD/HD/UHD/4K: „|TR| BEIN SPORTS 1 FHD“ = „beIN SPORTS 1“ = `beinsports1`) – angezeigt wird er nie.

## Neu in 3.48 (Sport: Bzzoiro-Senderliste kommt an)

- **Gemeldet:** Bzzoiro zeigt auf der Webseite je Spiel den Sender (z. B. Galatasaray – Amedspor: „beIN SPORTS 1“),
  die App übernahm ihn nicht. Die Namen passen (Schlüssel „beinsports1“ auf beiden Seiten) – verloren gingen die
  Daten beim Abruf: Die App las nur das Feld „channel_name“ und verschluckte jeden Fehler der Senderliste still.
- **Jetzt:** Die Senderliste wird tolerant gelesen („channel_name“, „channel“ als Objekt oder Text, „event_id“ oder
  „event“). Fehler der Senderliste erscheinen unter *Einstellungen → Sport* (z. B. „Senderliste TR: Schlüssel ungültig
  oder Liga nicht im Tarif“); kommt eine Antwort ohne lesbaren Sender, stehen die Feldnamen im Protokoll.
- Senderliste wird stündlich neu geholt (vorher alle 3 h); „beIN Connect“ u. ä. Apps zählen nicht als Sender.
- Ersatzsender ohne Nummer im Namen (z. B. „Sky Sport Bundesliga“) gilt wieder für alle offenen Spiele der Liga
  (gekennzeichnet „vermutlich“); mit Nummer wird weiter auf 1, 2, 3 … verteilt.

## Neu in 3.47 (Sport: echte Sender je Spiel, Süper Lig nur einmal)

- **Süper Lig zusammengeführt:** „Süper Lig“ (ESPN) und „Trendyol Super Lig (Turkey)“ (Bzzoiro) sind dieselbe Liga.
  Mit Bzzoiro-Schlüssel ersetzt Bzzoiro jetzt auch die ESPN-Süper-Lig (wie Bundesliga & Co.); wer beide schon folgte,
  wird beim nächsten Abruf automatisch zusammengeführt – ein von Hand gewählter Ersatzsender wandert mit.
- **Echte Sender je Spiel:** Bzzoiro liefert für jedes Spiel die TV-Sender (DE, AT, CH, TR). Diese Namen treffen jetzt
  auch die Kanäle des Anbieters, wenn ein Länderzusatz abweicht („beIN SPORTS 2 Türkiye“ = „|TR| BEIN SPORTS 2 FHD“);
  die Sendernummer muss stimmen. Reihenfolge der Quellen unverändert: Sendeliste/Programm/EPG vor Ersatzsender.
- **Ehrlich gekennzeichnet:** Kommt der Sender nur vom Ersatzsender der Liga (bzw. dessen Reihe 1, 2, 3 …), steht
  „(vermutlich)“ dahinter, im Spiel-Dialog mit Erklärung.

## Neu in 3.46 (Sport: gleichzeitige Spiele auf mehreren Sendern)

- **Gemeldet:** Mit einem von Hand gewählten Ersatzsender (z. B. „|TR| BEIN SPORTS 1 FHD“ für die Süper Lig) bekamen
  alle Spiele der Liga denselben Sender – auch gleichzeitige, die in Wirklichkeit auf beIN SPORTS 2, 3 … laufen.
- **Jetzt:** Der Ersatzsender steht für seine ganze Senderreihe im selben Zugang (gleicher Name, andere Nummer: 1–10).
  Spiele ohne sichere Zuordnung bekommen der Reihe nach den ersten Sender, der zu ihrer Anstoßzeit frei ist
  (Belegung 2 h 15 min); Sender, die über Online-Sendeliste, Programm oder EPG schon sicher für ein anderes Spiel
  feststehen, gelten in dessen Zeit als belegt. Reicht die Reihe nicht, bekommt ein Spiel lieber keinen Sender als
  einen falschen. Echte Daten (Bzzoiro-Sendeliste, Sport-Programm, EPG des Senders) haben weiterhin Vorrang.

## Neu in 3.45 („Zugang belegt“ durch das eigene Gerät)

- **Gemeldet:** „Zugang belegt – über ‚Alternative‘ schaut gerade jemand (1/1)“, obwohl nur dieses Gerät lief.
  Ursache: Die Live-Vorschau (ebenso Mosaik und „Läuft gerade“) belegte die eine Verbindung des Zugangs, meldete sie
  aber nicht als eigene Nutzung – nur der Vollbild-Player tat das. Die Prüfung vor dem Start hielt die eigene
  Vorschau deshalb für ein fremdes Gerät.
- **Jetzt:** Vorschau, Mosaik, „Läuft gerade“ und Player vermerken ihre Verbindung als eigene Nutzung – beim Start,
  laufend (Player alle 30 s, Vorschau jede Sekunde) und beim Beenden. Die Schonfrist danach beträgt 3 min statt 90 s
  (Anbieter zählen beendete Verbindungen noch eine Weile mit).
- **Umschalten:** ExoPlayer schließt die alte Verbindung, bevor er den neuen Sender anfordert.
- **Ehrliche Meldung:** Lehnt der Anbieter trotzdem ab und war es dieses Gerät, heißt es „Der Anbieter hat deine
  vorige Verbindung noch nicht freigegeben …“ statt „auf einem anderen Gerät beenden“.
- Grenze: Schaut innerhalb der Schonfrist tatsächlich ein anderes Gerät über den Zugang, fragt die App nicht nach.

## Neu in 3.44 (Ordnung in „Alle Zugänge“ und in den Gruppen)

- **Einheitliche Gruppennamen in „Alle Zugänge“:** immer „Land · Rubrik“ (z. B. „Deutschland · Sport“,
  „Deutschland · Neuheiten“, „Deutschland · Science-Fiction“). Gruppen ohne erkennbare Rubrik folgen demselben Format:
  Länderkürzel und Zierzeichen der Anbieter fallen weg, GROSSBUCHSTABEN werden normal geschrieben
  („|DE| ✪ FILME 2023“ → „Deutschland · Filme 2023“); Abkürzungen bis drei Zeichen (TV, UHD, VIP) bleiben.
- **Feste Gruppenreihenfolge:** Länder in der Reihenfolge des wichtigsten Zugangs, darin Neuheiten → Vollprogramm →
  Unterhaltung → Nachrichten → Sport → Kinder → Familie → Doku … → Genres → Streamingdienste → 4K; Gruppen ohne
  Rubrik danach alphabetisch, Erwachsene immer am Ende. Eigene Reihenfolgen aus „Kategorien verwalten“ gelten weiter.
- **Farbpunkte vor Gruppennamen entfernt** (3.42) – sie erschienen nur bei manchen Gruppen und wirkten unruhig.
- **Reihenfolge in Gruppen** (*Einstellungen → Inhalte*): Automatisch (Standard: „Alle Zugänge“ A–Z, einzelne
  Zugänge wie beim Anbieter) · Wie beim Anbieter · A–Z. Gilt für Sender, Filme und Serien. Sender werden dabei wie
  erwartet sortiert: „DE:“, „|DE|“, HD/UHD/4K zählen nicht, „Sky Sport 2“ vor „Sky Sport 10“. Umschalten auf/zu
  Senderzapping folgt derselben Reihenfolge.
- Nach dem Update baut die App „Alle Zugänge“ einmal neu auf (neue Regelversion), damit die neuen Namen gelten.

## Neu in 3.43 (lange Namen lesbar)

- **Gemeldet:** Gruppen- und Titelnamen wie „|DE| ✪ NEUE FILME …“ waren abgeschnitten und nicht lesbar.
- **Gruppenlisten** (Live, Filme, Serien) zeigen Namen jetzt zweizeilig.
- **Laufschrift wie bei Apple TV** (`MarqueeText`): Einzeilige Namen enden ohne Fokus mit „…“; hat die Zeile oder
  Kachel den Fokus, läuft der ganze Text nach kurzer Pause durch. Gilt app-weit (43 Stellen): Sender, Poster- und
  Kartentitel, „Läuft gerade“, Mosaik, Kontextmenü, TV-Guide, Suche, Aufnahmen, Episoden, Zugänge, Länderauswahl,
  Sport, Player-Menüs. Kurze Texte bewegen sich nie.

## Neu in 3.42 (Bedienkomfort)

- **Leere Zustände mit Ausweg:** Leere Favoriten/Sehgewohnheiten (Live) und leere Favoriten/Weiterschauen (Filme,
  Serien) zeigen einen Knopf „Alle Sender zeigen“ bzw. „Alle anzeigen“ statt einer Sackgasse.
- ~~Farbige Gruppen-Akzente~~ – in 3.44 wieder entfernt.
- **Zuletzt gesucht:** Bei leerem Suchfeld stehen die letzten 8 Begriffe als Chips (nur auf dem Gerät gespeichert),
  dazu „Verlauf löschen“. Ein Begriff gilt als gesucht, wenn das Tippen 2 s ruht.
- **TV-Guide lädt mit Gerüst:** Während die Sender laden, steht ein pulsierendes Raster statt „Keine Sender“.
- **Ladekarten** der Startseite in den Maßen des Gestaltungssystems (2:3).

## Neu in 3.41 (Oberfläche: Gestaltungssystem, Liquid Glass, Kontextmenü, „Läuft gerade“)

- **Gestaltungssystem** (`ui/theme/DesignSystem.kt`): feste Stufen für Abstände (4-dp-Raster), fünf Schriftrollen
  (`AppText`), eine Kartengröße je Inhaltsart (Poster 2:3, Sender 16:9, fließende 12-dp-Ecken) und Federbewegungen
  (`AppMotion`). Startseite, Bibliothek, Mosaik, Kontextmenü und Leiste nutzen es; ältere Bildschirme folgen schrittweise.
- **Liquid-Glass-Leiste:** Die obere Leiste ist ein schwebendes Glas-Element mit Lichtrand und Schatten. Liegt der
  Fokus im Inhalt, tritt sie zurück (Schriftzug blendet aus, die anderen Bereiche werden gedimmt, der gewählte bleibt
  hell); „Hoch“ holt sie mit einer Feder wieder nach vorn. Mit „Effekte reduzieren“ ohne Rand und Schatten.
- **Einheitliches Kontextmenü:** OK gedrückt halten öffnet überall dasselbe Menü statt einer versteckten
  Sofort-Aktion – Senderliste (Abspielen, Favorit, Mosaik ab hier), Filme/Serien (Öffnen, Favorit), Gruppen (Öffnen,
  Oben anheften), TV-Guide (Ansehen, Aufnehmen/Aufnahme entfernen), Startseite (Abspielen, Details, Meine Liste).
- **„Läuft gerade“** (Startseite am Fernseher, direkt unter dem großen Titel): Favoriten-Sender (ohne Favoriten die
  ersten der Liste) mit echten, aktuellen Standbildern, Sendung und Fortschritt. Nutzt den Abtaster des Mosaiks:
  eine Verbindung, stumm, Bilder alle 2 min, höchstens 5 Runden je Besuch; scrollt die Zeile aus dem Bild, endet die
  Verbindung. Am Handy bleibt die Logo-Zeile (Datenvolumen).

## Neu in 3.40 (Ambient-Licht und Live-Mosaik)

- **Ambient-Licht:** Läuft in der Senderliste die Vorschau, färben sich die beiden Farbflächen des App-Hintergrunds
  langsam in die Farben des Bildes (links oben = linke Bildhälfte, rechts unten = rechte), ähnlich wie Ambilight.
  Gemessen wird alle 1,2 s an einem 32×18-Bild (`PixelCopy`, GPU-skaliert); schwarze Balken und dunkle Szenen zählen
  nicht, grelle Farben werden gezähmt, damit Schrift lesbar bleibt. Aus mit „Effekte reduzieren“ (auf schwachen Geräten
  automatisch). DRM-geschützte oder getunnelte Streams liefern kein Bild – dann bleiben die Akzentfarben.
- **Live-Mosaik** (*Senderliste → Mosaik*): 2×2 oder 3×3 Sender der Gruppe ab dem markierten Sender als Standbilder,
  alle 30 s neu. Ein einziger, stummer Abtaster öffnet die Sender nacheinander in ihrer Kachel und sichert das Bild –
  das Mosaik belegt nie mehr als eine Verbindung beim Anbieter und dekodiert nie mehr als ein Video. Zwischen den
  Runden ist die Verbindung geschlossen. OK öffnet den Sender im Vollbild; schwache Geräte starten mit 2×2.
- **Bewegte Kategorie-Kacheln:** bewusst nicht umgesetzt – Trailer gibt es nur als YouTube-Schlüssel (TMDB/Xtream),
  die sich ohne WebView oder Umgehung der YouTube-Nutzungsbedingungen nicht stumm in einer Kachel abspielen lassen.

## Neu in 3.39 (Dachmarke + 4K ist ein eigener Sender)

- **Gemeldet:** „TRT 4K ist ein eingestellter Sender, er hat nichts mit TRT 1 oder TRT 2 zu tun.“ Ursache: Aus „TRT 4K“ bzw.
  „TRT UHD“ wurde nach dem Entfernen der Qualität „TRT“, und das Wörterbuch macht aus „TRT“ den Sender TRT 1 – so landete
  der UHD-Sender als Qualität bei TRT 1.
- **Allgemeine Regel** (`MatchKeys.channelName(groupKey, name)`): Entsteht ein nummerierter Sender nur durch Entfernen von
  4K/UHD/8K **und** ein Synonym, das eine Nummer anhängt, ist es ein eigener UHD-Sender (gilt ebenso für Eurosport, ITV …).
  Echte Qualitätsvarianten („TRT 1 UHD“, „ZDF UHD“, „PROSIEBEN UHD“) bleiben Quellen ihres Senders.
- Echte Liste: „|4K| TRT UHD“ ist ein eigener Eintrag; sonst keine Änderung (18.783 Einträge, 121 gemischte, alle geprüft).

## Neu in 3.38 (Umschalten geht immer zum nächsten Sender)

- **Gemeldet:** Im Vollbild schaltete „runter“ nicht zum nächsten Sender, sondern zur nächsten Qualität desselben Senders.
- **„Zuletzt gesehen“ und „Oft gesehen“ je Sender einmal.** Bisher stand jede gesehene Qualität einzeln (ProSieben HD,
  UHD, 4K) – Umschalten in diesen Listen lief durch die Qualitäten. Jetzt gruppiert nach Sender (zuletzt gesehene Quelle).
- **Jede Senderliste nutzt die Zuordnung** (`VariantUnifier` nach jedem Import): Quellen desselben Senders mit leicht
  anderem Namen, aber passender EPG-ID („SKY SPORT F1 4K“/„SKY SPORTS F1 HD“) werden ein Eintrag mit mehreren Quellen.
  An der echten Liste: 19.008 → 18.781 Listeneinträge (227 doppelte weg), keine neuen Fehlzuordnungen.
- **Umschalten findet den laufenden Sender** auch, wenn gerade eine andere Quelle läuft (vorher Sprung an den Listenanfang).
- Regel-Fehler behoben: Zweibuchstabige Kürzel bildeten zusammen neutrale Wörter („pl“ + „us“ = „plus“) – dadurch galten
  „Florida“ und „Florida Plus“, „Tastemade“ und „Tastemade+“ als gleich.
- Bewusst nicht: unscharfer Namensabgleich ohne EPG-ID – im Massentest 102 statt 11 Fehlzuordnungen („Channel 4“ = „Channel S“).
- Nach dem Update werden alle Senderlisten und „Alle Zugänge“ beim Start neu zusammengefasst (`MATCHER_VERSION` 338).

## Neu in 3.37 (eine Zuordnung für die ganze App, nachvollziehbare Quellen)

- **Hauptfehler behoben – eindeutige IDs:** Die Zuordnung trennte Sender richtig, vergab aber getrennten Gruppen
  mit gemeinsamem Vergleichsschlüssel dieselbe ID (kleinster Schlüssel, z. B. „e:trt1.tr“ für TRT 1 und das falsch
  beschriftete TRT 2). In der Datenbank lagen sie dann wieder zusammen. Jetzt bekommt jede Gruppe einen Schlüssel, der nur
  ihr gehört (`collect` in `Clusterer.kt`); geprüft bis in die Quellen-Liste (`SiblingChannelsTest`). Hinweis: Die bis 3.36
  gemeldeten Zahlen „nicht zusammengelegt“ waren dadurch zu niedrig (Massentest jetzt 5,7 % statt 4,0 %).
- **Eine einzige Zuordnung systemweit.** Bisher ordnete „Alle Zugänge“ anders zu als die Quellen aus anderen Zugängen,
  „Weiter über anderen Zugang“, übergreifende Favoriten und Fortschritt (`CounterpartFinder`, nur gleiche EPG-ID).
  Jetzt gilt überall die Zuordnung von „Alle Zugänge“; ohne sie dieselben Namensregeln (`NameRules`) – eine EPG-ID allein
  verbindet nie. Gemeldet: TRT 1 stand als Quelle bei TRT 2 (Anbieter gab TRT 2 die EPG-ID von TRT 1).
- **Neuaufbau nach Regeländerung:** Ändern sich die Zuordnungsregeln mit einem Update, wird „Alle Zugänge“ beim nächsten
  Start neu aufgebaut (`CombinedBuilder.MATCHER_VERSION`) – vorher blieb die alte Zuordnung bis zur nächsten Aktualisierung.
- **Quellen-Liste (Mehr → Quelle):** Originalname des Anbieters, Qualität mit Erklärung und Zugang, z. B.
  „Quelle 1 · DE - PROSIEBEN UHD · Ultra HD 4K (2160p) · Zugang A · beste“; Filme/Folgen ebenso.
- PRO7SAT.1 UHD bleibt ein eigener Sender (ProSiebenSat.1 UHD, nur zeitweise Sendungen) – kein Teil von ProSieben.

## Neu in 3.36 (Zuordnung an echten Anbieterdaten geprüft, Stabilität aus dem Geräteprotokoll)

- **Prüfung an einer echten Anbieterliste** (21.822 Sender, 360 Gruppen; nur Namen, Gruppen, EPG-IDs):
  - Einträge mit verschieden benannten Quellen: 248 → 125; die verbleibenden sind Schreibvarianten (TÉVA/TEVA,
    13ÈME RUE/13 EME RUE) oder Umbenennungen laut Katalog (Chérie 25 = RMC Life).
  - Behoben u. a.: beIN Sports 9 ≠ XTRA 3, Télétoon+ ≠ Télétoon +1, Ziggo Sport Select ≠ Voetbal, M. LaLiga ≠ LaLiga
    Hypermotion, HBO/Showtime East ≠ West, Discovery ≠ Discovery ID, TVM 1 ≠ TVM Sport+, Power TV ≠ Power Türk.
  - Werkzeug: `RealListAnalysisTest` (`-PrealNames=datei.tsv`), feste Fälle in `RealProviderCasesTest`.
- **Namensregeln** (`NameRules`): gleicher Stamm + bedeutsamer Unterschied vorn/Mitte/hinten = verschiedene Sender
  (ProSieben/MAXX); neutral sind Qualität, „TV“, Länder, Regionen, Tippfehler mit einem Zeichen, vertauschte Wörter.
  Die reine Ähnlichkeit (Bigramme ≥ 0,7, hielt „prosieben“ ≈ „prosiebenmaxx“) ist ersetzt; Schwelle für EPG-Verbindungen 0,8.
- **EPG-ID allein ist kein Beweis mehr:** Eine aus der EPG-ID abgeleitete Katalog-ID hebelt die Namensregeln nicht aus;
  nur eine aus dem Namen erkannte Katalog-ID darf das (Umbenennungen).
- **Reihenfolgeunabhängig:** Gleiche Schlüssel werden gegen jede bestehende Gruppe geprüft, nicht nur gegen den ersten Treffer.
- **Länderpräfixe bis 7 Zeichen** („LATAM:“, „BALKAN:“, „NORDIC:“) werden erkannt.
- **Selbstkontrolle im Protokoll:** „… Sender aus mehreren Zugängen (… durch das laufende Programm bestätigt)“.
- **Aktualisierung:** Reißt die Verbindung ab, wird der Zugang nach 20 s und 60 s erneut versucht (nicht bei abgelehnter Anmeldung).
- **Sport:** Zuordnung der Spiele auch nach jedem Neuaufbau von „Alle Zugänge“; Protokollzeile nennt neu zugeordnete Spiele.
- **VPN:** Reißt die Verbindung ≥ 4× in 30 min nach < 5 min ab, erklärt das Protokoll den wahrscheinlichen Grund (gleicher
  Schlüssel auf zwei Geräten) und verbindet mit 5 min Abstand neu statt sofort. Wiederherstellen einer Sicherung weist darauf hin.
- Nicht gebaut: eigene Tabelle für EPG-Anzeigenamen – EPG-ID-Verbindungen verlangen jetzt passende Namen, der Anzeigename
  fing in der Messung keinen weiteren Fall ab. Start-Zugriffe auf dem Hauptthread (StrictMode) und Ladezeiten: eigener Schritt.

## Neu in 3.35 (Zuordnung: Internetabgleich und Massentest)

- **Fehler aus 3.34 behoben:** TRT 1 und TRT 2 (und ähnliche) lagen zusammen. Ursache: „TRT 4K“ wurde beim Einlesen des
  Katalogs zu „TRT“ = „TRT 1“, damit galt „TRT 1“ als mehrdeutig und fiel aus dem Katalog; eine falsche EPG-ID reichte dann.
  Jetzt hat der echte Name Vorrang vor abgeleiteten Formen.
- **Zahlen im Namen trennen immer:** TRT 1/2, Sky Sport 1/2, RTL/RTL 2, 3sat/Sat.1, Channel 4/Channel 4 +1 – auch ohne
  Katalog und gegen jede EPG-ID.
- **EPG-ID allein verbindet nicht mehr:** Nur wenn die Namen ähnlich sind (oder der Katalog es bestätigt). Eine EPG-ID macht
  einen Sender nur dann zu einem Katalog-Sender, wenn der Name dazu passt („ARD Das Erste“ ↔ DasErste.de ja, „Universal TV“
  mit der EPG-ID von 7Bravo nein).
- **Internetabgleich:** Der Senderkatalog wird wöchentlich (nachts im WLAN, vor einem Aufbau, sofort bei „Neu zuordnen“)
  von `https://iptv-org.github.io/api/channels.json` geladen (~1 MB komprimiert), Feld für Feld gelesen, geprüft
  (≥ 20.000 Sender, bekannte Sender vorhanden) und erst dann ersetzt. Gesendet wird nichts außer der Anfrage.
- **Massentest** `CatalogBenchmarkTest`: alle ~24.000 Katalogsender je drei Anbieter (Präfix, HD/FHD, Zweitnamen, jeder
  sechste mit falscher EPG-ID). Falsch zusammengelegt: mit Katalog 111 → 8, ohne Katalog 3.060 → 13.

## Neu in 3.34 (vollautomatische Zuordnung in „Alle Zugänge“)

- **Keine Prüfliste mehr.** „Zuordnung prüfen“ ist entfernt – die App entscheidet selbst, im Zweifel getrennt.
- **Senderkatalog:** rund 30.000 Sender mit Land, Namen und Zweitnamen aus
  [iptv-org/database](https://github.com/iptv-org/database) (gemeinfrei, Unlicense), in der App mitgeliefert
  (`assets/channel-catalog.dat`, ~390 KB; neu bauen mit `tools/channel-catalog/build_catalog.py`). „Das Erste HD“, „ARD ᴴᴰ“
  und die EPG-ID „DasErste.de“ sind damit derselbe Sender, ohne eigenes Synonym.
- **Schutzregeln für jede Verbindung:** Zwei Gruppen werden nicht verbunden, wenn der Katalog verschiedene Sender sagt
  (ZDF ≠ ZDFneo – auch wenn der Anbieter ZDFneo die EPG-ID von ZDF gegeben hat), die Länder verschieden sind
  (n-tv ≠ TR: NTV) oder in den nächsten Stunden keine einzige Sendung übereinstimmt. Die Merkmale gelten für die ganze
  Gruppe – eine Kette A–B–C kann zwei widersprüchliche Sender nicht zusammenziehen. Filme/Serien: verschiedene TMDB-IDs
  bleiben getrennt.
- **Einstellungen → Zugänge → Alle Zugänge:** Zeile „Automatische Zuordnung“ zeigt, wie viele Quellen erkannt und wie viele
  Verwechslungen verhindert wurden; „Neu zuordnen“ baut sofort neu auf.
- **Alte Hand-Regeln** („Gehört nicht dazu“/„Stimmt“) werden beim ersten Aufbau nach dem Update einmalig gelöscht – sie
  stammten aus der fehlerhaften Prüfliste.
- Grenze: Sender ohne EPG, die im Katalog fehlen und nur einen mehrdeutigen Namen haben, bleiben getrennt.

## Neu in 3.33 (Design im Apple-TV-Stil)

- **Fließende Ecken** (Apples „Continuous Corners“) überall: Karten, Zeilen, Panels, Gruppen-Kästen.
- **Fokus hebt an statt einzurahmen:**
  - Poster und Kacheln werden größer (1,08), bekommen einen tiefen, weichen Schatten und einen Lichtreflex. Kein farbiger Rand mehr.
  - Mit „Effekte reduzieren“ (schwache Sticks) zeigt stattdessen ein heller Rand den Fokus.
  - Listenzeilen bleiben beim Fokus hell gefüllt (so macht es auch tvOS).
- **Material statt Glas mit Linien:** Flächen, Knöpfe und Chips ohne Ränder. Nebenschrift über Deckkraft statt Grautönen, damit sie auf jedem Hintergrundbild passt.
- **Filmdetails:** Titel, Beschreibung und Knöpfe stehen im unteren Bereich, darüber wirkt das Bild.
- **Player:**
  - Blendet weich ein und gleitet leicht hoch.
  - Sendernummer und Titel ohne Kästen; LIVE und REC als Punkte neben dem Titel.
  - Knöpfe in der Bildmitte kleiner, ihre Beschriftung nur im Fokus.
  - Untere Reihe als zurückhaltende Reiter.
  - Zeitleiste dünn, im Fokus dicker mit Knopf an der Zielstelle.

## Neu in 3.32 (Einstellungen neu aufgebaut)

- **7 statt 9 Bereiche**, links (Fernseher) bzw. als Menü (Handy), jeder mit seinem Zustand darunter:
  Zugänge · Wiedergabe · Inhalte · Sport · Sicherheit · Darstellung · System.
- **Gruppen statt Einzel-Zeilen:** Zusammengehöriges steht in einem Kasten mit Überschrift, die Texte sind kürzer.
- **Zugänge:** Je Zugang eine Karte (Art, Server, Stand, Marken für Aktiv, Verbindungen, HTTP, Auto-Update).
  OK öffnet die Seite des Zugangs mit allem, was nur ihn betrifft:
  - Wechseln, Jetzt aktualisieren, Nur EPG, Zugangsdaten bearbeiten.
  - Automatisch aktualisieren, Länder & Sprachen, EPG-Zeitverschiebung, HTTP zulassen.
  - Löschen (rot, mit Rückfrage).
  Mit 2 Zugängen vorher 27 Zeilen, jetzt 6.
- **„Alle Zugänge“** auf einer eigenen Unterseite: Anzeigen, automatischer Wechsel, Kategorien, Rangfolge, Werkzeuge.
- **EPG-Zeitverschiebung je Zugang** (vorher für alle gleich). Der bisherige Wert wird beim ersten Start auf alle Zugänge
  übertragen; Sicherungen speichern ihn je Zugang. Datenbank v13.
- **Länder & Sprachen** lässt sich für jeden Zugang einstellen, nicht nur für den aktiven.
- **Wiedergabe:** Puffer, Decoder, Tunneling und Dolby/DTS unter „Bild & Ton → Erweitert“ (nur ohne automatische
  Optimierung).
- **Sicherheit:** VPN, Kindersicherung (PIN, App-Sperre) und Datenschutz auf einer Seite; VPN-Standorte als Unterseite.
- **Zurück-Taste:** Unterseite → Bereich (Fokus auf der Zeile, die sie geöffnet hat) → Bereichsliste → raus.
- **Rückfrage** vor „Wiedergabeverlauf löschen“ und „Zugang löschen“.

## Neu in 3.27–3.31 (Zugang bearbeiten, „Alle Zugänge“)

- **Zugang bearbeiten** (Einstellungen → Zugänge → „Bearbeiten (Name)“): Name, Server, Benutzer, Passwort, M3U- und
  EPG-Adresse, HTTP, Intervall. Passwort und M3U-Adresse werden nie angezeigt (leer = unverändert). Neue Zugangsdaten
  werden vor dem Speichern geprüft. Favoriten, Verlauf, Sperren und Einstellungen bleiben erhalten.
- **„Alle Zugänge“** (ab zwei Zugängen, ganz oben im Zugangswechsel): Live-TV, Filme und Serien aller Zugänge in einer
  Liste, jeder Inhalt einmal.
  - **Namensabgleich:** EPG-ID, bereinigter Name mit Land, Synonyme (ARD = Das Erste, RTL II = RTL Zwei …),
    Programm-Fingerabdruck. Bei Filmen und Serien TMDB-ID, sonst Titel + Jahr. Im Zweifel getrennt.
  - **Serien:** Fehlende Staffeln und Folgen kommen aus den anderen Zugängen.
  - **Kategorien:** zusammengelegt nach Land + Thema („Deutschland · Sport“), umschaltbar auf „nach Zugang getrennt“.
- **Beste Quelle zuerst, automatischer Wechsel** (Einstellung, standardmäßig an):
  - **Rangfolge:** abspielbar auf dem Gerät → Zugang frei → eigene Sprache → zuletzt bewährt → Qualität →
    Zuverlässigkeit → freiester Zugang → eigene Rangfolge der Zugänge.
  - **Live-TV:** Klappt eine Quelle nicht (Fehler, kein Bild nach 3 s, Hänger), startet sofort die nächste, auch in
    einem anderen Zugang.
  - **Filme und Folgen:** ebenso (8 s), an derselben Stelle.
- **Übergreifend:** Fortschritt, „gesehen“ und Favoriten gelten über alle Zugänge. Das EPG kommt vom Original-Zugang,
  die Suche zeigt jeden Inhalt einmal.
- **Zuordnung:** seit 3.34 vollautomatisch (siehe dort); die Prüfliste gibt es nicht mehr.
- **Namen für den Abgleich aufs Handy:** Testsatz ohne Zugangsdaten und Adressen.
- Datenbank v12. Messbericht `docs/Messbericht-3.31.md`.

## Neu in 3.26 (automatische Aktualisierung je Zugang)

- **Einstellungen → Zugänge → „Automatisch aktualisieren (Name)“:** Zahl der Tage eingeben (0 = aus). Die App lädt dann
  Playlist **und** EPG neu, sobald seit der letzten Aktualisierung so viele Tage vergangen sind.
- **Countdown ohne Anzeige:** Gerechnet wird mit der gespeicherten Uhrzeit der letzten Aktualisierung. Zeit, in der App
  oder Gerät aus waren, zählt mit; ist der Countdown in der Zwischenzeit abgelaufen, wird beim nächsten Einschalten sofort
  aktualisiert. Eine Aktualisierung von Hand startet den Countdown neu.
- **Auch bei geschlossener App:** Hintergrundlauf genau zum Ablauf plus stündliche Prüfung als Sicherheitsnetz.
- **Fehlschlag** (kein Netz, Server aus): Der Zugang bleibt fällig, neuer Versuch nach 1 Stunde.
- **Falsche Geräteuhr** nach dem Einschalten wird abgefangen (kein Fehlstart, kein ewiges Warten).
- Datenbank v11 (neue Spalte, bestehende Zugänge stehen auf „aus“).

## Neu in 3.25 (Start, Import, schwache Geräte)

- **Start:** Die Live-Kategorien werden schon beim Start geladen, das Startprofil ordnet den Startcode in der App-Datei
  vorn an. Labor (gleiche Sitzung wie 3.24): „Inhalt sichtbar“ 18,8 → 12,4 s (−34 %).
- **Aktualisieren schneller:** Der Suchindex baut sich danach im Hintergrund auf; die Listen sind ohne ihn fertig.
  Eine Suche direkt danach wartet bis zu 3 s auf den neuen Index, sonst nutzt sie den alten. Labor: M3U mit 10 000
  Sendern 117,6–121,5 → 88,8–92,3 s.
- **Schwache Geräte (z. B. Fire TV Stick Lite):** Mit „Effekte reduzieren“ (dort automatisch an) entfallen Leuchtschatten
  und Lichtreflex. Fokus bleibt über helle Füllung und Rand sichtbar. Cube und Handy
  bleiben unverändert.
- Protokoll „Import-Phasen“ zeigt, wo die Zeit beim Aktualisieren bleibt. Messbericht `docs/Messbericht-3.25.md`.

## Neu in 3.24 (Plan-Fixes aus der Messkampagne)

- **Schnellerer Start:** eigenes Baseline-Profil (vorübersetzter Code für Start, Senderliste, Player); WorkManager startet
  erst bei Bedarf; Suchindex, Aktualisierung, EPG, Sport, DNS-Probe und Nacht-Pflege laufen erst nach „Inhalt sichtbar“.
  Labor (nach der nächtlichen Hintergrund-Optimierung von Android): Inhalt sichtbar 23,3 → 13,8 s im Emulator.
- **Player bleibt 30 s:** Kurz Home und zurück – das Bild läuft ohne Neuaufbau weiter. Nach 30 s wird wie bisher freigegeben.
- **Datenbank v10:** zwei Indizes gegen volle Durchläufe (Gegenstücke in anderen Zugängen, Sport-Zuordnung, Episoden-Aufräumen).
- **Sport:** wartet beim Start, bis der Inhalt steht; Wiederholungen nach Fehlern mit Zufallsanteil (±20 %).
- **Import:** weniger Rechenarbeit je Sender (Adressprüfung, Ländererkennung je Gruppe, Regex vorab).
- Messbericht `docs/Messbericht-3.24.md`.

## Neu in 3.23 (Zertifikats-Pinning, virtuelle Messkampagne)

- **Zertifikats-Pinning** für alle Dienste, die Schlüssel, Zugangsdaten oder DNS-Anfragen bekommen (TMDB, Bzzoiro,
  Sportmonks, Surfshark, Cloudflare- und Quad9-DNS). Gepinnt sind Wurzel-CAs mit Reserve, Ablauf 01.08.2027. Auf Android 9
  belegt: fremde CA wird abgelehnt, echte Kette geht durch. Pflege: `docs/Pinning.md`.
- **Virtuelles Testlabor** (`tools/virtual-lab/`, `VirtualLabTest`, `LabJvmBenchmarkTest`): Android-TV-Emulator (Android 9,
  1 GB) gegen einen Mock-Anbieter mit 10 000 Sendern und 8 Fehlermodi. Ergebnisse in `docs/Messbericht-3.23.md`, Plan in
  `docs/Plan-Fixes-3.24.md`.
- **Hauptthread beim Start frei:** Jugendschutz, VPN, Sport, Sehgewohnheiten und Auto-Aufnahme werden im Hintergrund
  angelegt (Ursache eines ANR im Stresstest und von StrictMode-Meldungen).
- **Verständliche Meldung** bei kaputter oder abgebrochener Anbieter-Antwort statt englischem Rohtext.

## Neu in 3.22 (Messkampagne, Schritt 0 des Overhauls)

- **Keine Optimierung ohne Messung:** Baseline, Messplan, Threat Model (STRIDE) und offene Regelkollisionen stehen in
  `docs/Overhaul-Schritt0.md`.
- **„Inhalt sichtbar“ (TTFD):** Das Fehlerprotokoll zeigt jetzt neben „Start bis Startbildschirm“ auch, wann die ersten
  Kategorien bzw. Startseiten-Reihen bedienbar sind (`reportFullyDrawn`, auch für Macrobenchmark).
- **„Bildzeiten …“ je Bereich:** Beim Bereichswechsel und beim Verlassen der App steht der Anteil verpasster und ruckeliger
  Bilder samt p50/p90/p99 im Protokoll (FrameMetrics, eigener Thread, nichts verlässt das Gerät).
- **Benchmark `liveListScroll`:** 60 s Scrollen durch „Alle Sender“ (Testfall 2); Kaltstart-Benchmarks warten auf den Inhalt.

## Neu in 3.21 (Rückmeldungen zu 3.20)

- **Beschreibung lesbar statt verdeckt:** Die Spoiler-Balken aus 3.20 sind weg. Jetzt stehen die ersten drei Zeilen
  ganz normal da und laufen nach unten weich aus, darunter „Mehr anzeigen“; aufgeklappt „Weniger anzeigen“. Passt
  der Text in drei Zeilen, gibt es keinen Schalter. Die Ausblendung funktioniert auf allen Android-Versionen.
- **Fokus beim Neustart wieder auf „Alle Sender“ / „Alle Filme“ / „Alle Serien“.** Ursache: Android stellte nach
  einem Neustart die alte Scrollposition der Gruppenliste wieder her, während die Gruppen noch luden – „Alle …“
  lag außerhalb des Bildes, der Fokus fiel auf eine gerade sichtbare Gruppe. Nach einem Tab-Wechsel waren die
  Gruppen schon geladen, darum stimmte es dort. Jetzt wird die gewählte Gruppe nach dem Laden immer sichtbar gemacht.

## Neu in 3.20 (Inhaltsbeschreibung als Spoiler)

- **Beschreibungen beginnen zugeklappt** (ab 3.21 lesbar statt mit Balken, siehe oben). Bei Filmen, Serien und
  TMDB-Titeln (Fernseher und Handy) stand zunächst „Beschreibung anzeigen“. OK bzw. ein Tipp klappt auf, der nächste wieder zu.
  Vorher füllte die TMDB-Beschreibung bis zu einen halben Bildschirm und verriet das Ende, bevor man gestartet hat.
- Kurze Beschreibungen (bis 180 Zeichen) stehen weiterhin einfach da – dort gibt es nichts zu verdecken.
- Der aufgeklappte Zustand gilt je Titel und übersteht Drehen des Handys; ein anderer Titel beginnt wieder zugeklappt.

## Neu in 3.19.1 (Korrektur)

- **„Mehr“-Panel am Handy ließ sich nicht schließen.** Ein Tipp neben ein geöffnetes Panel schloss die
  Seitenpanels und die Stream-Info, aber nicht „Mehr“ – und weil sich die Steuerung bei offenem Panel bewusst nicht
  ausblendet, blieb die Ansicht dauerhaft stehen. Jetzt schließt ein Tipp daneben jede dieser Flächen; ein Tipp
  **im** Panel schließt nichts mehr aus Versehen.
- **Schließen-Knopf am Handy:** Alle Seitenpanels („Mehr“, Tonspur/Untertitel, Engine, Quelle, Aufnahme,
  Sleep-Timer) haben oben rechts ein „×“. Am Fernseher bleibt die Zurück-Taste der Weg, dort ändert sich nichts.
- **Ton & Untertitel** ist am Handy nicht mehr breiter als der Bildschirm.

## Neu in 3.19 (Auswertung des Fehlerprotokolls vom Fire TV)

Grundlage: Protokoll vom 27.10.–02.11. (2 415 Zeilen, Android 9, Fire TV Stick).

- **VPN: 485 unnötige Neuverbindungen in fünf Tagen behoben.** Der Wächter hielt einen Tunnel für tot, sobald der
  letzte Handshake älter als 185 s war. Bei ungenutztem Tunnel handelt WireGuard aber gar nicht neu aus – es gab
  nichts zu senden. Jetzt wird erst ein kleines Prüfpaket geschickt; nur wenn darauf keine Antwort kommt, wird neu
  verbunden. Zeitsprünge durch Standby (Fernseher schläft) gelten nicht mehr als Abbruch.
- **Wiedergabe-Wächter ruhiger:** Ein einzelner Ton-Ausgabefehler löste „Bild ohne Ton“ aus und schaltete den Ton um
  (dreimal am 31.10., jedes Mal „behoben nach 0,0 s“) – jetzt zählt erst eine Häufung. Puffern mit ankommenden Daten
  ist kein Hänger mehr (vorher Quellwechsel nach 3 s, danach „behoben nach 0,6 s“); Dauerpuffern weiterhin schon.
- **HTTP 456** (Xtream: Stream gesperrt bzw. zu viele Verbindungen) gilt wie 401/403 als Zugangsproblem statt als
  Netzfehler – vorher sechs Neuversuche gegen einen Server, der bewusst ablehnt.
- **Startzeit:** Datenbank, Einstellungen, Aufnahmen, Erinnerungen und VPN-Start laufen nicht mehr auf dem
  Hauptthread (24 StrictMode-Meldungen im Startpfad, erster Bildschirm nach ~15 s).
- **Messwerte wieder brauchbar:** „Start bis Startbildschirm“ maß das Alter des Prozesses – weil das VPN den Prozess
  tagelang wach hält, standen dort Werte wie 86 606 484 ms (24 h). Jetzt wird Kaltstart und schon laufender Prozess
  unterschieden; „Start bis Wiedergabe“ zählt eine Pause des Nutzers nicht mehr mit (2 505 928 ms).
- **Protokoll lesbar:** Gleiche Meldungen werden je 10 Minuten zu einer Zeile zusammengefasst („12× in 10 min“).
  Vorher waren 1 410 von 2 415 Zeilen dieselbe VPN-Meldung und 404 derselbe Sport-Satz – Abstürze und Hinweise
  fielen aus dem begrenzten Protokoll heraus. Nicht geschlossene Dateien nennen jetzt die Fundstelle statt „unbekannt“.
- **Suchindex:** Bleibt der Bestand eines Zugangs unverändert, wird er nicht neu aufgebaut (im Protokoll bis zu
  103 s für 250 000 Einträge je Aktualisierung).
- **WireGuard-Absturz aus 3.14** (zweimal am 28.10. auf Android 9, `newIncompleteFuture` in `VpnService.onDestroy`):
  im Bytecode von 1.0.20260102 geprüft – dort ist der Aufruf mit `SDK_INT >= 31` abgesichert. Der Test prüft jetzt
  genau diese Stelle.

## Neu in 3.18 (Sicherheits- und Stabilitätsprüfung)

- **Sicherungen stärker verschlüsselt (Format 2):** PBKDF2-HMAC-SHA256 mit 600 000 Runden statt SHA1/120 000,
  Kennwort mindestens 8 Zeichen, Format/Verfahren/Rundenzahl gegen Manipulation geschützt (AES-GCM-AAD). Die Datei
  enthält Zugangsdaten und den privaten WireGuard-Schlüssel und lässt sich offline durchprobieren – das dauert jetzt
  rund 10-mal länger je Versuch. Alte Sicherungen (Format 1) bleiben lesbar. Wiederherstellen dauert am Fernseher
  dadurch einige Sekunden.
- **Heimnetz-Seiten (QR-Einrichtung, Sicherung, VPN, Protokoll):** Token 16 statt 12 Zeichen und in konstanter Zeit
  geprüft; Schutz-Kopfzeilen (kein Referer mit Token, CSP ohne fremde Skripte/Verbindungen, kein Einbetten,
  nosniff); Kopfzeilen nur ohne Steuerzeichen; Fehlerprotokoll nur noch unter „Protokoll teilen“ abrufbar.
- **Fremde Daten:** Kopfzeilen aus Playlists (`|User-Agent=…`) werden geprüft – ungültige werden verworfen statt die
  Wiedergabe oder Aufnahme abbrechen zu lassen. Xtream-Antworten: höchstens 128 Felder je Eintrag, Werte bis 8 KB.
- **Abbrüche:** An mehreren Stellen wurde ein Abbruch (Bildschirm verlassen) wie ein Fehler behandelt und die Arbeit
  lief weiter; jetzt wird er weitergereicht (`runCatchingCancellable`). Nächtliche Pflege höchstens 3 Versuche.
  Fokus im Textfeld (Fernseher) kann nicht mehr abstürzen.

## Neu in 3.17 (Stabilität, Prüfungen, Android 17)

- **Android 17 (targetSdk 37):** Verbindungen ins Heimnetz brauchen dort die Erlaubnis „Geräte in der Nähe“.
  Die App fragt danach, wenn die QR-Einrichtung bzw. die Übertragung vom Handy offen ist oder ein Zugang auf einen
  Server im Heimnetz zeigt (NAS, TV-Box; privat/link-lokal/IPv6-ULA). Abgelehnt: kurzer Hinweis, alles andere läuft.
  Auf Android 16 und älter ändert sich nichts. Ton im Hintergrund gibt es nicht (der Player hält beim Verlassen an,
  Bild im Bild zählt als sichtbar) – dafür ist kein Vordergrund-Dienst nötig.
- **WireGuard 1.0.20260102:** Die Aussage aus 3.16 war zu streng – der Android-12-Aufruf ist in dieser Version per
  SDK-Abfrage abgesichert. `LibraryApiLevelTest` prüft das jetzt direkt im Bytecode (Absicherung muss vorhanden sein).
- **Gerätetests:** neue Rauchtests (`app/src/androidTest`) für Start, SQLite, LibVLC, FFmpeg, Media3 und WireGuard;
  Workflow `device-tests.yml` lässt sie im Emulator mit Android 7, 9 und 14 laufen.
- **Release-Schlüssel:** Skript `tools/release-key.sh` zum Anlegen und Prüfen, Hinweise zur Sicherung (oben).
- **Aufgeräumt:** große Dateien aufgeteilt (alle unter 350 Zeilen, z. B. Player-Bildschirm → Zustand/Effekte separat,
  Wiedergabe-Wächter → Regeln/Detektoren separat); Lint-Altlasten (SharedPreferences per KTX) und detekt-Altlasten
  verringert, Einheiten-Umrechnungen und HTTP-Codes zählen nicht mehr als „magische Zahlen“.

## Neu in 3.16 (alles auf dem neuesten Stand, 02.11.2026)

- **Mindestens Android 7 (minSdk 24, vorher Android 6):** nötig für WorkManager 2.12.0 und DataStore 1.3.0.
  Alle eigenen Geräte (Fire TV Cube 3, Shield TV 2019, OnePlus 13) liegen weit darüber.
- Android Gradle Plugin 9.5.0 (vorher 9.4.1), KSP 2.3.13, UI Automator 2.4.0 (Benchmark), ASM 9.11.1 (Tests).
- Bereits neueste stabile Version (geprüft): Gradle 9.8.2, Kotlin 2.4.21, Compose BOM 2026.09.00, Core 1.19.1, Activity
  1.13.0, Lifecycle 2.11.0, TV Material 1.1.0, Room 2.8.5, Paging 3.5.3, Coroutines 1.11.0, OkHttp 5.5.1, Coil 3.6.3,
  Media3 1.11.1, LibVLC 3.7.7, FFmpeg-Decoder 1.9.0+1, ZXing 3.5.4, Desugaring 2.1.5, Robolectric 4.17, Roborazzi
  1.77.0, Spotless 8.10.4, ktlint 1.8.0, detekt 1.23.8, Konsist 0.18.1, AndroidX Test/Benchmark, GitHub-Actions.
- **Bewusst nicht:** WireGuard bleibt 1.0.20250531 – auch die neueste 1.0.20260102 ruft noch eine Methode auf, die es
  erst ab Android 12 gibt (LibraryApiLevelTest schlägt an; Absturz auf Fire TV beim Beenden des VPN).
  targetSdk bleibt 36: Android 17 (37) erzwingt u. a. eine Berechtigung fürs lokale Netz (Übertragung vom Handy per
  QR-Code), Ton im Hintergrund nur mit Vordergrund-Dienst und Certificate Transparency – das braucht eigene Anpassungen
  und Tests am Gerät. compileSdk 37 (Hauptversion; 37.2 bringt nur Zusatz-APIs).

## Neu in 3.14.2 (Review-Korrekturen)

- „Durchgehend aktives VPN“: Startet Android den VPN-Dienst selbst, verbindet die App sofort, auch ohne geöffnet zu werden
  (3.14.1 wartete in diesem Fall bis zum Öffnen).
- Protokoll bei VPN-Startfehlern wieder nur mit Fehlertyp – keine Serveradressen.
- Prüfbericht nach den Review-Prompts: Projekt-Dokument `claude/Review-3.14.md`.

## Neu in 3.14.1 (Korrektur)

- **Absturz behoben:** WireGuard 1.0.20251231 (aus 3.14) rief eine Java-Methode auf, die es erst ab Android 12 gibt.
  Auf Fire TV (Android 9) stürzte die App ab, sobald das VPN beendet wurde. Zurück auf WireGuard 1.0.20250531.
  Neuer Test `LibraryApiLevelTest` prüft WireGuard und FFmpeg gegen die Android-Versionen aus dem SDK.
- **VPN nach Update/Neustart:** Android erlaubt den VPN-Dienst nicht, solange die App unsichtbar im Hintergrund
  startet. Statt fünf Fehlversuchen mit Meldung wartet das VPN jetzt still und verbindet beim Öffnen der App –
  oder sofort, wenn Android den Dienst über „Durchgehend aktives VPN“ selbst startet.
- **VPN-Abbrüche:** Das Protokoll nennt jetzt, welche Prüfung den Abbruch erkannt hat (Handshake-Alter oder
  20 s ohne Antwort mit gesendeten Bytes).

## Neu in 3.14 (Bibliotheken aktuell)

- **Media3 1.11.1** (vorher 1.8.0) für ExoPlayer, HLS, DASH, Oberfläche und OkHttp-Anbindung; **LibVLC 3.7.7**.
- **OkHttp 5.5.1** (vorher 4.12) und **Coil 3.6.3** (vorher 2.7, neues Paket `coil3`, Netzwerkmodul `coil-network-okhttp`).
  Bildspeicher je Geräteklasse und Cache-Verhalten wie bisher. Coil 3 verwirft den Bild-Cache von Coil 2 beim ersten
  Start einmal (anderes Dateiformat): Poster und Logos werden danach einmal neu geladen.
- Kotlin 2.4.21, Android Gradle Plugin 9.4.1, Gradle 9.8.2, Compose BOM 2026.09.00, TV Material 1.1.0, Room 2.8.5,
  Paging 3.5.3, Lifecycle 2.11.0, Coroutines 1.11.0 und weitere.
- **compileSdk 37** (verlangt von den neuen AndroidX-Versionen); targetSdk bleibt 36, minSdk damals 23 (ab 3.16: 24).
  Lokal in Android Studio: *SDK Manager → Android SDK Platform 37* installieren, falls der Build danach fragt.
- Bewusst nicht auf die neueste Version: WorkManager 2.11.2, DataStore 1.2.1 (die nächsten verlangen Android 7),
  WireGuard 1.0.20250531 (siehe 3.14.1).
- **FFmpeg-Tondecoder** (AC3, E-AC3, DTS, MP2) bleibt 1.9.0+1, eine Version für Media3 1.11 gibt es nicht.
  Der neue Test `BinaryCompatTest` prüft im Build jede Methode, die der Decoder in Media3 aufruft, ebenso
  Media3-OkHttp und Coil-OkHttp gegen OkHttp 5.

## Neu in 3.13 (Stabilität, Speicher, Sicherheit)

- **EPG mit Generationen** (Datenbank 8): Ein Import schreibt direkt, aber unsichtbar; umgeschaltet wird mit einer Zeile,
  das alte EPG in Blöcken gelöscht. Vorher: Zwischentabelle + Kopie in einem Schritt (kurzzeitig dreifacher Platz,
  lange Schreibsperre). Beschreibungen auf 600 Zeichen gekürzt. Zeitzonen im Format „+01:00“ werden gelesen.
- **Suche ohne Ausfallzeit:** Der Suchindex wird unter neuem Schlüssel aufgebaut, der alte gilt bis zum Umschalten.
- **Nachtpflege (WorkManager):** einmal am Tag im Leerlauf, nur WLAN/LAN: EPG erneuern (wenn fällig), Datenbank verkleinern
  (nur wenn ≥ 30 % frei). Bei offener App wie bisher.
- **Geräteklasse** (schwach/normal/stark): Bildspeicher 24/48/64 MB (höchstens 20 % Heap), LibVLC-Optionen, LibVLC wird bei
  Speichermangel freigegeben. Sport-Index nur noch aus Sport-/Event-Sendern plus gezielt gebrauchten Sendern
  (100.000 Sender: 1,5 MB statt 39 MB).
- **Robustheit:** M3U-Zeilen über 64 KB werden verworfen (kein OOM bei Fehlerseiten), Gesamt-Zeitlimit für API-Aufrufe,
  Download-Abbruch unter 16 KB/min, LibVLC-Freigabe je Engine mit Wächter, Sport-Abruf blockiert die Kontrolle vor Anstoß
  nicht mehr, Senderliste springt bei der Gruppen-Abfrage nicht mehr.
- **Sicherheit:** PIN-Sperre übersteht Neustarts (bis 16 min), kein Klartext-HTTP zu Diensten mit Schlüssel,
  Sportmonks-Schlüssel im Kopf statt in der Adresse, `api_token` im Protokoll geschwärzt.
- **Aufnahmen:** HLS mit AES-128 wird entschlüsselt, Segmente nach Sequenznummer (keine Doppelten).
- **Start:** Baseline Profile für Start, Leiste, Senderliste und Player; Debug-APK mit StrictMode-Protokoll.
- Datenbank-Schemas liegen unter `app/schemas` (ab Version 7).

## Neu in 3.11

- **Alle Zugänge & EPG aktualisieren:** unter *Einstellungen → Aktualisieren & EPG* und im Konto-Dialog (oben rechts), sobald es
  mehr als einen Zugang gibt. Die Zugänge laufen nacheinander im Hintergrund (Anbieter erlauben meist nur eine Verbindung);
  schlägt einer fehl, laufen die übrigen weiter.
- **Dezente Fortschrittsanzeige:** kleine einzeilige Pille unten rechts (Handy: über der unteren Leiste) statt des Banners
  über dem Inhalt; nicht fokussierbar, verschwindet 4 s nach dem Ende (Fehler 8 s).

## Am Handy (z. B. OnePlus 13)

Dieselbe APK läuft auf Fernseher und Handy. Beim Start erkennt die App das Gerät (TV-Modus, Leanback oder fehlender
Touchscreen = Fernseher) und zeigt die passende Oberfläche. Daten, Zugänge, Suche, Aufnahmen und Player sind
dieselben; Favoriten und Fortschritt lassen sich per *Sicherung* übertragen.

- **Navigation:** Leiste unten mit Live TV, Guide, Filme, Serien und Suche; oben Aufnahmen, Einstellungen und der
  Zugangs-Avatar. Kategorien als waagerecht wischbare Chips. Hochformat, randlos (Edge-to-Edge),
  Zurück per Wischgeste vom Rand.
- **Antippen und gedrückt halten:** Antippen öffnet bzw. startet, gedrückt halten macht Sender, Filme, Serien und
  ganze Gruppen zu Favoriten. Im Guide: gedrückt halten = aufnehmen bzw. einplanen.
- **Guide:** statt des breiten Rasters „Was läuft um …“ – Zeitstrahl mit „Jetzt“, 6 Stunden zurück (Archiv) und
  12 Stunden voraus.
- **Details:** Titelbild oben, darunter Infos, großer Abspiel-Knopf und Symbol-Knöpfe (Favorit, Trailer, Engine).
- **Player:** dreht automatisch ins Querformat und läuft im Vollbild (Leisten per Wischen vom Rand). Antippen
  blendet die Steuerung ein/aus, die Zeitleiste lässt sich ziehen. Doppeltippen links/rechts: 10 s zurück/vor
  (Live: Sender zurück/weiter). Rechts senkrecht wischen: Lautstärke, links: Helligkeit.
- **Bild im Bild:** Wer während der Wiedergabe zum Startbildschirm wischt, sieht das Video als kleines Fenster weiter
  (Android 8+, ab Android 12 mit flüssigem Übergang).
- **Eingabefelder:** Antippen setzt den Cursor und öffnet die Tastatur (am Fernseher weiterhin erst mit OK).
- **Absturzbericht:** Wurde die App unerwartet beendet (Absturz, „App reagiert nicht“, auch im Video-Code), fragt
  sie beim nächsten Start nach und schickt den Bericht übers Teilen-Menü (WhatsApp, Mail …). Das Fehlerprotokoll
  in den Einstellungen hat am Handy ebenfalls „Teilen“. Beendet Android die App im Hintergrund wegen Speichermangels
  (normal), erscheint kein Bericht.
- **Fernseher bleibt unverändert:** Alle TV-Tests laufen weiter; am Fernseher ist die Bedienung exakt wie bisher.

## Darstellung

- **Erscheinungsbild:** *Einstellungen → Darstellung*: **Automatisch** (tagsüber hell, ab 19 Uhr bis 7 Uhr dunkel –
  wechselt ohne Neustart), **Dunkel** oder **Hell**. Dazu fünf Akzentfarben (Blau, Violett, Türkis, Orange, Rosé).
  Über laufenden Videos ist die Oberfläche immer dunkel, damit nichts blendet.
- **Glas-Look:** Halbtransparente Flächen über einem weichen Farbverlauf; in Film- und Serienrastern übernimmt der
  Hintergrund unscharf das Bild des gewählten Titels. Bewusst ohne Echtzeit-Weichzeichner (zu teuer für TV-Sticks),
  ab Android 12 zusätzlich echter Blur.
- **Fokus:** Kacheln heben sich mit Schatten, leuchtendem Rand und einem Lichtreflex; Listenzeilen füllen sich hell
  (dunkel) bzw. in der Akzentfarbe (hell). Alle Farbkombinationen sind auf Lesbarkeit geprüft (Kontrast nach WCAG).
- **Icons:** Eigene Icon-Familie mit kleinen Animationen beim Fokus (Antenne wackelt, Zahnrad dreht sich,
  Herz pocht, Aufnahme-Punkt pulsiert …). Schrift: Inter (SIL Open Font License).
- **Titel:** Anbieter-Kürzel wie „|DE|“, „[4K]“, „(2023)“ werden aus den Titeln entfernt und als Qualitäts-Badge
  bzw. Jahr angezeigt. Angefangene Filme zeigen einen Fortschrittsbalken auf dem Poster.

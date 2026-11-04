# Zugang bearbeiten und „Alle Zugänge“ – Umsetzung und Nachweis (04.11.2026, Version 3.31)

Umsetzung von `Plan-Kombinierte-Zugaenge.md` (v4) und `Plan-Namensabgleich.md` mit den Entscheidungen vom 03.11.2026:
- Automatischer Quellenwechsel standardmäßig an.
- Gleichstand → freiester Zugang.
- Kategorien zusammengelegt, aber trennbar.

## Was gebaut wurde

| Version | Teil | Wo |
|---|---|---|
| 3.27 | Zugang bearbeiten (Prüfen vor dem Speichern, Passwort nie sichtbar, ID bleibt) | `EditPlaylistScreen/-ViewModel`, `AccountVerifier`, `PlaylistRepository.updatePlaylist` |
| 3.28 | Zuordnungstabelle (`combined_links`), Korrekturregeln (`combined_rules`), EPG-Quelle je Sender (DB v12) | `data/combined/*`, `Migration11To12` |
| 3.28 | Namensabgleich: EPG-ID (Qualitätszusätze, Synonyme), Land + Name, Name ohne Land, Programm-Fingerabdruck; TMDB / Titel + Jahr; Land + Thema für Gruppen | `MatchKeys`, `Clusterer`, `CategoryTopics`, `EpgFingerprints` |
| 3.29 | „Alle Zugänge“ als virtueller Zugang (-2) in denselben Tabellen – Listen, Suche, Favoriten laufen unverändert | `CombinedBuilder`, `Combined` |
| 3.29 | Quellen-Rangfolge und automatischer Wechsel über Zugänge (Live) | `SourceRanker`, `CombinedPlayables`, `LiveFailover` |
| 3.30 | Filme und Folgen: Quellenwahl, automatischer Wechsel (8 s) an derselben Stelle, Folgen aus allen Zugängen | `Playable.vodSources`, `CombinedVod.buildEpisodes`, `PlayerViewModel.switchVariant` |
| 3.31 | Fortschritt/Favoriten übergreifend, EPG vom Original, Suche einmal, Zuordnung prüfen, Namen-Export | `CounterpartFinder`, `ChannelPagingSource`, `MatchReview`, `NamesExport` |

## Nachweise (automatische Tests)

- **Namensabgleich** (`NameMatchingTest`):
  - Wird zusammengeführt: ZDF über EPG-ID mit HD-Zusatz und Zierzeichen; ARD = Das Erste; RTL II = RTL ZWEI; Pro7 = ProSieben; tagesschau24 ohne Land.
  - Bleibt getrennt: ZDF/ZDFneo, WDR Köln/Aachen, Sky Sport 1/2, RTL/RTL +1, Sport 1 DE/PL, mehrdeutiger Name ohne Land.
  - Programm-Fingerabdruck nur, wenn er eindeutig ist.
  - Eigene Regeln (trennen und zusammenlegen) wirken.
  - Filme: Der Pate = The Godfather (TMDB); „Inception (2010) [4K]“ = „Inception 2010 [FHD]“; Dune 1984 ≠ 2021; Remake-Schutz bei zwei TMDB-IDs; Film ohne Jahr bleibt allein.
  - Serie ohne Jahr findet die eine mit Jahr.
  - Gruppen: „DE | Sport“ = „GERMANY SPORTS HD“ = „Deutschland · Sport“, „Transport“ ist kein Sport.
- **Ende zu Ende mit zwei Zugängen** (`CombinedAccountsTest`):
  - 6 statt 9 Sender; ZDF mit 3 Quellen aus 2 Zugängen; Favorit übernommen.
  - Abspielen startet eine echte Quelle.
  - Gruppen zusammengelegt.
  - Film: ein Eintrag, 2 Quellen.
  - Serie: Folgen 1×1, 1×2, 2×1 aus beiden Zugängen.
  - Aufnahme-Adresse aufgelöst; Suche einmal.
  - Ausschließen → Kombination weg.
  - Prüfliste zeigt ARD/Das Erste als unsicher; „Gehört nicht dazu“ trennt.
- **Rangfolge und Wechsel** (`SourceRankerTest`):
  - 4K hinten auf 1080p-Geräten, voller Zugang hinten, eigene Sprache vorn.
  - Bei gleicher Qualität der freieste Zugang, dann die Rangfolge.
  - Ohne „automatisch“ kein Wechsel über Zugänge.
  - Position: gleich bei ≤ 3 % Laufzeitunterschied, sonst anteilig 10 s früher.
- **Zugang bearbeiten** (`EditPlaylistTest`, `EditPlaylistScreenTest`):
  - ID, Favoriten und Verlauf bleiben; Geheimnisse nur verschlüsselt; leeres Passwort = unverändert.
  - Prüfung: gültig/ungültig/keine M3U/nicht erreichbar.
  - Formular zeigt das Passwort nie.
- **Migrationen** 10→11 und 11→12: zweimal ausführbar, Daten bleiben.

## Virtuelles Labor: Aufbau von „Alle Zugänge“ (JVM, `LabCombinedBuildTest`, GUEVEN_LAB=true)

Zwei Zugänge schreiben dieselben Inhalte verschieden („DE: Sender 1 HD“ ↔ „|DE| SENDER 1 FHD“, nur jede zweite Zeile mit
EPG-ID; Filme mit Jahr in Klammern ↔ ohne Klammern mit [FHD]).

| Größe | Quellen | Ergebnis Sender | Aufbau | Neuaufbau | Speichergrenze |
|---|---|---|---|---|---|
| 2 × (10 000 Sender + 20 000 Filme) | 60 000 | 10 000 von 10 000 Paaren erkannt (100 %) | 3,8 s | 2,9 s | läuft mit 128 MB Heap |
| 2 × (30 000 Sender + 60 000 Filme) | 180 000 | 30 000 von 30 000 (100 %) | 10,9 s | 10,2 s | läuft mit 192 MB Heap |

- Die Zeiten gelten für einen Desktop-Prozessor. Auf dem Cube rechne ich mit dem 3- bis 5-Fachen [geraten]. Der Aufbau läuft im Hintergrund und wartet, bis laufende Aktualisierungen fertig sind.
- Die Zeilen werden blockweise geschrieben, nicht alle im Speicher gesammelt. Zu wenig Speicher führt zu einem Protokolleintrag, nicht zum Absturz.
- Die Testdaten sind künstlich. Die echte Trefferquote zeigt erst der Testsatz aus deinen Zugängen (Einstellungen → „Namen für den Abgleich aufs Handy“).

## Abweichungen vom Plan

- Die Zuordnung wird nach jedem Import komplett neu aufgebaut, nicht nur für den geänderten Zugang. Der Aufbau ist gebündelt und läuft im Hintergrund.
- Geräteprüfung: Auf Geräten ohne 4K kommen 4K-/8K-Quellen nach hinten. Codec-Fähigkeit (HEVC/AV1/HDR) und gemessene echte Auflösung zählen nicht für die Rangfolge.
- Namensabgleich ohne TMDB-Online-Suche, IMDb-ID, Logo-Adresse, Laufzeitvergleich und Anime-Nummerierung.
- Korrekturen nur über „Zuordnung prüfen“, kein langes Drücken in den Listen.

## Nicht im Labor gemessen

- Start, Scrollen und Umschaltzeit im Emulator: Diesmal gab es keinen Emulator-Lauf. Die Listen nutzen dieselben Abfragen wie ein einzelner Zugang (eine Zeile je Inhalt, seitenweise). Langsamer als bisher erwarte ich sie nicht [geraten].
- Automatischer Wechsel mit echten, ausgefallenen Streams: Am Gerät bitte nach Prüfliste 15q, Punkte Q3–Q5.

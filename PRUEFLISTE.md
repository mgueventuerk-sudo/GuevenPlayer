# Prüfliste Handy & Fernseher (Version 3.13)

Eine einzige APK für Handy und Fernseher. 3.2 enthält alles aus 3.1 (gleiche PIN- und Datenformate – das Update
läuft über 3.1 drüber, ohne dass etwas verloren geht) plus die Punkte aus dem Verbesserungsplan und die neue
Sport-Sektion. Alles unten ist automatisch getestet (519 Tests, dazu Prüfung im Android-Emulator); am Gerät geht es um echte Streams, echte
Spielpläne und das Gefühl bei der Bedienung. Dauer etwa 30 Minuten. Bei einem Fehler: *Einstellungen → Fehlerprotokoll*.

**Wichtig:** Nicht auf eine ältere Version zurück installieren (3.3 → 3.2 oder 3.1) – die ältere Version kennt die neue Datenbank nicht und
würde Zugänge, Favoriten und Fortschritt löschen.

## 1. Update und Sicherheit

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| U1 | 3.2 über die vorhandene App (3.0 oder 3.1) installieren | Zugänge, Favoriten, Fortschritt, Erinnerungen sind da | Update, Datenbank 5 → 6 |
| U2 | Gesperrte Gruppe öffnen, bisherige PIN eingeben | PIN stimmt (keine neue nötig) | PIN-Format wie 3.1 |
| U3 | Einrichtung bzw. Kennwortfeld öffnen und einen Screenshot versuchen | Screenshot gesperrt bzw. schwarz | Schutz der Eingaben |
| U4 | *Einstellungen → Zugänge*: Zugang über reine `http://`-Adresse | Hinweis „unverschlüsselt (HTTP)“ | Hinweis auf HTTP |
| U5 | QR-Einrichtung vom Handy aus einem **anderen** WLAN aufrufen | Seite lädt nicht; im selben WLAN klappt es | Einrichtung nur im Heimnetz |

## 2. Suche, Zugänge, Wiedergabe

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| W1 | Zwei Zugänge anlegen, Suche „nord“ | Oben Reiter „Alle Playlists · N“ und je Playlist; Handy: waagerecht wischen, Fernseher: Kanal +/− | Suchseiten |
| W2 | Suche verlassen und wieder öffnen | Zuletzt gewählte Seite ist wieder offen | Seite gemerkt |
| W3 | Zugang, der gerade woanders genutzt wird (1/1), Sender starten | Frage „Zugang belegt“ mit Angebot des anderen Zugangs, „Trotzdem starten“, „Abbrechen“ – **nie** automatischer Wechsel | Nachfrage belegt |
| W4 | Stream, der nicht startet | Frage „Stream ausgefallen“ mit dem anderen Zugang (jeder Zugang nur einmal angeboten) | Nachfrage Ausfall |
| W5 | Film in Zugang A bis zur Hälfte schauen, denselben Film in Zugang B öffnen | „Weiterschauen“ an derselben Stelle; Favorit gilt in beiden Zugängen | Fortschritt/Favoriten geteilt |
| W6 | Abo kurz vor Ablauf bzw. abgelaufen | Hinweis am Zugang („läuft in X Tagen ab“ bzw. „abgelaufen“); beim Abspielen „Zugriff verweigert“ statt allgemeinem Fehler | Konto-Hinweise |
| W7 | Im Player „Sleep-Timer“ → 30 min (zum Test „Ende der Sendung“) | Knopf zeigt „Aus um …“; danach stoppt die Wiedergabe mit Hinweis | Sleep-Timer |
| W8 | Live-Sender mit HLS 10 min laufen lassen | Abstand zum Live-Bild bleibt gleich, keine Ruckler | Live-Abstand |
| W9 | TV-Guide: Sendung in der Zukunft → OK | Uhr-Symbol an der Sendung; 2 min vor Beginn Hinweis „Gleich: …“ bzw. Benachrichtigung; „Jetzt umschalten“ startet den Sender | Erinnerungen |
| W10 | *Aufnahmen* öffnen | Erinnerungen stehen oben, Papierkorb löscht sie | Erinnerungsliste |

## 3. Sport (neu)

Die Spiele kommen **immer online** (ESPN, ohne Schlüssel). Gezeigt werden nur Spiele, die auf einem Sender deiner
Playlists laufen – erkannt über den Sendernamen oder das EPG (beide Mannschaften im Sendungstitel, ±45 min).

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| S1 | App an einem Spieltag öffnen (z. B. Samstag Bundesliga) | Fernseher: Tab „Sport“ neben Live TV; Handy: Pokal-Symbol oben. An Tagen ohne passende Spiele fehlt beides | Tab nur mit Inhalt |
| S2 | Sport öffnen | „Jetzt live“, dann „Heute“, „Morgen“, „Diese Woche“; Fokus auf dem ersten Spiel; Countdown läuft flüssig | Übersicht |
| S3 | Laufendes Spiel → OK/Antippen | Sender startet sofort (bei gesperrter Gruppe erst PIN) | Live-Klick |
| S4 | Kommendes Spiel → OK/Antippen | Fenster mit Countdown, Sender und „Dort läuft gerade …“; „Erinnerung setzen“ (fokussiert), „Sender jetzt ansehen“ | Zukunft-Klick |
| S5 | Unten „Ligen wählen“ bzw. *Einstellungen → Sport → Ligen wählen*, z. B. Premier League einschalten | Neue Spiele erscheinen nach wenigen Sekunden; abgewählte verschwinden | Eigene Ligen |
| S6 | *Einstellungen → Sport*: eigenen Sportmonks-Schlüssel eintragen | Unter „Ligen wählen“ kommt ein Abschnitt „Sportmonks“ mit den Ligen deines Tarifs (Gratis: dänische und schottische Liga) | Sportmonks |
| S7 | Falschen Sportmonks-Schlüssel eintragen | Hinweis „Schlüssel ungültig …“ unter „Ligen wählen“ | Fehlermeldung |
| S8 | Sport-Gruppe in *Kategorien verwalten* ausblenden | Spiele auf diesen Sendern verschwinden aus „Sport“ | Ausgeblendete Gruppen |
| S9 | Fehlerprotokoll nach 1 Stunde Nutzung | Zeilen „Sport: … Events aus … Ligen“; im Hintergrund keine Abrufe | Abruf-Takt |

## 4. Neu nach dem Emulator-Test

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| E1 | Fernseher: Live TV, Sender fokussieren (Vorschau oben rechts läuft), OK drücken und mit Zurück zurückkehren | Derselbe Stream läuft in Vollbild und Vorschau **ohne erneutes Laden** weiter | Vorschau ↔ Vollbild |
| E2 | TV-Guide: vergangene Sendung auf einem Sender mit mehreren Qualitäten (z. B. „ZDF · 2 Quellen“), bei dem nur eine Archiv hat | Catch-up startet (über die Qualität mit Archiv) statt „kein Archiv“ | Catch-up |
| E3 | Handy mit schmalem Bildschirm und Sport-Symbol oben | Avatar bleibt rund und vollständig; der Schriftzug „GuevenPlayer“ entfällt bei Platzmangel | Kopfzeile |
| E4 | Älteres Gerät mit Android 7 (ab 3.16 Mindestversion): PIN festlegen und eingeben | Kein Absturz (3.1 stürzte hier ab), PIN funktioniert | PIN auf alten Geräten |
| E5 | Länderauswahl bei einer Playlist mit einem Land | „1 Gruppe“, „aus einem Land“; keine Zeile „0 Gruppen ohne Länderangabe“ | Texte |
| E6 | App frisch starten und die Senderliste öffnen (auch nach zuvor gespeicherter Kategorie) | Kategorie „Alle Sender“ ist aktiv; Fokus steht beim ersten Sender, nicht in der linken Gruppenliste | Startfokus |
| E7 | Einen anderen Sender wählen, im Vollbild umschalten und Zurück drücken | Fokus und Scrollposition stehen wieder exakt auf dem zuletzt gewählten Sender; dessen Vorschau läuft weiter | Fokus- und Stream-Wiederherstellung |

## 5. Neu in 3.3

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| N1 | Fernseher: ARD/ZDF/Dritte, ein HD-Sender mit Dolby-Ton und ein Sport-Sender – je in der Vorschau und im Vollbild | Überall Ton, ohne Hinweis „wechsle zu LibVLC“; Start ohne Umweg | Ton (MP2/AC3/E-AC3 per FFmpeg, PCM statt Durchreichen) |
| N2 | Mit AV-Receiver/Soundbar: *Einstellungen → Dolby/DTS durchreichen* an | Receiver zeigt Dolby an; bei Stille wieder ausschalten | Passthrough |
| N3 | Live TV: Sender öffnen, im Vollbild 3× umschalten, Zurück | Liste steht auf dem **zuletzt gesehenen** Sender (auch weit unten), Vorschau spielt ihn ohne neues Laden weiter | Rücksprung |
| N4 | Wie N3, aber über „Alle Sender“ bzw. Favoriten geöffnet | Liste bleibt in dieser Kategorie und steht auf dem Sender | Rücksprung je Kategorie |
| N5 | *Einstellungen → Kategorien verwalten*: „Verschieben“ OK, 3× runter, OK; dann Filme und Serien | Neue Reihenfolge sofort in Live TV, Filme, Serien; „Standard-Reihenfolge“ stellt sie zurück | Sortieren |
| N6 | Fernseher, nach dem Update auf 3.3 einmal öffnen und ein paar Minuten warten (EPG lädt neu) | Bereich „Sport“ erscheint, sobald ein Spiel auf deinen Sendern läuft; *Einstellungen → Sport* zeigt „X Spiele geladen, Y auf deinen Sendern“ | Sport am TV |
| N7 | Senderliste mit gedrückter Taste schnell durchblättern | Flüssiger als bisher; Nummern stimmen auch weit unten | Tempo |

## 6. Neu in 3.4

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| V1 | Sender mit mehreren Qualitäten („2 Quellen“), dessen beste Qualität hängt | Nach 3 s Hinweis „wechsle automatisch …“, nächste Qualität läuft; beim nächsten App-Start gleich die funktionierende | Quellenwechsel |
| V2 | Laufender Sender puffert länger (z. B. WLAN kurz stören) | Nach 3 s Puffern Wechsel zur nächsten Qualität | Puffer-Wechsel |
| V3 | Im Vollbild ein paar Sender umschalten, dann Rechts gedrückt halten | Liste „Verlauf“ mit den zuletzt gesehenen Sendern; Rechts kurz = vorheriger Sender | Verlauf |
| V4 | Sender mit zwei Tonspuren: im Overlay „Ton“ auf die zweite stellen, Sender wechseln und zurück | Die zweite Tonspur ist wieder gewählt | Tonspur merken |
| V5 | *Einstellungen → Favoriten sortieren*: einen Sender verschieben | Live TV → Favoriten und Umschalten in den Favoriten folgen der neuen Reihenfolge | Favoriten |
| V7 | *Sport → Sender zuordnen*: Liga ohne Sender wählen, Sender suchen und wählen | Die Spiele der Liga erscheinen unter „Sport“ mit diesem Sender | Sport von Hand |

## 7. Neu in 3.5

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| F1 | Suche öffnen, noch nichts eingeben | Nur Suchfeld und Hinweis – Playlists und Filter erscheinen erst ab zwei Zeichen | Suche |
| F2 | Fernseher: obere Leiste | „Sport“ (Pokal) ist da, solange mindestens einer Liga gefolgt wird | Sport am TV |
| F3 | Sport öffnen, wenn das EPG die Spiele nicht nennt | Abschnitt „Ohne Sender“ mit Hinweis; OK auf ein Spiel öffnet „Sender zuordnen“ für dessen Liga | Sport ohne Treffer |
| F4 | Startbildschirm des Fernsehers bzw. Handys | Neues App-Icon und Banner in Gelb-Rot | Icon |
| F5 | Einstellungen am Fernseher | Links die Bereiche (Zugänge & EPG, Wiedergabe, Filme & Serien, Kategorien & PIN, Sport, Darstellung, Speicher & Sicherung, Hilfe & Info), rechts der Inhalt; Zurück springt in die Bereichsliste | Einstellungen |
| F6 | Einstellungen am Handy | Bereiche als Chips oben, darunter der Inhalt | Einstellungen |
| F7 | *Einstellungen → Sport → Bzzoiro-API-Schlüssel* eintragen (sports.bzzoiro.com → Registrieren) | Meldung „Bzzoiro verbunden – … Ligen umgestellt“; unter „Sport“ stehen die Spiele mit Sky/DAZN/beIN-Sendern deiner Playlist | Bzzoiro |

## 8. Neu in 3.6 – VPN und Datenschutz

Vorher in Surfshark: *VPN → Manuelle Einrichtung → Router/Desktop → WireGuard → Schlüsselpaar erzeugen → Deutschland – Frankfurt → .conf herunterladen*.

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| S1 | *Einstellungen → Sicherheit & VPN → VPN einrichten*, QR-Code mit dem Handy scannen, .conf wählen, „An Fernseher senden“ | Handy: „VPN eingerichtet ✓“. Fernseher: Android fragt „Verbindungsanfrage“ → OK. Oben rechts grünes Schild „DE“ | Einrichtung |
| S2 | *Sicherheit & VPN* öffnen, einen Sender abspielen | „Verbunden mit Deutschland · Frankfurt seit …“, die Datenmenge steigt. Andere Apps (z. B. YouTube) laufen normal ohne VPN | Nur GuevenPlayer |
| S3 | Sender im Vollbild: Overlay öffnen | Grünes Schild „VPN · DE“ neben der Uhr | Player-Schild |
| S4 | Gerät aus- und wieder einschalten, dann GuevenPlayer öffnen | Schild sofort grün (VPN lief schon seit dem Hochfahren) | Immer an |
| S5 | Handy: während der Wiedergabe WLAN aus (mobile Daten an) | Wiedergabe läuft weiter; das Schild bleibt grün bzw. ist nach wenigen Sekunden wieder grün („VPN wieder verbunden“) | Netzwechsel |
| S6 | Surfshark-App starten und dort verbinden | GuevenPlayer: rotes Band oben „VPN getrennt – Verbindung ungeschützt …“, Wiedergabe läuft weiter, Benachrichtigung „VPN getrennt“; nach 20 s wird das Band zu einem blinkenden „⚠ VPN getrennt“ | Abbruch sichtbar |
| S7 | Surfshark trennen, in der Benachrichtigung „Neu verbinden“ (oder *Sicherheit & VPN → Neu verbinden*) | Android fragt erneut nach der Erlaubnis → OK → grün, „VPN wieder verbunden“ | Neu verbinden |
| S8 | Zweite .conf (z. B. Niederlande) über „Standort hinzufügen“ übertragen, dann „Deutschland“ wieder wählen | Liste mit DE und NL, das Schild zeigt den gewählten Standort | Standorte |
| S9 | „Ton/Vibration bei Trennung“ an, dann S6 wiederholen | Kurzer Ton (Handy zusätzlich Vibration) | Ton |
| S10 | „Online-Zusatzinfos“ aus | Sport lädt nichts Neues, Filmdetails ohne TMDB-Infos; wieder an → alles wie vorher | Zusatzinfos |
| S11 | „Ohne Verlauf“ an, einen Film 2 Minuten schauen | Kein Fortschritt, „Zuletzt gesehen“ unverändert, im Protokoll keine Sender | Ohne Verlauf |
| S12 | „App-Sperre“ an (PIN nötig), Home-Taste, App wieder öffnen | PIN-Abfrage vor dem Inhalt; am Handy in „Zuletzt verwendet“ kein Bildinhalt | App-Sperre |
| S13 | Zugang mit `http://` in *Zugänge & EPG* | Roter Hinweis „⚠ Unverschlüsselt (http) …“ | HTTP-Warnung |
| S14 | Sicherung erstellen und auf dem Handy wiederherstellen | VPN-Standorte sind mit dabei, Android fragt einmal nach der VPN-Erlaubnis | Sicherung |

## 9. Neu in 3.7

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| T1 | Fernseher: Live TV in der oberen Leiste, dann Runter | Fokus auf der Gruppenliste links (gewählte Gruppe), nicht auf einem Sender | Navigation |
| T2 | Filme bzw. Serien in der Leiste, Runter | Fokus auf den Film- bzw. Serien-Gruppen, nicht auf einem Poster; mit Rechts weiter zu den Titeln | Navigation |
| T3 | Aus den Film-Gruppen Hoch drücken | Fokus auf dem Tab „Filme“ (nicht auf „Live TV“) | Navigation |
| T4 | OK auf „Filme“ in der Leiste | Fokus auf den Film-Gruppen | Navigation |
| T5 | *Sicherheit & VPN → VPN einrichten*: de-ber.conf übertragen | „Standort … gespeichert“, ggf. Erlaubnis bestätigen, Schild oben grün „DE“. Klappt es nicht: Text im roten Band bzw. unter *Sicherheit & VPN* notieren und das Fehlerprotokoll schicken (Einträge „VPN …“) | Surfshark-Datei |
| T6 | Danach *Surfshark-Server wählen* | Liste aller Surfshark-Server (Deutschland zuerst, mit Auslastung) – ohne Anmeldung | Serverliste |
| T7 | „Schnellwahl: Deutschland …“ oder einen Server wählen | Meldung „VPN: … wird verbunden“, Schild grün; in „Standorte“ bleibt die Datei, dazu genau ein gewählter Surfshark-Standort | 1 Klick |
| T8 | Anmelden mit E-Mail/Passwort (optional) | Entweder Serverliste oder klare Meldung „Cloudflare-Schutz“ – nie ohne Rückmeldung | Anmeldung |

## 10. Neu in 3.9 (Durchgang 1: Stabilität, Tempo, Design, Sicherheit)

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| D1 | *Darstellung → Große Schrift* | Alle Texte etwa 15 % größer, nichts abgeschnitten | Lesbarkeit |
| D2 | *Darstellung → Effekte reduzieren* | Flächen ohne Glanz/Rand, Hintergrund ohne Farbflächen und Filmbilder; Bedienung wie vorher | Effekte |
| D3 | *Darstellung → Akzentfarbe* „Gelb-Rot“ | Rot als Akzent, Gelb als Partnerfarbe (wie das Icon) | Farbe |
| D4 | Leere Gruppe bzw. „Aufnahmen“ ohne Einträge | Hinweis, was als Nächstes hilft | Leerzustände |
| D5 | *Sicherheit & VPN → Verbindung prüfen* (mit und ohne VPN) | „Öffentliche IP: … – über das VPN (…)“ bzw. „– ohne VPN …“ | IP-Prüfung |
| D6 | *Fehlerprotokoll* anzeigen bzw. aufs Handy holen | Keine Benutzernamen, Passwörter, Schlüssel, E-Mail-Adressen (***) | Schwärzung |
| D7 | Sicherung erstellen: Kennwort tippen | Anzeige schwach/mittel/stark | Kennwort |
| D8 | Lange Wiedergabe mit ExoPlayer; Bild friert ein, Ton läuft weiter | Nach ca. 12 s verbindet der Player selbst neu (Protokoll: „Bild steht …“) | Hänger |

## 11. Neu in 3.10 (Durchgang 2: Innovation)

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| I1 | Live-Sender mit Archiv, OK → „10 Min zurück“ (zweimal) | Archiv ab jetzt −10 bzw. −20 min; „Zurück zu Live“ springt ins Live-Bild | Rückspulen |
| I2 | Sender ohne Archiv → „10 Min zurück“ | Hinweis „Für diesen Sender ist kein Archiv verfügbar“ | Rückspulen |
| I3 | Gefolgte Liga, laufendes Spiel auf dem Sender schauen, OK | Oben neben der Uhr „Heim 2:1 Gast  67'“ | Live-Ergebnis |
| I4 | Während des Spiels fällt ein Tor (Overlay zu) | Oben links 10 s „TOR! Heim 2:1 Gast“ | Tor-Einblendung |
| I5 | Suche öffnen → Mikrofon-Knopf → sprechen | Gesprochener Text steht im Suchfeld, Treffer erscheinen; ohne Spracherkennung kein Knopf | Sprachsuche |
| I6 | Einen Sender ≥ 2 min schauen, dann Live-TV → „Oft gesehen“ | Sender steht in der Liste (abends: abends Geschautes oben); „Verlauf löschen“ leert sie | Oft gesehen |
| I7 | *Aufnahmen → Automatisch aufnehmen* „Galatasaray“ hinzufügen | Meldung mit Anzahl eingeplanter Sendungen (nächste 24 h); geplante Aufnahmen in der Liste | Automatik |
| I8 | Eine automatisch geplante Aufnahme löschen, App neu starten | Sie wird nicht erneut eingeplant | Automatik |
| I9 | Im Player OK → „Bild im Bild“ (Fernseher) | Sender groß, kleine Kachel unten rechts; OK auf klein = Sender wählen; Steuerkreuz wechselt Ton/Fokus; Menü-Taste = Raster | Bild im Bild |
| I10 | In einer Senderliste schnell hoch/runter zappen | Wechsel spürbar schneller als vorher, nur eine Verbindung zum Anbieter | Vorladen |

## 12. Neu in 3.11 – Alle Zugänge aktualisieren, dezente Anzeige

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| U1 | Mind. 2 Zugänge: *Einstellungen → Aktualisieren & EPG → Alle Zugänge & EPG aktualisieren* | Unten rechts kleine einzeilige Anzeige „1/2 · Name: …“, danach „2/2 · …“, am Ende „Alle 2 Zugänge aktualisiert“ (4 s) | Alle Zugänge |
| U2 | Dasselbe über das Konto-Symbol oben rechts → „Alle Zugänge & EPG aktualisieren“ | Dialog schließt, Aktualisierung läuft im Hintergrund | Schnellzugriff |
| U3 | Während der Aktualisierung Sender/Filme bedienen und abspielen | Anzeige verdeckt nichts Wichtiges, ist nicht fokussierbar, Bedienung normal | Dezent |
| U4 | Einen Zugang mit falschem Passwort, dann U1 | Übrige Zugänge werden aktualisiert; rote Meldung „1 von 2 aktualisiert, Fehler bei: …“ (8 s) | Fehler |
| U5 | Handy: U1 | Kleine Anzeige über der unteren Leiste, oben bleibt frei | Handy |

## 13. Neu in 3.12 – Sender automatisch finden, alle Top-Ligen und Events

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| S1 | *Sport → Ligen wählen* | Abschnitte nach Sportart (Fußball, Kampfsport, Motorsport, US-Sport, Tennis, Weitere), echte Namen | Ligen |
| S2 | Sport-Übersicht mit Spielen der nächsten 1–3 Tage (Bundesliga, Premier League, Süper Lig) | Spiele stehen mit dem Sender aus deinem Zugang da, ohne Zuordnung von Hand | Automatisch |
| S3 | Spiel auf einem Sender, den dein Zugang nicht hat | Unter „Ohne Sender in deinem Zugang“: „läuft auf … – nicht in deinem Zugang“ | Hinweis |
| S4 | Spiel ohne jede Angabe | „Sender unbekannt“ – kein geratener Sender | Kein Raten |
| S5 | 15 min vor Anstoß eines Spiels (Xtream-Zugang) | Sender bleibt bzw. wird still korrigiert; Protokoll ggf. „Kontrolle vor Anstoß: … angepasst“ | Kontrolle |
| S6 | MotoGP, Darts, EuroLeague o. Ä. folgen | Live-Übertragungen erscheinen mit Sender, keine Wiederholungen/Magazine | TV-Events |
| S7 | *Sicherheit & VPN → Sport-Programm aus dem Internet* aus | Keine Abrufe von epgshare01 mehr (Protokoll), Zuordnung wie vorher über EPG/Sendernamen | Schalter |
| S8 | *Sport → Sender korrigieren* | Liga-Namen statt „bsd:5“/„espn:…“; Ersatz-Sender gilt nur, wenn sonst nichts gefunden wird | Korrektur |

## 14. Neu in 3.12.1 – Event-Sender vor Anstoß, Übersicht nach Sportart

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| P1 | Xtream-Zugang, Spiel einer gefolgten Liga, dessen Event-Sender erst kurz vorher umbenannt wird (z. B. DAZN/beIN-Events); App 20 min vor Anstoß offen lassen | Spätestens ca. 13 min vor Anstoß steht das Spiel mit dem Event-Sender da; Protokoll „Event-Gruppen vor Anstoß: … umbenannt …“ und „Neu zugeordnet vor Anstoß …“ | Gruppen neu abfragen |
| P2 | Wie P1, im Protokoll die Zahl der Abrufe ansehen | Je Zugang höchstens eine Abfrage-Runde je 10 min, auch wenn mehrere Spiele gleichzeitig beginnen | Schonend |
| P3 | Nach P1 Suche „Galatasaray“ (bzw. Teamname aus dem Event-Sender) | Der umbenannte Event-Sender wird gefunden | Suchindex |
| P4 | Sport-Übersicht mit Fußball und z. B. UFC oder Formel 1 | Unter „Heute“ usw. Unterüberschriften „Fußball“, „Kampfsport“ …; Fokus beim Öffnen auf dem obersten Spiel | Nach Sportart |
| P5 | Nur Fußball-Ligen gefolgt | Keine Unterüberschriften (wie bisher) | Nach Sportart |
| P6 | *Einstellungen → Sport → Bzzoiro-API-Schlüssel* eintragen | Meldung „Bzzoiro verbunden – … Ligen umgestellt, Trendyol Super Lig dazu“; unter *Ligen wählen* Süper Lig gewählt | Bzzoiro |
| P7 | Pokal- oder Bundesliga-Abend mit mehreren gleichzeitigen Spielen auf Sky | Jedes Spiel auf seinem Sky-Kanal (Sky Sport 3, 4, 5 …), keins auf „Sky Sport Top Event“/„Mix“/„Sky Go“ | Sender je Spiel |

## 15. Neu in 3.13 – Stabilität, Speicher, Sicherheit

**Vor dem Update:** Die Datenbank wird umgestellt (Version 8, EPG mit Generationen). Das vorhandene EPG bleibt sichtbar.
Sicherheitshalber vorher *Speicher & Sicherung → Sicherung erstellen*.

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| Q1 | Update über 3.12.x installieren, App öffnen | Zugänge, Favoriten, EPG wie vorher; TV-Guide zeigt Sendungen | DB-Umstellung |
| Q2 | *Aktualisieren & EPG → EPG neu laden*, währenddessen TV-Guide und Senderliste bedienen | EPG bleibt die ganze Zeit sichtbar, kein Springen; danach neue Sendungen | EPG-Generationen |
| Q3 | Während „Alle Zugänge aktualisieren“ suchen (z. B. „zdf“) | Treffer kommen sofort (früher langsam während der Aktualisierung) | Suchindex |
| Q4 | Gesperrte Gruppe: 5× falsche PIN, App über *Einstellungen → Apps → Beenden* schließen, neu öffnen | PIN weiter gesperrt (Restzeit) | PIN-Sperre |
| Q5 | Schnell durch 15 Sender zappen, dann „Zuletzt gesehen“ | Nur Sender, auf denen du mindestens 3 s warst | Zappen |
| Q6 | Aufnahme eines HLS-Senders (falls dein Anbieter verschlüsselt: „#EXT-X-KEY“) | Aufnahme lässt sich abspielen; bei DRM klare Meldung „verschlüsselt … nicht möglich“ | Aufnahmen |
| Q7 | Fernseher über Nacht im Standby mit WLAN/LAN lassen, morgens Fehlerprotokoll | Einträge „Nacht: EPG wird erneuert …“ bzw. „Datenbank verkleinert …“ (nur wenn fällig) | Nachtpflege |
| Q8 | Schwacher Stick (1 GB): lange durch Film-Raster scrollen | Kein Neustart der App, Poster laden weiter | Bildspeicher |
| Q9 | Sport öffnen direkt nach dem Start | „Wird geladen …“, dann Liste von oben; Fokus beim Betreten auf dem obersten Spiel | Sport-Fokus |
| Q10 | Debug-APK: ein paar Minuten bedienen, Fehlerprotokoll | ggf. Zeilen „StrictMode: …“ – bitte schicken (Hinweise auf Hänger) | StrictMode |
| Q11 | Kaltstart (App ganz beenden, öffnen) – zweimal nach der Installation | „Start bis Startbildschirm: … ms“ im Protokoll; ab dem zweiten Start schneller als 3.12 (Baseline Profile) | Startzeit |

## 15a. Neu in 3.14 – Bibliotheken aktuell (Media3 1.11.1, OkHttp 5, Coil 3)

**Vergleich:** Die 3.13-APK aufheben. Wenn etwas schlechter wirkt, 3.13 und 3.14 am selben Gerät vergleichen
(Fehlerprotokoll „Start bis Startbildschirm“ und gefühlte Umschaltzeit).

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| L1 | Update über 3.13 installieren, App öffnen | Zugänge, Favoriten, EPG wie vorher | Update |
| L2 | Live-Sender (TS) und HLS-Sender starten, 10× zappen | Bild und Ton wie bisher, kein Absturz | Media3 1.11.1 |
| L3 | Sender oder Film mit AC3/E-AC3/DTS-Ton (z. B. Sky-Sport, Film mit 5.1) | Ton läuft; im Fehlerprotokoll kein „NoSuchMethodError“/„AbstractMethodError“ | FFmpeg-Decoder |
| L4 | Film starten, spulen, Tonspur und Untertitel wechseln, beenden, fortsetzen | Position stimmt, Spuren wechseln | VOD |
| L5 | Bild im Bild (Handy) | Wie bisher | Android-PiP |
| L6 | Durch Film-Raster und Senderliste scrollen, dann *Speicher & Sicherung → Bild-Cache leeren* | Poster/Logos laden (beim ersten Start nach dem Update einmal neu), nach dem Leeren wieder | Coil 3 |
| L7 | Schwacher Stick (1 GB): 30 min Live + Raster-Scrollen | Kein Neustart der App | Speicher |
| L8 | Zugang aktualisieren, EPG neu laden, Sport öffnen, VPN an/aus | Wie bisher | OkHttp 5 |
| L9 | Sender mit LibVLC (Player-Wahl oder automatischer Rückfall) | Bild und Ton | LibVLC 3.7.7 |

## 15b. Neu in 3.14.1 – VPN-Korrektur

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| V1 | Update über 3.14 installieren, App öffnen | VPN verbindet; im Protokoll keine 5× „Start fehlgeschlagen: IllegalStateException“ | Hintergrundstart |
| V2 | VPN unter *Sicherheit & VPN* aus- und wieder einschalten, Standort wechseln | Kein Absturz | WireGuard 1.0.20250531 |
| V3 | Fernseher aus (Standby) und wieder an, App öffnen | Kein Absturz, VPN steht nach wenigen Sekunden | VpnService-Ende |
| V4 | Bei VPN-Abbrüchen: Fehlerprotokoll schicken | Zeile „Abbruch erkannt: …“ vor jedem „Getrennt“ | Diagnose |
| V5 | Nur falls genutzt: Android-Einstellung „Durchgehend aktives VPN“ für GuevenPlayer an, Fernseher neu starten, App nicht öffnen, nach 1 min öffnen | VPN steht bereits (grünes Schild) | Always-on |

## 15c. Neu in 3.15 – Wiedergabe-Wächter

Ziel: Problem-Sender gezielt prüfen. Bei jedem Test, der nicht wie erwartet läuft, bitte das Fehlerprotokoll
schicken (Zeilen beginnen mit „Wächter:“).

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| W1 | 30 min normal Live-TV und einen Film schauen | Kein Hinweis, kein Umschalten, kein Neuverbinden | Keine Fehlalarme |
| W2 | Sender, der oft „ewig lädt“ | Nach 3 s (mit 2. Quelle) bzw. 12 s: andere Quelle oder Neuverbinden, Hinweis oben | S1 |
| W3 | Sender, der zwischendurch hängen bleibt | Nach 3 s (mit 2. Quelle) bzw. 20 s: Quelle/Live-Rand/Neuverbinden | S3 |
| W4 | Sender mit Standbild bei laufendem Ton | Nach 8 s: Bildspur neu, danach neu verbinden, sicherer Decoder, LibVLC | S4 |
| W5 | Sender „Ton ohne Bild“ (z. B. HEVC/4K auf schwachem Stick) | Nach 6 s Stufen bis LibVLC; beim nächsten Öffnen startet der Sender gleich passend | S5, Gedächtnis |
| W6 | Sender „Bild ohne Ton“ (AC3/EAC3, Passthrough an) | Ton selbst dekodieren, Ton kommt | S6 |
| W7 | Radiosender | Nur Hinweis „Nur Ton“, kein Neuverbinden | S5i |
| W8 | WLAN 30 s trennen und wieder verbinden | „Warte auf Netzwerk“, danach automatisch weiter, kein Fehlerbildschirm | S15, Netzwarten |
| W9 | Anbieter komplett offline (z. B. falscher Server) | Nach wenigen Versuchen klare Meldung + „Anderen Zugang“, kein endloser Spinner | Budget, Anbieter-Sperre |
| W10 | Schnell durch 15 Sender zappen | Kein Eingriff auf alten Sendern, kein Absturz | Sitzungswechsel |
| W11 | Live-Sender 1 h mit Pause/Weiter | Nach Weiter: zurück zum Live-Rand, wenn > 45 s zurück | S9 |
| W12 | *Einstellungen → Wiedergabe-Wächter* aus, W2–W5 wiederholen | Verhalten wie 3.14 | Rückweg |

## 15d. Neu in 3.16 – Bibliotheken und Werkzeuge aktuell

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| U1 | Update über 3.15 installieren, App öffnen | Zugänge, Favoriten, Einstellungen, EPG wie vorher | DataStore 1.3 |
| U2 | Fernseher über Nacht im Standby, morgens Fehlerprotokoll | Nächtliche EPG-Erneuerung läuft wie bisher | WorkManager 2.12 |
| U3 | VPN an/aus, Standort wechseln, Fernseher aus/an | Kein Absturz (WireGuard unverändert) | VPN |
| U4 | Kurzcheck (Abschnitt 16) auf Fire TV Cube und Handy | Alles wie in 3.15 | Gesamt |

## 15e. Neu in 3.17 – Stabilität, Android 17

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| V1 | VPN verbinden, trennen, Standort wechseln – auf Fire TV (Android 9) und Handy | Kein Absturz beim Trennen | WireGuard 1.0.20260102 |
| V2 | Sender: Overlay, Spulen, „Mehr“, Panels, Zurück, Ziffern, Senderliste/Verlauf (Fernseher) | Alles wie in 3.16 | Player-Aufteilung |
| V3 | Sender mit Störung (Netz kurz trennen) | „Warte auf Netzwerk“, danach selbst neu verbunden | Wächter-Aufteilung |
| V4 | Handy mit Android 17: QR-Einrichtung bzw. „Übertragen“ öffnen | Frage „Geräte in der Nähe“; erlaubt: Handy erreicht die Seite | ACCESS_LOCAL_NETWORK |
| V5 | Wie V4, aber ablehnen | Hinweis, App läuft weiter; Eingabe am Gerät möglich | Ablehnung |
| V6 | Zugang mit Server im Heimnetz (192.168.…) auf Android 17 | Einmalige Frage beim Start, danach Wiedergabe | LAN-Server |
| V7 | Android 16 oder älter: QR-Einrichtung | Keine neue Frage, alles wie bisher | Abwärtskompatibel |
| V8 | Einstellungen durchblättern (alle Seiten, Akzentfarbe, Schalter) | Wie in 3.16 | Einstellungs-Aufteilung |

## 15f. Neu in 3.18 – Sicherheit und Stabilität

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| W1 | Sicherung erstellen mit 7 Zeichen | Hinweis „mindestens 8 Zeichen“ | Kennwortlänge |
| W2 | Sicherung (8+ Zeichen) erstellen, aufs Handy laden, am Fernseher wiederherstellen | Klappt; Wiederherstellen dauert wenige Sekunden | Format 2 |
| W3 | Alte Sicherung aus 3.17 wiederherstellen | Klappt mit dem alten Kennwort | Format 1 lesbar |
| W4 | Falsches Kennwort beim Wiederherstellen | „Falsches Kennwort“, erneut eingeben möglich | Fehlerfall |
| W5 | QR-Einrichtung, VPN einrichten, Protokoll teilen vom Handy | Seiten funktionieren wie bisher | Schutz-Kopfzeilen |
| W6 | „Sicherung erstellen“ offen, am Handy die Adresse von /backup auf /log ändern | „Nicht gefunden“ | Protokoll nur im Protokoll-Modus |
| W7 | Suche/Textfeld am Fernseher schnell öffnen und mit Zurück verlassen | Kein Absturz | Fokus |

## 15g. Neu in 3.19 – Auswertung des Fehlerprotokolls

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| X1 | VPN verbinden, App über Nacht offen lassen, am Morgen Protokoll ansehen | Keine bzw. nur einzelne „Getrennt/Wieder verbunden“-Paare (vorher ~100 pro Nacht) | Prüfpaket statt Abriss |
| X2 | VPN verbinden, Fernseher in Standby, nach 30 min aufwecken | Tunnel steht weiter, kein Abbruch im Protokoll | Standby-Erkennung |
| X3 | VPN verbinden, dann im Router das Internet trennen, Stream starten | Innerhalb ~20 s „Abbruch erkannt“, danach Neuverbindung | Echter Abbruch |
| X4 | Mehrere Sender zappen, dabei auf Ton achten | Kein kurzes Ton-Umschalten mehr nach Senderwechsel | S6 ohne Fehlalarm |
| X5 | Sender mit langsamem Start wählen | Kein Quellwechsel nach 3 s; bei echtem Dauerpuffern weiter Eingriff | S3-Schwelle |
| X6 | App neu starten und Zeit bis zum Startbildschirm beobachten | Deutlich schneller als in 3.18; Protokollwert plausibel | Startpfad |
| X7 | App aus dem Hintergrund (VPN läuft) erneut öffnen | Protokoll: „Oberfläche bis Startbildschirm: … (Prozess lief schon … min)“ | Messwert |
| X8 | Aktualisieren, ohne dass der Anbieter die Liste geändert hat | Protokoll: „Suchindex unverändert – kein Neuaufbau“, Suche funktioniert | Index-Fingerabdruck |
| X9 | Nach Umbenennungen (Sport-Gruppen) suchen | Umbenannte Sender werden gefunden | Index nach Umbenennung |

## 15h. Neu in 3.19.1 – Panels am Handy

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| Y1 | Handy: Player öffnen, „Mehr“ antippen, dann links neben das Panel tippen | Panel schließt sofort | Tipp daneben |
| Y2 | Handy: „Mehr“ öffnen, oben rechts auf „×“ tippen | Panel schließt | Schließen-Knopf |
| Y3 | Handy: „Mehr“ öffnen, in der Liste scrollen und zwischen zwei Einträge tippen | Panel bleibt offen | Tipp im Panel |
| Y4 | Handy: „Ton & Untertitel“ öffnen (Hoch- und Querformat) | Panel passt auf den Bildschirm, „×“ sichtbar | Breite |
| Y5 | Handy: Panel öffnen, 30 s nichts tun | Panel bleibt (erst Tipp oder „×“ schließt) | Kein Zeitausblenden |
| Y6 | Fernseher: alle Panels mit OK öffnen und mit Zurück schließen | Wie bisher, kein „×“ zu sehen | TV unverändert |

## 15i. Neu in 3.20 – Beschreibung als Spoiler (Darstellung ab 3.21 geändert, siehe 15j)

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| Z1 | TMDB-Titel von der Startseite öffnen (Fernseher) | Statt der Handlung Balken + „Beschreibung anzeigen“ | Zugeklappt |
| Z2 | Mit OK aufklappen, erneut OK | Voller Text, danach wieder zugeklappt | Umschalten |
| Z3 | Film- und Seriendetails öffnen (Fernseher und Handy) | Gleiches Verhalten an allen vier Stellen | Alle Ansichten |
| Z4 | Titel mit sehr kurzer Beschreibung öffnen | Text steht direkt da, kein Aufklappen nötig | Kurze Texte |
| Z5 | Aufklappen, zu einem anderen Titel wechseln und zurück | Neuer Titel beginnt zugeklappt | Zustand je Titel |
| Z6 | Handy: aufklappen, Gerät drehen | Bleibt aufgeklappt | Zustand übersteht Drehen |

## 15p. Neu in 3.27 – Zugang bearbeiten

- [ ] P1 Einstellungen → Zugänge → „Bearbeiten (Name)“: Formular ist ausgefüllt, das Passwort-Feld ist leer.
- [ ] P2 Nur den Namen ändern → „Prüfen & speichern“: Name geändert, nichts lädt neu, Favoriten und Verlauf sind noch da.
- [ ] P3 Falsches Passwort eingeben → Meldung „Anmeldung abgelehnt …“, die alten Daten bleiben; „Trotzdem speichern“ erscheint.
- [ ] P4 Richtiges neues Passwort (oder neue M3U-Adresse) → gespeichert, Inhalte und EPG laden neu, Favoriten bleiben.
- [ ] P5 Mit gesetzter PIN: Bearbeiten fragt zuerst nach der PIN.
- [ ] P6 QR-Code im Bearbeiten-Bildschirm: Daten vom Handy landen im Formular (gespeichert wird erst mit „Prüfen & speichern“).

## 16d. Neu in 3.44 – Ordnung

- [ ] G1 „Alle Zugänge“ → Filme/Serien/Live: alle Gruppen im Format „Land · Rubrik“, keine „|DE| ✪“-Namen mehr (nach dem automatischen Neuaufbau).
- [ ] G2 Gruppenreihenfolge: eigenes Land oben, darin Neuheiten, Vollprogramm, Unterhaltung, Nachrichten, Sport …, Erwachsene ganz unten.
- [ ] G3 Keine Farbpunkte mehr vor Gruppennamen.
- [ ] G4 Einstellungen → Inhalte → „Reihenfolge in Gruppen“: Automatisch → Wie beim Anbieter → A–Z; Senderliste, Filme und Serien ordnen sich sofort bzw. nach wenigen Sekunden neu.
- [ ] G5 A–Z: „Das Erste“ vor „ProSieben“ vor „ZDF“ trotz „DE:“-Vorsatz; „Sky Sport 2“ vor „Sky Sport 10“; Umschalten im Vollbild folgt der Liste.
- [ ] G6 Einzelner Zugang mit „Automatisch“: Reihenfolge wie vorher beim Anbieter.

## 16c. Neu in 3.43 – lange Namen

- [ ] L1 Filme/Serien/Live → Gruppenliste: lange Gruppennamen stehen zweizeilig und sind ohne Fokus lesbar.
- [ ] L2 Sender mit langem Namen fokussieren: nach ca. 1 s läuft der Name durch; Fokus weg → wieder „…“.
- [ ] L3 Poster mit langem Titel fokussieren: Titel unter dem Poster läuft durch.
- [ ] L4 Schnell durch eine Liste blättern: nichts läuft unruhig mit (erst nach kurzer Pause).

## 16b. Neu in 3.42 – Bedienkomfort

- [ ] K1 Live → Favoriten leer: Knopf „Alle Sender zeigen“ wechselt zu „Alle“; Filme/Serien → Favoriten leer: „Alle anzeigen“.
- [ ] K2 Gruppenliste: Sport-, Nachrichten-, Film-, Kinder-Gruppen mit passendem Farbpunkt; „Alle“/„Favoriten“ ohne Punkt.
- [ ] K3 Suche: Begriff eingeben, 2 s warten, Suche verlassen und wieder öffnen → Begriff steht unter „Zuletzt gesucht“; Chip sucht erneut.
- [ ] K4 „Verlauf löschen“ leert die Liste dauerhaft (auch nach Neustart).
- [ ] K5 TV-Guide beim ersten Öffnen: pulsierendes Raster bis die Sender da sind, kein kurzes „Keine Sender“.

## 16a. Neu in 3.41 – Oberfläche

- [ ] O1 Startseite: Fokus in den Inhalt → Schriftzug blendet aus, andere Bereiche gedimmt; „Hoch“ holt die Leiste wieder nach vorn.
- [ ] O2 Leiste: links/rechts blättern und Verweilen öffnet weiterhin den Bereich; OK springt in den Inhalt.
- [ ] O3 „Effekte reduzieren“: Leiste ohne Lichtrand/Schatten, Einziehen funktioniert trotzdem.
- [ ] O4 OK gedrückt halten auf Sender, Film, Serie, Gruppe, Guide-Sendung und Startseiten-Karte: Menü erscheint, erster Eintrag hat den Fokus, Zurück schließt es.
- [ ] O5 Menü-Einträge wirken: Favorit an/aus (Beschriftung passt zum Zustand), Mosaik ab hier, Aufnehmen/Aufnahme entfernen, Oben anheften.
- [ ] O6 Startseite am Fernseher: „Läuft gerade“ zeigt nacheinander kurz live, dann Standbilder; kein Ton.
- [ ] O7 Zugang mit nur einer Verbindung: „Läuft gerade“ ohne „Zu viele Verbindungen“; Sender öffnen funktioniert sofort.
- [ ] O8 „Läuft gerade“ aus dem Bild scrollen oder Player öffnen: Anbieter zeigt 0 aktive Verbindungen der Zeile.
- [ ] O9 Poster überall 2:3 mit gleichen Ecken; Sender-Kacheln 16:9.

## 15z. Neu in 3.40 – Ambient-Licht und Live-Mosaik

- [ ] A1 Senderliste, Vorschau läuft: Hintergrund nimmt nach 1–2 s die Farben des Bildes an, Übergang ohne Flackern.
- [ ] A2 Schwarzbild/Senderwechsel: Hintergrund kehrt sanft zu den Akzentfarben zurück, kein Sprung.
- [ ] A3 „Effekte reduzieren“ an: kein Ambient-Licht, Hintergrund ruhig wie bisher.
- [ ] A4 Heller Modus: Schrift auf den Glasflächen bleibt bei grellen Bildern lesbar.
- [ ] A5 Vollbild öffnen und zurück: Ambient-Licht läuft weiter, kein Ruckeln beim Durchblättern der Liste.
- [ ] M1 Senderliste → Mosaik: 3×3 (Fire TV Stick Lite: 2×2) ab dem markierten Sender; Kacheln werden nacheinander kurz live, dann Standbild.
- [ ] M2 Zugang mit nur einer Verbindung: Mosaik läuft ohne „Zu viele Verbindungen“.
- [ ] M3 Umschalter 2×2 ↔ 3×3, OK auf einer Kachel öffnet den Sender im Vollbild, Zurück führt ins Mosaik.
- [ ] M4 Kein Ton aus dem Mosaik; Sender ohne Bild zeigen Logo und „Kein Bild“.
- [ ] M5 App in den Hintergrund: Mosaik schließt die Verbindung (Anbieter-Statusseite: 0 aktive Verbindungen).

## 15y. Neu in 3.39 – Dachmarke + 4K

- [ ] T1 TRT 1 → Mehr → Quelle: keine „TRT UHD“/„TRT 4K“-Quelle; TRT 2 ebenso.
- [ ] T2 „TRT UHD“/„TRT 4K“ steht als eigener Sender in der Liste.
- [ ] T3 TRT 1 UHD, ZDF UHD, ProSieben UHD erscheinen weiter als Quellen ihres Senders.

## 15x. Neu in 3.38 – Umschalten zum nächsten Sender

- [ ] U1 Vollbild in „Alle Sender“: runter/hoch schaltet zum nächsten anderen Sender, nie zur anderen Qualität.
- [ ] U2 Dasselbe in „Zuletzt gesehen“ und „Oft gesehen“ (vorher ProSieben HD/UHD/4K einzeln).
- [ ] U3 Sender aus der Suche in einer anderen Qualität starten → umschalten geht zum Nachbarn in der Liste, nicht an den Anfang.
- [ ] U4 Protokoll nach dem Update: „… Quellen gleicher Sender zusammengefasst (Zugang …)“.

## 15w. Neu in 3.37 – eine Zuordnung für die ganze App

- [ ] W1 Nach dem Update: Protokoll zeigt „Aufgebaut (Neue Zuordnungsregeln)“ ohne eigenes Aktualisieren.
- [ ] W2 TRT 2 (einzelner Zugang und „Alle Zugänge“) → Mehr → Quelle: nur TRT-2-Quellen, kein TRT 1.
- [ ] W3 ProSieben → Mehr → Quelle: keine MAXX-Quelle; ProSieben MAXX → keine ProSieben-Quelle.
- [ ] W4 Quellen-Liste zeigt je Quelle Originalnamen, Qualität mit Erklärung („Full HD (1080p)“) und Zugang.
- [ ] W5 Favorit auf TRT 2 setzen → im anderen Zugang ist TRT 2 Favorit, TRT 1 nicht.

## 15v. Neu in 3.36 – Zuordnung an echten Daten, Stabilität

- [ ] X1 Pro 7 / ProSieben (alle Qualitäten) und ProSieben MAXX sind zwei Einträge; Pro 7 UHD hängt bei ProSieben.
- [ ] X2 Sender mit Zusatz (ZDF/ZDFneo, RTL/RTL Nitro, Sport1/Sport1+, Sky Cinema/Sky Cinema Action) getrennt.
- [ ] X3 Gleiche Sender verschiedener Zugänge (Das Erste/ARD, RTL II/RTL ZWEI, n-tv-Qualitäten) zusammen.
- [ ] X4 Protokollzeile „Aufgebaut …“: Zahl „durch das laufende Programm bestätigt“ notieren.
- [ ] X5 WLAN während „Alle Zugänge aktualisieren“ kurz trennen → „Verbindung abgerissen – neuer Versuch …“, danach fertig.
- [ ] X6 Sport-Übersicht direkt nach dem Aktualisieren: Spiele haben Sender (Protokoll: „… neu zugeordnet, davon … mit Sender“).
- [ ] X7 VPN auf Fire TV und Handy gleichzeitig mit derselben Sicherung → Protokoll nennt „derselbe VPN-Schlüssel …“.

## 15u. Neu in 3.35 – Internetabgleich der Zuordnung

- [ ] Y1 TRT 1, TRT 2, TRT 3 (und Sky Sport 1/2, Eurosport 1/2) erscheinen in „Alle Zugänge“ als getrennte Sender.
- [ ] Y2 „Neu zuordnen“: Meldung „Katalog aktuell (… Sender)“; Fußzeile „Zuletzt abgeglichen: <heute>“.
- [ ] Y3 Ohne Internet „Neu zuordnen“: Meldung „Internetabgleich nicht möglich …“, Zuordnung läuft trotzdem.
- [ ] Y4 Fehlerprotokoll: „Senderkatalog aus dem Internet aktualisiert: … Sender (iptv-org)“.
- [ ] Y5 Weitere Fehlzuordnungen notieren (Sendername + Zugang) – sie gehören als Testfall in `AutoMatchTest`.

## 15t. Neu in 3.34 – vollautomatische Zuordnung

- [ ] Z1 Einstellungen → Zugänge → Alle Zugänge: Es gibt keine „Zuordnung prüfen“ mehr; „Automatische Zuordnung“ zeigt Zahlen.
- [ ] Z2 n-tv (bzw. ein Sender, den jeder Anbieter in mehreren Qualitäten hat) erscheint in „Alle Zugänge“ genau einmal, alle Qualitäten als Quellen.
- [ ] Z3 ZDF und ZDFneo, ARD und ARD alpha, RTL und RTL II, Sky Sport 1 und Sky Sport 2 bleiben getrennte Sender.
- [ ] Z4 Türkische/arabische Sender mit gleichem Namen wie deutsche (NTV, Kanal …) laufen nicht mit den deutschen zusammen.
- [ ] Z5 „Neu zuordnen“ drücken: Nach wenigen Sekunden steht ein neuer Stand mit Uhrzeit; die App bleibt bedienbar.
- [ ] Z6 Fehlerprotokoll: Zeile „Aufgebaut … Quellen über den Senderkatalog erkannt, … Verwechslungen verhindert“.
- [ ] Z7 Fire TV Stick (wenig RAM): Aufbau läuft durch, keine Meldung „Zu wenig Arbeitsspeicher“ im Protokoll.

## 15s. Neu in 3.33 – Design im Apple-TV-Stil

- [ ] D1 Filme: Mit dem Steuerkreuz über die Poster – das fokussierte Poster ist aus 3 m eindeutig zu erkennen (Anheben + Schatten).
- [ ] D2 Fire TV Stick (Effekte reduziert): fokussiertes Poster hat einen hellen Rand, Scrollen bleibt flüssig.
- [ ] D3 Hell und dunkel: Nebenschrift (Beschreibungen, Zeiten) überall gut lesbar, auch über Hintergrundbildern.
- [ ] D4 Player: Overlay blendet weich ein; OK auf Pause wirkt sofort; Runter erreicht Zeitleiste (Film) und Reiter.
- [ ] D5 Film-Zeitleiste: im Fokus dicker mit Knopf, Links/Rechts verschiebt ihn, OK springt.
- [ ] D6 Filmdetails: Titel und Knöpfe unten, nichts abgeschnitten (auch mit großer Schrift).

## 15r. Neu in 3.32 – Einstellungen neu aufgebaut

- [ ] E1 Update über 3.31: Hatte die EPG-Zeitverschiebung vorher einen Wert (z. B. +1 h), steht er jetzt bei jedem Zugang
  (Zugänge → Zugang → EPG-Zeitverschiebung). Sendezeiten in Liste, Guide und Player unverändert.
- [ ] E2 Fernseher: In der Bereichsliste mit Runter durch alle 7 Bereiche – rechts wechselt der Inhalt sofort, unter jedem
  Bereich steht sein Zustand (z. B. „VPN aus · keine PIN“).
- [ ] E3 Zugänge → OK auf einem Zugang → Seite des Zugangs. Zurück: Fokus steht wieder auf derselben Karte. Noch einmal
  Zurück: Fokus in der Bereichsliste. Noch einmal: Einstellungen verlassen.
- [ ] E4 EPG-Zeitverschiebung nur bei einem Zugang ändern: nur dessen Sendezeiten verschieben sich (auch in „Alle Zugänge“).
- [ ] E5 Länder & Sprachen auf der Seite eines nicht aktiven Zugangs: Die Auswahl gilt für diesen Zugang, nicht für den aktiven.
- [ ] E6 Zugang löschen: Rückfrage erscheint, „Abbrechen“ lässt ihn stehen; „Löschen“ entfernt ihn, die Seite schließt sich.
- [ ] E7 Wiedergabe: „Automatisch optimieren“ aus → „Bild & Ton → Erweitert“ erscheint, darin Puffer, Decoder, Tunneling, Dolby/DTS.
- [ ] E8 Handy: Einstellungen zeigt das Menü; Bereich antippen → Seite mit Zurück-Pfeil; Zurück-Geste führt Ebene für Ebene zurück.
- [ ] E9 Sicherung aus 3.31 wiederherstellen: Zugänge kommen zurück, die alte gemeinsame EPG-Verschiebung gilt für alle.
- [ ] E10 Hell und dunkel, große Schrift an: alle Seiten lesbar, nichts abgeschnitten (besonders die Zugangs-Karten).

## 15q. Neu in 3.28–3.31 – „Alle Zugänge“

- [ ] Q1 Mit zwei Zugängen: Im Zugangswechsel steht „Alle Zugänge“ (∑) ganz oben. Wählen → Live-TV zeigt Sender beider Zugänge, jeden einmal.
- [ ] Q2 Ein Sender, den es in beiden Zugängen gibt: Player-Menü „Quelle“ zeigt alle Quellen (beide Zugänge), die beste läuft.
- [ ] Q3 Zugang A ist belegt (1/1): Der Sender startet automatisch über Zugang B, ohne Nachfrage.
- [ ] Q4 Quelle in Zugang A ausgefallen (z. B. Sender beim Anbieter kaputt): Nach ≤ 3 s läuft Zugang B, Hinweis „wechsle automatisch zu …“.
- [ ] Q5 Film in beiden Zugängen: startet; nach Wechsel der Quelle (Menü „Quelle“) geht es an derselben Stelle weiter.
- [ ] Q6 Serie, bei der Zugang A Staffeln fehlen: In „Alle Zugänge“ sind die Staffeln aus Zugang B da und spielbar.
- [ ] Q7 Kategorien: „Deutschland · Sport“ statt zweimal Sport. Einstellung „Kategorien nach Zugang trennen“ → Gruppen je Zugang.
- [ ] Q8 „Automatisch zur nächsten Quelle“ aus → bei belegtem Zugang wird wie früher nachgefragt.
- [ ] Q9 Einen Zugang nicht einbeziehen → seine Inhalte verschwinden aus „Alle Zugänge“ (nach kurzem Neuaufbau).
- [ ] Q10 Favorit in „Alle Zugänge“ setzen → er ist auch im einzelnen Zugang Favorit (und umgekehrt).
- [ ] Q11 Film in „Alle Zugänge“ halb schauen → im einzelnen Zugang steht „Weiterschauen“ an derselben Stelle.
- [ ] Q12 EPG läuft in „Alle Zugänge“ (Jetzt-Läuft-Zeile, TV-Guide), Suche zeigt jeden Titel einmal.
- [ ] Q13 (ab 3.34 ersetzt durch §15t)
- [ ] Q14 Gesperrte Gruppe eines Zugangs: Ihre Inhalte tauchen in „Alle Zugänge“ nicht auf und werden nie als Quelle genommen.
- [ ] Q15 Zweiten Zugang löschen → „Alle Zugänge“ verschwindet, der verbliebene Zugang ist aktiv.

## 15o. Neu in 3.26 – Automatische Aktualisierung je Zugang

- [ ] O1 Update von 3.25 installieren: alle Zugänge sind noch da, „Automatisch aktualisieren“ steht bei allen auf „Aus“.
- [ ] O2 Einstellungen → Zugänge → „Automatisch aktualisieren (Name)“ → 1 eingeben → ✓: Zeile zeigt „jeden Tag“.
- [ ] O3 Zifferntasten der Fernbedienung funktionieren im Dialog; Löschen bis leer und ✓ → „Aus“.
- [ ] O4 Zugang, der vor über 1 Tag aktualisiert wurde, auf 1 stellen: Aktualisierung startet sofort (Fortschritt oben), EPG danach ebenfalls.
- [ ] O5 Gerät über Nacht ausschalten (Intervall 1 Tag): Nach dem Einschalten und Öffnen startet die Aktualisierung sofort.
- [ ] O6 App geschlossen lassen: Am nächsten Tag zeigt das Protokoll „Auto-Update: … aktualisiert (Hintergrund)“.
- [ ] O7 Ohne Internet fällig: Protokoll „fehlgeschlagen – neuer Versuch in 1 Stunde“, kein Dauer-Neuversuch.
- [ ] O8 Zweiter Zugang mit anderem Intervall: Wird nur nach seinem eigenen Countdown aktualisiert.

## 15n. Neu in 3.25 – Start, Aktualisieren, Effekte

- [ ] N1 Update von 3.24 installieren: alle Zugänge, Favoriten und Verlauf sind noch da.
- [ ] N2 Kaltstart: Fokus „Live TV“, Runter → „Alle Sender“; die Kategorien links sind sofort da.
- [ ] N3 Zugang aktualisieren: Fortschritt endet früher als bisher. Protokoll: „Import-Phasen: …“, kurz danach „Suchindex aufgebaut“.
- [ ] N4 Direkt nach dem Aktualisieren einen neuen Sender suchen: wird gefunden (Suche wartet höchstens 3 s).
- [ ] N5 Zugang direkt nach dem Aktualisieren löschen: kein Fehler; Suche zeigt danach keine Treffer aus ihm.
- [ ] N6 Cube: Kacheln und Listenzeilen leuchten und vergrößern sich wie bisher.
- [ ] N7 Einstellungen → „Effekte reduzieren“ an: kein Leuchtschatten, kein Lichtreflex;
  Fokus bleibt gut erkennbar. Wieder aus: alles wie vorher.

## 15m. Neu in 3.24 – Start, Player-Rückkehr, Datenbank

- [ ] M1 Update von 3.23 installieren: alle Zugänge, Favoriten und Verlauf sind noch da (Datenbank v9 → v10).
- [ ] M2 Kaltstart wie gewohnt: Fokus „Live TV“, Runter → „Alle Sender“.
- [ ] M3 Sender läuft → Home → innerhalb von 30 s zurück: Bild läuft weiter, kein Neuladen. Nach über 30 s: Sender startet neu.
- [ ] M4 Film läuft → Home → innerhalb von 30 s zurück: weiter an derselben Stelle.
- [ ] M5 Sport-Bereich füllt sich nach dem Start (wenige Sekunden später als bisher ist normal).
- [ ] M6 Nächtliche Pflege: Nach einem Tag Nutzung im Protokoll „Inhalt sichtbar“ vergleichen (sollte deutlich kürzer sein als am ersten Tag).
- [ ] M7 Senderwechsel zu einem Sender, der auch in einem zweiten Zugang vorkommt: „Quelle“ zeigt den anderen Zugang wie bisher.

## 15l. Neu in 3.23 – Pinning, Hauptthread, Fehlermeldungen

- [ ] L1 TMDB-Empfehlungen auf der Startseite laden wie vorher (gepinnter Host erreichbar).
- [ ] L2 Sport-Bereich lädt (Bzzoiro/Sportmonks gepinnt), VPN-Anmeldung bei Surfshark klappt.
- [ ] L3 Einstellungen → DNS über HTTPS an: kein Fehler im Protokoll („Verschlüsseltes DNS nicht erreichbar“ nur ohne Internet).
- [ ] L4 Kaltstart: Fokus auf „Live TV“, Runter → „Alle Sender“ (unverändert).
- [ ] L5 Während des Starts mehrfach Tasten drücken: kein „App reagiert nicht“.
- [ ] L6 Debug-APK: Protokoll enthält nach dem Start keine `[StrictMode]`-Zeilen zu VpnStore, ParentalControl, Sport, ChannelHabits, AutoRecorder.
- [ ] L7 Anbieter kurz nicht erreichbar/abgebrochen: Meldung „Antwort des Servers unvollständig oder beschädigt – später erneut versuchen“ bzw. „Server nicht erreichbar“.

## 15k. Neu in 3.22 – Messkampagne (Baseline)

- [ ] K1 Kaltstart (App vorher beenden): Protokoll enthält „Start bis Startbildschirm“ und danach „Start bis Inhalt sichtbar“, beide in ms.
- [ ] K2 Ohne Playlist (Einrichtung): kein „Inhalt sichtbar“.
- [ ] K3 Live TV 20 s durch die Senderliste scrollen, dann zu „Filme“ wechseln: Zeile „Bildzeiten live: … Bilder, … % verpasst …“.
- [ ] K4 App mit Home-Taste verlassen: Zeile „Bildzeiten …“ für den zuletzt offenen Bereich.
- [ ] K5 Nur kurz auf einem Bereich (kaum Bewegung): keine Zeile (unter 120 Bildern).
- [ ] K6 Debug-APK: Zeilen enden mit „[Debug-Build: langsamer als Release]“.
- [ ] K7 Bedienung fühlt sich gleich an wie 3.21 (Messung kostet nichts Spürbares).

## 15j. Neu in 3.21 – Beschreibung lesbar, Startfokus

| # | So testen | Erwartet | Prüft |
|---|-----------|----------|-------|
| A1 | TMDB-Titel von der Startseite öffnen (Fernseher) | Drei lesbare Zeilen, unten weich ausgeblendet, darunter „Mehr anzeigen“ | Eingeklappt, lesbar |
| A2 | OK auf „Mehr anzeigen“, dann erneut OK | Voller Text mit „Weniger anzeigen“, danach wieder drei Zeilen | Umschalten |
| A3 | Titel mit kurzer Beschreibung (≤ 3 Zeilen) | Text steht direkt da, kein Schalter | Kurze Texte |
| A4 | App beenden (oder Fernseher neu starten), App öffnen, in Live TV nach unten | Fokus auf „Alle Sender“ | Startfokus Live |
| A5 | Wie A4, aber zuerst nach rechts zu Filme bzw. Serien, dann nach unten | Fokus auf „Alle Filme“ / „Alle Serien“ | Startfokus VOD |
| A6 | Vorher in der Gruppenliste weit nach unten scrollen, App per Home-Taste verlassen, später wieder öffnen | Wie A4/A5 | Wiederhergestellte Scrollposition |

## 16. Fernseher und Handy (Kurzcheck)

| # | So testen | Erwartet |
|---|-----------|----------|
| K1 | Sender starten, OK, Links/Rechts, Zurück (Fernseher) | Bedienung wie bisher |
| K2 | Sender antippen, wischen, Bild im Bild (Handy) | Wie bisher: Querformat, Gesten, Bild im Bild |
| K3 | Kaltstart | Startbildschirm schnell da; Fehlerprotokoll zeigt „Start bis Startbildschirm: … ms“ |

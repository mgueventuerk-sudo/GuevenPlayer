# Zertifikats-Pinning (ab 3.23)

## Was gepinnt ist

Datei: `app/src/main/res/xml/network_security_config.xml`. Gepinnt ist der öffentliche Schlüssel (SPKI, SHA-256) der Wurzel-CAs,
nicht das Serverzertifikat. Gilt nur für den genannten Host (`includeSubdomains="false"`).

| Host | Warum | Pins (Wurzeln) |
|---|---|---|
| api.themoviedb.org | TMDB-API-Schlüssel | Amazon Root CA 1–4, Starfield Services Root G2 |
| sports.bzzoiro.com | Bzzoiro-Token | Cloudflare-Satz: GTS Root R1/R3/R4, ISRG Root X1/X2, SSL.com (4 Wurzeln), DigiCert Global Root G2/G3 |
| api.sportmonks.com | Sportmonks-Token | Cloudflare-Satz |
| api.surfshark.com | Surfshark-Anmeldung | Cloudflare-Satz |
| cloudflare-dns.com | DNS über HTTPS | Cloudflare-Satz |
| dns.quad9.net | DNS über HTTPS | DigiCert Global Root G2/G3, ISRG Root X1/X2 |

Ablauf der Pins: **2027-08-01**. Danach prüft Android wieder ohne Pinning (die App bleibt erreichbar).
`NetworkSecurityConfigTest` schlägt 60 Tage vorher absichtlich fehl.

Nicht gepinnt sind IPTV-Anbieter, weil ihre Adressen erst beim Einrichten bekannt sind. Ebenfalls nicht gepinnt sind
Bilder-CDNs, ESPN, epgshare01 und ipify, weil sie keine Geheimnisse empfangen.

## Prüfen und erneuern

1. Pins gegen die echten Ketten prüfen: `python3 -I tools/check_pins.py`. Jede Domain muss „OK“ melden.
2. Wechselt ein Dienst die CA:
   1. Die neue Wurzel aus der Ausgabe von `openssl s_client -showcerts` bestimmen.
   2. Den SPKI-Wert berechnen: `openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64`.
   3. Den Wert ergänzen. Alte Pins erst entfernen, wenn kein Gerät sie mehr braucht.
3. Ablaufdatum verlängern, danach `NetworkSecurityConfigTest` laufen lassen.
4. Auf einem Gerät oder Emulator prüfen: `VirtualLabTest#pinningRejectsValidChainOfOtherCa` und `#pinnedHostsReachableThroughAppClient`.

## Belege (Emulator Android 9, 02.11.2026)

- **Pin greift:** Eine gültige DigiCert-Kette wird für api.themoviedb.org abgelehnt („Pin verification failed“). Für einen
  nicht gepinnten Host wird dieselbe Kette akzeptiert. Der Fehler kommt also vom Pin, nicht vom Vertrauen in die Kette.
- **Echte Kette geht durch:** Die echte Amazon-Kette wird für api.themoviedb.org akzeptiert.
- **Live-Verbindungen:** Alle 6 Hosts haben über den App-Client mit HTTP 200, 400 oder 404 geantwortet (kein TLS-Fehler).
- **Zwischenfall beim Bau:** Im ersten Entwurf stand `cloudflare-dns.com` doppelt in der Datei. Android lehnt so eine Datei ab,
  und die App stürzte beim Start ab („cloudflare-dns.com has already been specified“). Das Emulator-Labor hat den Fehler vor der
  Auslieferung gefunden. Der Test `noDomainIsSpecifiedTwice` verhindert ihn künftig.

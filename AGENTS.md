# AGENTS.md — Projektregeln (nicht verhandelbar)

Du arbeitest an einer IPTV-App für Android TV / Google TV (inkl. Fire TV
Stick) und Android-Handys (Android 17). Befolge diese Regeln bei JEDER
Änderung. Bei Regelkonflikt: Korrektheit/Sicherheit vor Kürze oder
Bequemlichkeit.

## P0 — Architektur & Sprache
- 100 % Kotlin. Kein Java.
- Asynchron ausschließlich Coroutines + Flows. Kein blockierender I/O auf
  dem Main-Thread, kein runBlocking in Produktionscode.
- Clean Architecture, strikt entkoppelte Module (domain / data /
  presentation). Domain ohne Android-Abhängigkeiten.
- UI: MVVM, State via Flow, keine Logik in Activities/Composables.
- Adaptive UI für BEIDE Plattformen: TV mit D-Pad-/Fokus-Navigation,
  Handy mit Touch. Nie Plattform-Annahmen mischen.
- Playback ausschließlich Jetpack Media3: HLS, MPEG-TS, DASH, Hardware-
  Decoding, Auto-Frame-Rate-Matching. Kein alter ExoPlayer2.
- Datenhaltung: Room + Paging 3. Große Playlists (M3U/XMLTV) via
  XmlPullParser streamen — nie komplett in den RAM laden.
- Quellen: M3U (URL und lokal), Xtream Codes API, Stalker Portal.

## P0 — Sicherheit
- Login-Daten (Xtream/Stalker) NUR in EncryptedSharedPreferences/Keystore.
  Nie im Code, nie in Logs, nie in Commits, nie in Datenbank-Klartext.
- Netzwerk ausschließlich HTTPS. Keine Credentials in URLs/Headers, die
  geloggt werden können.
- Alle Eingaben validieren (Playlist-URLs, Xtream-Antworten, XML-Inhalte)
  vor Verarbeitung. Keine dynamischen Pfade ohne Prüfung.
- Keine sensiblen Daten in Logcat. Debug-Logs dürfen keine Passwörter,
  Token oder vollständige Playlist-URLs mit Credentials enthalten.

## P0 — Stabilität
- Coroutines immer in viewModelScope (UI) bzw. passendem Scope starten,
  Ausnahmen mit SupervisorJob + ExceptionHandler behandeln.
- Keine stillen Swallows: leere catch-Blöcke sind verboten. Jede Exception
  wird behandelt UND geloggt.
- ExoPlayer/Media3 immer im Lifecycle freigeben (release in onStop/
  onCleared) — kein Player-/Speicherleak.
- Netzwerkaufrufe mit Retry + Backoff; Fehlerzustände in der UI anzeigen,
  nicht stumm ignorieren. Prozess-Tod überstehen: Zustand im ViewModel,
  nicht in Activity/Composable.
- Tests für Repositories/UseCases. Neuer Code ohne Test nur mit
  schriftlicher Begründung im Diff.

## P0 — Performance
- Baselines: Fire TV Stick (TV) und schwache Handys. 60-FPS-Scrolling
  und Stabilität dort sind das Abnahmekriterium jeder UI-Änderung.
- Kein I/O im Main-Thread, keine teuren Operationen pro Frame, keine
  großen Objekte im Composable-Body. Listen via Paging statt Vollladen.
- Player-Session nach Nutzung freigeben; keine doppelten Player-Instanzen.
- R8/ProGuard aktiv lassen; keine Regression bei Build-Größe/Startzeit.

## Arbeitsregeln
- Vor JEDER Änderung betroffenes Symbol lesen (grep/read auf Funktions-
  oder Zeilenebene). Keine blinden Scans, kein Raten.
- Änderungen als minimales Diff. Keine unrelated Refactors, keine
  Neuformatierung fremder Dateien.
- Nicht anfassen: .git, build/, dist/, node_modules/, Lockfiles, Bilder,
  Assets, generierte Dateien.
- Typsicherheit/Null-Safety: kein !! ohne Begründung im Diff-Kommentar.
- Keine erfundenen APIs/Versionen/Bibliotheken. Unsicher? Nachschlagen
  oder [geraten] markieren.
- Code nur als Diff ausgeben.

## Definition of Done (Pflicht vor „fertig")
- Kompiliert + Tests grün + Detekt/Lint ohne NEUE Fehler: `./gradlew test detekt lint`.
- Zusätzlich konkrete manuelle Prüfschritte nennen (was auf Gerät zu
  prüfen ist). Ohne Nachweis gilt nichts als fertig.
- „Sollte funktionieren" ist verboten. Entweder belegt grün oder klar
  als ungeprüft markiert.

## Fehlersuche & professioneller Fix
Wenn du Fehler suchen oder fixen sollst, gilt diese Reihenfolge strikt.
Raten und blindes Scannen sind verboten.
1. REPRODUZIEREN — konkreten Fehler verlangen (Stacktrace/Logcat,
   erwartet vs. tatsächlich). Fehlt er: sagen, was fehlt, statt raten.
2. LOKALISIEREN — Symbol/Modul per grep/read finden, Code vor Änderung lesen.
3. ROOT-CAUSE — Ursache in 1–3 Sätzen, Ursache statt Symptom fixen.
   Vermutete Ursache: [geraten] + Prüfschritte.
4. MINIMALER FIX — Diff, nur nötige Zeilen. Keine Refactors nebenbei.
5. VERIFIZIEREN — Tests + Build grün. Rot = nicht gefixt.
   Nennen, wie der Fix reproduziert wird.
6. RÜCKFALLEBENE — keine Ursache findbar: ehrlich sagen, Verdachtspunkte
   mit Beleg listen, keinen Schein-Fix.

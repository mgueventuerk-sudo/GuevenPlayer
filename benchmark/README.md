# Geräte-Benchmarks

Das Modul misst die **Release-Variante** mit Android Macrobenchmark. Es erfasst Kaltstart ohne Kompilierung,
Kaltstart mit Baseline Profile, Frame-Zeiten, maximalen Speicherverbrauch bei Start und Navigation sowie Player-Start
bis zur Wiedergabe.
Die Tests laufen auf
angeschlossenen Android-Geräten; Robolectric und normale Emulatoren sind keine verlässliche Hardware-Messung.

## Gerätevergleich

Für einen reinen Cloud-Lauf eine Remote-Device-Farm verwenden, die den Android-Instrumentierungstest auf echten
Android-Geräten ausführt. Lokal kann weiterhin jeweils ein Gerät angeschlossen und mit
`tools/benchmark-device-info.sh` dokumentiert werden.

- **Schwach:** echtes Android-TV-/Fire-TV-Gerät am unteren Ende der Zielgruppe, idealerweise 1–2 GB RAM und älterer SoC.
- **Stark:** aktuelles Android-TV-Gerät mit mindestens 4 GB RAM und deutlich neuerem SoC.
- Beide Geräte mit derselben Android-App-Version, Auflösung, Bildfrequenz und demselben Testzugang betreiben.
- Der Testzugang benötigt einen Kanal namens `BENCHMARK_TEST_CHANNEL` und einen stabilen Stream, der mindestens
  30 Sekunden verfügbar ist.
- Vor dem Navigationstest dieselbe Test-Playlist einrichten. Auto-Update, VPN und andere Hintergrundlast während der Messung
  gleich einstellen; vor jedem Lauf das Gerät abkühlen lassen.
- Je Szenario mindestens drei vollständige Läufe durchführen; Median und p90 vergleichen. Modell, Android-Version,
  SoC, RAM, Auflösung und Build-Stempel zusammen mit dem Ergebnis aufbewahren.

## Ausführen

```sh
./gradlew :benchmark:connectedReleaseAndroidTest
```

Gradle installiert die profileable Release-App und den Benchmark-Test auf dem verbundenen Gerät. Berichte und Rohdaten landen unter
`benchmark/build/outputs/connected_android_test_additional_output/`.

`coldStartWithoutCompilation` zeigt den Start ohne vorab optimierten App-Code. `coldStartWithBaselineProfile` zeigt den
Start mit dem enthaltenen Baseline Profile. `topLevelNavigation` braucht eine eingerichtete Playlist und erfasst
Frame-Zeiten beim Wechsel zwischen Live-TV, Filmen, Serien und Suche. `playerStartsFromFixtureChannel` startet den
definierten Testkanal und wartet bis zu 30 Sekunden auf die Wiedergabe. Macrobenchmark liefert pro Gerät eigene Werte;
Gerätewerte nicht zu einem Durchschnitt zusammenziehen.

## Cloud-Status (31.10.2026)

Die JVM-/Compose-Suite lief mit 628 Tests ohne Fehler; neun Tests mit externen Voraussetzungen wurden anschließend
gezielt ebenfalls erfolgreich ausgeführt. Der CH1-Parser benötigte auf der Cloud-VM 7,2 MB zusätzlichen lebenden Heap
und 1.139 ms. Diese JVM-Messung ersetzt keine Gerätedaten.

`connectedReleaseAndroidTest` benötigt ein verbundenes oder remote bereitgestelltes Android-Gerät. Der hier installierte
Android-TV-x86-Emulator kam ohne `/dev/kvm` nicht über ADB `offline` hinaus; daraus wurden keine Hardwarewerte abgeleitet.
Der ausführliche Stand und der daraus folgende Plan stehen in [RESULTS-2026-10-31.md](RESULTS-2026-10-31.md).

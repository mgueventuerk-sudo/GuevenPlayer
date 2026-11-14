#!/usr/bin/env bash
# Misst zwei Stände abwechselnd (vorher, nachher, nachher, vorher, ...) auf demselben Emulator.
# Ein gescheiterter Lauf bricht die übrigen nicht ab; sein Ausgang steht in <lauf>/exit-code.
set -u
BEFORE="$1"; AFTER="$2"; ROUNDS="${3:-2}"
ROOT="$PWD"; OUT="$ROOT/ab-results"; mkdir -p "$OUT"
git worktree add --detach "$ROOT/../wt-before" "$BEFORE" >/dev/null
git worktree add --detach "$ROOT/../wt-after" "$AFTER" >/dev/null
echo "vorher=$BEFORE nachher=$AFTER runden=$ROUNDS" > "$OUT/staende.txt"

run() { # <name> <verzeichnis> <runde>
  local name="$1" dir="$2" round="$3" dest="$OUT/$1-$3"
  mkdir -p "$dest"
  echo "=== $name, Runde $round ($(git -C "$dir" rev-parse --short HEAD)) ==="
  (cd "$dir" && chmod +x gradlew && rm -rf benchmark/build/outputs/connected_android_test_additional_output &&
    ./gradlew :benchmark:connectedBenchmarkAndroidTest -PbenchmarkEmulator=true \
      -Pandroid.testInstrumentationRunnerArguments.class=com.mg.player.benchmark.AppMacrobenchmark --stacktrace) \
    > "$dest/gradle.log" 2>&1
  local code=$?
  echo $code > "$dest/exit-code"
  cp -r "$dir/benchmark/build/outputs/connected_android_test_additional_output/." "$dest/" 2>/dev/null || true
  echo "--- $name Runde $round: Gradle-Ausgang $code, Ergebnisdateien: $(find "$dest" -name '*.json' | wc -l)"
  # Fehlerursachen ins Job-Log, damit sie ohne Artefakt lesbar sind (kurz: nur die aussagekräftigen Zeilen).
  grep -E "FAILED|Exception|AssertionError|Error:|error:|No tests|Unresolved|BUILD " "$dest/gradle.log" | head -n 25
  [ "$code" -eq 0 ] || FAILED_RUNS=$((FAILED_RUNS + 1))
}

FAILED_RUNS=0

for round in $(seq 1 "$ROUNDS"); do
  if [ $((round % 2)) -eq 1 ]; then
    run vorher "$ROOT/../wt-before" "$round"; run nachher "$ROOT/../wt-after" "$round"
  else
    run nachher "$ROOT/../wt-after" "$round"; run vorher "$ROOT/../wt-before" "$round"
  fi
done
echo "Gescheiterte Läufe: $FAILED_RUNS"
# Ein Lauf ohne Ergebnis darf nicht als Erfolg zählen – sonst sieht „grün“ nach Messung aus, obwohl nichts gemessen wurde.
[ "$FAILED_RUNS" -eq 0 ]

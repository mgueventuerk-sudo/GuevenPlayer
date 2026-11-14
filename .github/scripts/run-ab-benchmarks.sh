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
  echo $? > "$dest/exit-code"
  cp -r "$dir/benchmark/build/outputs/connected_android_test_additional_output/." "$dest/" 2>/dev/null || true
  tail -n 15 "$dest/gradle.log"
}

for round in $(seq 1 "$ROUNDS"); do
  if [ $((round % 2)) -eq 1 ]; then
    run vorher "$ROOT/../wt-before" "$round"; run nachher "$ROOT/../wt-after" "$round"
  else
    run nachher "$ROOT/../wt-after" "$round"; run vorher "$ROOT/../wt-before" "$round"
  fi
done
exit 0

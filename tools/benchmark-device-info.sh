#!/usr/bin/env bash
set -euo pipefail

adb_cmd=("${ADB:-adb}")
if [[ $# -gt 0 ]]; then
    adb_cmd+=( -s "$1" )
fi

"${adb_cmd[@]}" get-state >/dev/null
printf 'model: '
"${adb_cmd[@]}" shell getprop ro.product.model | tr -d '\r'
printf 'device: '
"${adb_cmd[@]}" shell getprop ro.product.device | tr -d '\r'
printf 'android: '
"${adb_cmd[@]}" shell getprop ro.build.version.release | tr -d '\r'
printf 'api: '
"${adb_cmd[@]}" shell getprop ro.build.version.sdk | tr -d '\r'
printf 'soc: '
"${adb_cmd[@]}" shell getprop ro.soc.model | tr -d '\r'
printf 'memory: '
"${adb_cmd[@]}" shell awk '/MemTotal/ { printf "%.0f MiB\n", $2 / 1024 }' /proc/meminfo | tr -d '\r'
printf 'display: '
"${adb_cmd[@]}" shell wm size | tr -d '\r'
printf 'refresh: '
"${adb_cmd[@]}" shell dumpsys display | rg -m1 -o '[0-9]+(\.[0-9]+)? ?Hz' || true

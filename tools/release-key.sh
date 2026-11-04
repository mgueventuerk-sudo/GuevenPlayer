#!/usr/bin/env bash
# Release-Schlüssel für GuevenPlayer anlegen bzw. prüfen.
#
#   tools/release-key.sh erzeugen  ZIELDATEI.jks [ALIAS]   – neuen Schlüssel anlegen (Passwörter werden abgefragt)
#   tools/release-key.sh pruefen   APK                     – Signatur einer APK anzeigen (SHA-256)
#
# Wichtig: Der Release-Schlüssel ist nicht ersetzbar. Wer ihn verliert, kann keine Updates mehr über die
# installierte App einspielen (Neuinstallation = Daten weg). Die .jks-Datei und beide Passwörter deshalb an
# zwei getrennten Orten sichern (z. B. Passwort-Manager + verschlüsselter USB-Stick) – nie ins Repository.
set -euo pipefail

usage() {
    sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
}

need() { command -v "$1" >/dev/null 2>&1 || { echo "„$1“ fehlt (JDK bzw. Android-SDK build-tools installieren)." >&2; exit 1; }; }

case "${1:-}" in
    erzeugen)
        target="${2:-}"; alias="${3:-guevenplayer}"
        [ -n "$target" ] || usage
        [ ! -e "$target" ] || { echo "$target existiert schon – wird nicht überschrieben." >&2; exit 1; }
        case "$(cd "$(dirname "$target")" && pwd)" in
            "$(git rev-parse --show-toplevel 2>/dev/null || echo /nie)"*) echo "Bitte außerhalb des Repositorys speichern." >&2; exit 1 ;;
        esac
        need keytool
        # keytool fragt Store- und Schlüssel-Passwort selbst ab – sie landen weder in der Shell-History noch in Dateien.
        keytool -genkeypair -v -keystore "$target" -storetype PKCS12 -alias "$alias" \
            -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=GuevenPlayer"
        chmod 600 "$target"
        echo
        echo "Fingerabdruck (für spätere Prüfung notieren):"
        keytool -list -v -keystore "$target" -alias "$alias" | grep -E "SHA256:" || true
        echo
        echo "Für GitHub-Secrets:"
        echo "  SIGNING_KEYSTORE_BASE64 = Ausgabe von:  base64 -w0 \"$target\""
        echo "  SIGNING_KEY_ALIAS       = $alias"
        echo "  SIGNING_STORE_PASSWORD / SIGNING_KEY_PASSWORD = die eben gewählten Passwörter"
        echo "Lokal: SIGNING_KEYSTORE_PATH=\"$target\" plus dieselben drei Werte als Umgebungsvariablen."
        echo "Jetzt sichern: $target und beide Passwörter an zwei getrennten Orten."
        ;;
    pruefen)
        apk="${2:-}"
        [ -f "$apk" ] || usage
        signer="$(command -v apksigner || ls -1 "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/nie}}"/build-tools/*/apksigner 2>/dev/null | tail -1 || true)"
        if [ -n "$signer" ]; then
            "$signer" verify --print-certs "$apk" | grep -E "SHA-256|DN"
        else
            # Nur für APKs mit v1-Signatur aussagekräftig; besser apksigner (Android-SDK build-tools) installieren.
            need keytool
            keytool -printcert -jarfile "$apk" | grep -E "SHA256|Owner"
        fi
        echo "Debug-Schlüssel (nicht für Veröffentlichungen): a7:14:20:28:46:6a:6b:90:3d:8a:0c:56:3d:40:1b:2b:60:39:85:87:dc:f4:9e:e1:f6:d9:dd:65:62:b6:75:62"
        ;;
    *) usage ;;
esac

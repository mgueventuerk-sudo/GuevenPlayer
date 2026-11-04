#!/usr/bin/env python3
"""Prüft die Pins in network_security_config.xml gegen die echten Zertifikatsketten der Dienste.

Für jede gepinnte Domain: Kette holen (openssl s_client, bei gesetztem HTTPS_PROXY über den Proxy), SPKI-SHA-256
jedes Kettenglieds und der ausstellenden Wurzel aus dem lokalen Trust-Store berechnen und mit den Pins vergleichen.
Ausgabe je Domain OK/FEHLER; Exit-Code 1 bei mindestens einem Fehler.
Aufruf: python3 -I tools/check_pins.py [Pfad/zur/network_security_config.xml]
"""
import base64, hashlib, os, re, subprocess, sys
import xml.etree.ElementTree as ET

CONFIG = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/res/xml/network_security_config.xml"
TRUST_STORE = "/etc/ssl/certs/ca-certificates.crt"


def spki(pem: str) -> str:
    pub = subprocess.run(["openssl", "x509", "-noout", "-pubkey"], input=pem, capture_output=True, text=True).stdout
    der = subprocess.run(["openssl", "pkey", "-pubin", "-outform", "der"], input=pub.encode(), capture_output=True).stdout
    return base64.b64encode(hashlib.sha256(der).digest()).decode()


def field(pem: str, what: str) -> str:
    return subprocess.run(["openssl", "x509", "-noout", what], input=pem, capture_output=True, text=True).stdout.strip()


def chain(host: str) -> list:
    cmd = ["openssl", "s_client", "-connect", f"{host}:443", "-servername", host, "-showcerts"]
    proxy = os.environ.get("HTTPS_PROXY", "").replace("http://", "").rstrip("/")
    if proxy:
        cmd[2:2] = ["-proxy", proxy]
    out = subprocess.run(cmd, input="", capture_output=True, text=True, timeout=30).stdout
    return re.findall(r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", out, re.S)


def main() -> int:
    store = re.findall(r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", open(TRUST_STORE).read(), re.S)
    roots = {field(c, "-subject"): c for c in store}
    failed = 0
    for config in ET.parse(CONFIG).getroot().iter("domain-config"):
        pin_set = config.find("pin-set")
        if pin_set is None:
            continue
        pins = {p.text.strip() for p in pin_set.iter("pin")}
        for domain in config.iter("domain"):
            host = domain.text.strip()
            certs = chain(host)
            if not certs:
                print(f"FEHLER {host}: keine Kette erhalten")
                failed += 1
                continue
            issuer = field(certs[-1], "-issuer").replace("issuer=", "subject=", 1)
            candidates = certs + ([roots[issuer]] if issuer in roots else [])
            hits = [field(c, "-subject").split("CN = ")[-1] for c in candidates if spki(c) in pins]
            print(f"{'OK    ' if hits else 'FEHLER'} {host}: {', '.join(hits) or 'kein Pin passt'}")
            failed += 0 if hits else 1
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

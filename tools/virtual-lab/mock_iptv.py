#!/usr/bin/env python3
"""Virtueller IPTV-Anbieter für Mess- und Ausfalltests (Xtream-API, M3U, XMLTV, TS-Streams).

Der erste Pfadteil wählt das Verhalten:
  /ok/…        normaler Anbieter            /expired/…  Konto abgelaufen (Streams 403)
  /badpass/…   falsches Passwort (auth 0)   /err500/…   Server-Fehler 500
  /slow/…      jede Antwort 8 s verzögert   /empty/…    leere Listen
  /broken/…    kaputtes JSON/M3U            /drop/…     Verbindung bricht mitten in der Antwort ab
Größen per Umgebung: LIVE (10000), VOD (5000), SERIES (2000), EPG_CHANNELS (2000).
Aufruf: python3 -I mock_iptv.py <port> <medienordner> <logdatei>
"""
import json, os, sys, time, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT, MEDIA, LOG = int(sys.argv[1]), sys.argv[2], sys.argv[3]
LIVE, VOD, SERIES = (int(os.environ.get(k, d)) for k, d in (("LIVE", 10000), ("VOD", 5000), ("SERIES", 2000)))
EPG_CHANNELS = int(os.environ.get("EPG_CHANNELS", 2000))
CATS = 40
MEDIA_FILES = sorted(f for f in os.listdir(MEDIA) if f.endswith(".ts"))


def cats(prefix):
    return [{"category_id": str(i), "category_name": f"{prefix} {i:02d}", "parent_id": 0} for i in range(1, CATS + 1)]


def live():
    return [{"num": i, "name": f"DE| Sender {i:05d} HD", "stream_type": "live", "stream_id": i, "stream_icon": "",
             "epg_channel_id": f"ch{i}.de", "category_id": str(i % CATS + 1), "tv_archive": 0} for i in range(1, LIVE + 1)]


def vod():
    return [{"num": i, "name": f"Film {i:05d} ({1980 + i % 45})", "stream_id": i, "stream_icon": "", "rating": "7.1",
             "category_id": str(i % CATS + 1), "container_extension": "ts"} for i in range(1, VOD + 1)]


def series():
    return [{"num": i, "name": f"Serie {i:05d}", "series_id": i, "cover": "", "rating": "8.0",
             "category_id": str(i % CATS + 1)} for i in range(1, SERIES + 1)]


DATA = {
    "get_live_categories": json.dumps(cats("Live")).encode(),
    "get_vod_categories": json.dumps(cats("Filme")).encode(),
    "get_series_categories": json.dumps(cats("Serien")).encode(),
    "get_live_streams": json.dumps(live()).encode(),
    "get_vod_streams": json.dumps(vod()).encode(),
    "get_series": json.dumps(series()).encode(),
}


def account(mode, user):
    now = int(time.time())
    expired = mode == "expired"
    return json.dumps({
        "user_info": {"username": user, "auth": 0 if mode == "badpass" else 1, "status": "Expired" if expired else "Active",
                      "exp_date": str(now - 86400 if expired else now + 86400 * 365), "max_connections": "1", "active_cons": "0"},
        "server_info": {"timezone": "Europe/Berlin", "server_protocol": "http", "time_now": time.strftime("%Y-%m-%d %H:%M:%S")},
    }).encode()


def m3u(host):
    lines = ["#EXTM3U"]
    for i in range(1, LIVE + 1):
        lines.append(f'#EXTINF:-1 tvg-id="ch{i}.de" tvg-name="Sender {i:05d}" tvg-logo="" group-title="Gruppe {i % CATS + 1:02d}",DE| Sender {i:05d} HD')
        lines.append(f"http://{host}/ok/media/{i}.ts")
    return ("\n".join(lines) + "\n").encode()


def xmltv():
    start = time.gmtime(time.time() - 3600 * 3)
    base = time.mktime(start) - (time.mktime(start) % 3600)
    out = ['<?xml version="1.0" encoding="UTF-8"?>', "<tv>"]
    for i in range(1, EPG_CHANNELS + 1):
        out.append(f'<channel id="ch{i}.de"><display-name>Sender {i:05d}</display-name></channel>')
    for i in range(1, EPG_CHANNELS + 1):
        for k in range(12):
            a = time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(base + k * 3600))
            b = time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(base + (k + 1) * 3600))
            out.append(f'<programme start="{a}" stop="{b}" channel="ch{i}.de"><title>Sendung {k} auf {i}</title><desc>Beschreibung {k}</desc></programme>')
    out.append("</tv>")
    return "\n".join(out).encode()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        with open(LOG, "a") as f:
            ua = self.headers.get("User-Agent", "-").replace(" ", "_")
            rng = self.headers.get("Range", "-")
            f.write(f"{time.time():.3f} {self.command} {self.path} {args[1] if len(args) > 1 else ''} ua={ua} range={rng}\n")

    def send(self, code, body=b"", ctype="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        parts = url.path.strip("/").split("/")
        mode, rest = parts[0], parts[1:]
        q = dict(urllib.parse.parse_qsl(url.query))
        if mode == "slow":
            time.sleep(8)
        if mode == "err500":
            return self.send(500, b"Internal Server Error", "text/plain")
        if mode == "broken" and rest and rest[0] in ("player_api.php", "get.php", "m3u", "xmltv.php"):
            return self.send(200, b'{"user_info": {"auth": 1, "status": "Act', "application/json")
        if not rest:
            return self.send(404)
        head = rest[0]
        if head == "player_api.php":
            action = q.get("action")
            if action is None:
                return self.send(200, account(mode, q.get("username", "")))
            if mode == "badpass":
                return self.send(200, b"[]")
            body = b"[]" if mode == "empty" else DATA.get(action, b"[]")
            if mode == "drop":
                return self.drop(body)
            return self.send(200, body)
        if head in ("get.php", "m3u"):
            body = b"#EXTM3U\n" if mode == "empty" else m3u(self.headers.get("Host", f"127.0.0.1:{PORT}"))
            return self.drop(body) if mode == "drop" else self.send(200, body, "audio/x-mpegurl")
        if head == "xmltv.php":
            return self.send(200, xmltv(), "application/xml")
        if head in ("live", "movie", "series", "media"):
            if mode == "expired" or mode == "badpass":
                return self.send(403, b"Forbidden", "text/plain")
            name = rest[-1]
            try:
                index = int(name.split(".")[0])
            except ValueError:
                index = 0
            path = os.path.join(MEDIA, MEDIA_FILES[index % len(MEDIA_FILES)])
            return self.live_stream(path) if head == "live" else self.stream(path)
        return self.send(404)

    def drop(self, body):
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body[: len(body) // 3])
        self.wfile.flush()
        self.close_connection = True

    def live_stream(self, path):
        """Wie echte IPTV-Live-Streams: ohne Content-Length, endlos (Datei in Schleife), Ende = Verbindung zu."""
        self.send_response(200)
        self.send_header("Content-Type", "video/mp2t")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        if self.command == "HEAD":
            return
        try:
            while True:
                with open(path, "rb") as f:
                    while chunk := f.read(188 * 348):
                        self.wfile.write(chunk)
                        time.sleep(0.04)  # etwa Echtzeit-Bitrate statt Leitungsgeschwindigkeit
        except (BrokenPipeError, ConnectionResetError):
            pass

    def stream(self, path):
        size = os.path.getsize(path)
        self.send_response(200)
        self.send_header("Content-Type", "video/mp2t")
        self.send_header("Content-Length", str(size))
        self.end_headers()
        if self.command == "HEAD":
            return
        with open(path, "rb") as f:
            try:
                while chunk := f.read(64 * 1024):
                    self.wfile.write(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass


ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()

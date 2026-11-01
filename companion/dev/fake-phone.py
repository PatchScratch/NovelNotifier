#!/usr/bin/env python3
"""Fake NovelNotifier phone server for companion development.

Serves the /api/v1 contract (see the app's LanApi.kt) with sample data, so
the desktop companion can be developed and eyeballed without a phone.
Not a mock of the app logic — a static JSON fixture plus echo-success
mutations that apply to its own in-memory copy.

Usage: python3 fake-phone.py [port]   (default 8799, token "dev-token")
"""
import json
import struct
import sys
import threading
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = "dev-token"

SHELVES = [
    {"id": "c1", "name": "Ongoing", "hidden": False},
    {"id": "c2", "name": "Finished", "hidden": False},
    {"id": "c3", "name": "Backlog", "hidden": False},
]

NAMES = [
    ("Welcome to Japan, Ms. Elf!", 9), ("Welcome to Japan Ms. Elf!", 1),
    ("Re:Zero −Starting Life in Another World−", 14),
    ("Failure to Transition", 3), ("By the Grace of the Gods", 12),
    ("Two-Headed Wizard", 4), ("Kuma Kuma Kuma Bear", 10),
    ("薬屋のひとりごと", 6), ("転生したらスライムだった件", 18),
    ("無職転生", 12), ("Shield Hero", 11), ("Solo Leveling", 5),
    ("Stabbing with a Syringe", 2), ("Story Seedling", 7),
    ("Asian Hobbyist Collection", 3), ("LNT Anthology", 2),
    ("High School DxD", 17), ("Campione!", 8), ("Date A Live", 15),
    ("Oregairu", 9), ("Bunny Girl Senpai", 6), ("86 —Eighty Six—", 8),
]

state_lock = threading.Lock()
state = {"series": [], "assignments": {}}


def build_state():
    series = []
    for i, (name, vols) in enumerate(NAMES):
        series.append({
            "id": "s%d" % i,
            "name": name,
            "key": name.lower(),
            "description": "Sample series %d for companion development." % i,
            "coverBookId": "",
            "volumes": [
                {"id": "b%d-%d" % (i, v), "title": "%s Vol. %d" % (name, v + 1),
                 "author": "Author %d" % (i % 5), "chapters": 20 + v,
                 "addedAt": 1700000000000 + i * 86400000 + v * 3600000,
                 "linked": v % 2 == 0, "pos": (v * 3) % 15}
                for v in range(vols)
            ],
        })
    return series


state["series"] = build_state()
for i in range(0, len(NAMES), 3):
    state["assignments"]["s%d" % i] = SHELVES[i % 3]["id"]


def png_bytes(rgb, w=120, h=180):
    """Minimal solid-color PNG without external deps."""
    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)
    raw = b"".join(b"\x00" + bytes(rgb) * w for _ in range(h))
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 6))
            + chunk(b"IEND", b""))


COVERS = {sid: png_bytes(((i * 37) % 255, (i * 91) % 255, (i * 53) % 255))
          for i, sid in enumerate(s["id"] for s in state["series"])}


class Handler(BaseHTTPRequestHandler):
    def _send(self, obj, status=200):
        body = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authed(self):
        if self.headers.get("Authorization") != "Bearer " + TOKEN:
            self._send({"ok": False, "error": "unauthorized"}, 401)
            return False
        return True

    def do_GET(self):
        if not self._authed():
            return
        path = self.path.rstrip("/")
        if path == "/api/v1/status":
            self._send({"ok": True, "device": "Fake Phone", "appVersion": "3.2.4-dev",
                        "apiVersion": 1, "port": self.server.server_address[1],
                        "libraries": [{"id": "default", "name": "Library"},
                                      {"id": "abc123", "name": "Light Novels"}]})
            return
        if path.startswith("/api/v1/library/"):
            with state_lock:
                self._send({"ok": True,
                            "library": {"id": "default", "name": "Library"},
                            "shelves": SHELVES,
                            "assignments": state["assignments"],
                            "series": state["series"]})
            return
        if path.startswith("/api/v1/cover/"):
            sid = path.rsplit("/", 1)[1].rsplit("-", 1)[0]
            cover = COVERS.get(sid)
            if cover:
                self.send_response(200)
                self.send_header("Content-Type", "image/png")
                self.send_header("Content-Length", str(len(cover)))
                self.end_headers()
                self.wfile.write(cover)
            else:
                self._send({"ok": False, "error": "no cover"}, 404)
            return
        self._send({"ok": False, "error": "not found"}, 404)

    def do_POST(self):
        if not self._authed():
            return
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length) or b"{}")
        with state_lock:
            if self.path.rstrip("/") == "/api/v1/shelf/create":
                SHELVES.append({"id": "c%d" % len(SHELVES), "name": body["name"], "hidden": False})
            elif self.path.rstrip("/") == "/api/v1/shelf/assign":
                shelf = body.get("shelfId")
                for sid in body.get("seriesIds", []):
                    if shelf:
                        state["assignments"][sid] = shelf
                    else:
                        state["assignments"].pop(sid, None)
            elif self.path.rstrip("/") == "/api/v1/series/merge":
                src = next(s for s in state["series"] if s["id"] == body["fromId"])
                dst = next(s for s in state["series"] if s["id"] == body["toId"])
                dst["volumes"] += src["volumes"]
                state["series"] = [s for s in state["series"] if s["id"] != src["id"]]
                state["assignments"].pop(src["id"], None)
            # Everything else: echo-success (the companion refreshes after).
        self._send({"ok": True})

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8799
    print("fake phone on http://127.0.0.1:%d (token: %s)" % (port, TOKEN))
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()

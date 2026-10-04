"""Local end-to-end verification server for the subscription video playback path.

Serves a Legado-format JSON API (list + article body) plus a small real mp4, so
the app can be driven on an emulator without any internet access.

  python verify_rss_playback_server.py [port]

Endpoints
  GET /api/videos?page=N    list page (ruleArticles = "$.data[*]")
       item 1 -> ruleContent is a bare mp4 URL          (native player path)
       item 2 -> ruleContent is a full HTML player page (in-page playback)
  GET /media/clip.mp4       the sample video (copied from .recover/test.mp4)
  GET /manifest.json        the legado source JSON (array form, for import)
  GET /view?guide=N         article page (unused: ruleContent is enough)
"""

import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8731
HOST = f"http://10.0.2.2:{PORT}"
CLIP = os.path.join(os.path.dirname(os.path.abspath(__file__)), "verify_clip.mp4")

PAGE_HTML = (
    '<html><head><meta charset="utf-8">'
    '<meta name="viewport" content="width=device-width,initial-scale=1">'
    "</head><body>"
    '<div class="container"><div class="title">%TITLE%</div>'
    '<video id="video" width="100%" height="91%" controls autoplay muted loop>'
    '<source src="' + HOST + '/media/clip.mp4" type="video/mp4"></video></div>'
    '<script>var s=["' + HOST + '/media/clip.mp4",""];'
    "function playNext(){var v=document.getElementById('video');"
    "var p=v.play();if(p&&p.catch)p.catch(function(){});}"
    "playNext();</script>"
    "</body></html>"
)

LIST = {
    "data": [
        {
            "id": 1,
            "title": "本地1-裸地址正文",
            "cover": HOST + "/thumb/1.jpg",
            # 91porn / Rule34 的形态：ruleContent 求值结果只有一条媒体地址
            "content": HOST + "/media/clip.mp4",
        },
        {
            "id": 2,
            "title": "本地2-自带播放器正文",
            "cover": HOST + "/thumb/2.jpg",
            # h视频 的形态：完整 HTML + <video>
            "content": PAGE_HTML.replace("%TITLE%", "本地2-自带播放器正文"),
        },
    ],
    "next": "",
}

SOURCE = [
    {
        "sourceName": "本地视频源校验",
        "sourceUrl": HOST,
        "type": 0,
        "enabled": True,
        "enableJs": False,
        "header": json.dumps({"Referer": HOST + "/", "User-Agent": "WallpaperSwitcherVerify/1.0"}),
        "ruleArticles": "$.data[*]",
        "ruleTitle": "$.title",
        "ruleLink": "/view?guide={{$.id}}",
        "ruleImage": "{{$.cover}}",
        "ruleContent": "$.content",
        "ruleNextPage": "$.next",
    }
]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("REQ %s\n" % (fmt % args))
        sys.stderr.flush()

    def _send(self, body: bytes, ctype: str, code: int = 200):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/api/videos":
            self._send(json.dumps(LIST, ensure_ascii=False).encode("utf-8"),
                       "application/json; charset=utf-8")
        elif path == "/media/clip.mp4":
            with open(CLIP, "rb") as fh:
                self._send(fh.read(), "video/mp4")
        elif path == "/manifest.json":
            self._send(json.dumps(SOURCE, ensure_ascii=False).encode("utf-8"),
                       "application/json; charset=utf-8")
        elif path.startswith("/thumb/"):
            self._send(b"", "image/jpeg")
        elif path == "/view":
            self._send(PAGE_HTML.replace("%TITLE%", "article").encode("utf-8"),
                       "text/html; charset=utf-8")
        else:
            self._send(b"not found", "text/plain", 404)


if __name__ == "__main__":
    if not os.path.isfile(CLIP):
        sys.exit(f"missing {CLIP} (copy any small mp4 there)")
    with open("verify_source_manifest.json", "w", encoding="utf-8") as fh:
        json.dump(SOURCE, fh, ensure_ascii=False, indent=2)
    print(f"list  : {HOST}/api/videos")
    print(f"source: {HOST}/manifest.json")
    print(f"mp4   : {HOST}/media/clip.mp4 ({os.path.getsize(CLIP)} bytes)")
    sys.stdout.flush()
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()

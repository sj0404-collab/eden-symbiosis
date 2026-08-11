"""A Windows desk in a browser, with no password and no VNC.

WHY THIS EXISTS
    TightVNC on a GitHub Windows runner serves a black screen. That is not a
    guess: in one probe job, three frames of the same desktop taken seconds
    apart came out as

        GDI CopyFromScreen        26508 distinct colours   (the real desktop)
        TightVNC as a service         1 distinct colour    (black)
        TightVNC as an application    1 distinct colour    (black)

    and turning off its mirror driver, BlankScreen and RemoveWallpaper changed
    nothing. The desktop is fine; TightVNC's own capture path is what fails on
    a machine with no real display driver.

    So this uses the capture path that demonstrably works on that machine - the
    plain GDI grab - and streams it as MJPEG, which every browser renders
    natively. Mouse and keyboard go back through SendInput.

WHAT IT IS NOT
    Not a VNC server, not efficient, and not a remote-desktop protocol. It
    resends whole JPEG frames a few times a second. On a runner with four idle
    cores that is fine, and it beats a black rectangle that is technically a
    VNC session.

NO PASSWORD
    There is nothing to authenticate against, by design. It binds to localhost
    and is published through a tunnel; whoever has the address has the desk.

Usage: python win_desk_server.py [port]
"""

import base64
import ctypes
import io
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    from PIL import ImageGrab, Image
except ImportError:  # pragma: no cover - only reachable off-Windows
    ImageGrab = None
    Image = None

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 6090
QUALITY = 55
FPS = 5.0

# A full-size stream measured 4.28 Mbit/s at 4.35 fps - fine over a tunnel,
# heavy for a phone showing two thumbnails at once. /stream?w=340&fps=1 lets the
# panel's preview ask for something much cheaper, and an idle desk costs almost
# nothing because identical frames are not resent.
MIN_WIDTH = 160

# Cloudflare buffers a slow response until it has roughly 64 KB, and only then
# forwards the lot. Measured through a live quick tunnel, first frame arriving:
#
#   /stream                 (full size, ~85 KB/frame)   0.6 s
#   /stream?w=340&fps=4     (small, but frequent)       4.1 s
#   /stream?w=340&fps=1     (small and slow, ~15 KB)   28.4 s, then a burst
#
# That burst is exactly what looked like "black for a couple of seconds, then
# it works again": the picture froze while bytes piled up at the edge. Padding
# each part with a comment header to clear the buffer keeps small frames moving
# at the rate they were produced. Browsers ignore unknown MIME headers, so the
# padding is invisible to the client.
PAD_TO = 70 * 1024

# ── Windows input, through SendInput ────────────────────────────────
#
# Guarded so the module imports on Linux, where it is only ever syntax-checked
# and unit-tested; every call site checks IS_WINDOWS first.
IS_WINDOWS = sys.platform == "win32"

if IS_WINDOWS:
    user32 = ctypes.windll.user32
    user32.SetProcessDPIAware()
    SCREEN_W = user32.GetSystemMetrics(0)
    SCREEN_H = user32.GetSystemMetrics(1)
else:  # pragma: no cover
    user32 = None
    SCREEN_W, SCREEN_H = 1024, 768

MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_ABSOLUTE = 0x8000
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_MIDDLEDOWN = 0x0020
MOUSEEVENTF_MIDDLEUP = 0x0040
MOUSEEVENTF_WHEEL = 0x0800

KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004

BUTTON_FLAGS = {
    0: (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP),
    1: (MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP),
    2: (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP),
}

# Browser key names that have no printable character, mapped to virtual keys.
VK = {
    "Backspace": 0x08, "Tab": 0x09, "Enter": 0x0D, "Shift": 0x10,
    "Control": 0x11, "Alt": 0x12, "CapsLock": 0x14, "Escape": 0x1B,
    "PageUp": 0x21, "PageDown": 0x22, "End": 0x23, "Home": 0x24,
    "ArrowLeft": 0x25, "ArrowUp": 0x26, "ArrowRight": 0x27, "ArrowDown": 0x28,
    "Insert": 0x2D, "Delete": 0x2E, "Meta": 0x5B,
    "F1": 0x70, "F2": 0x71, "F3": 0x72, "F4": 0x73, "F5": 0x74, "F6": 0x75,
    "F7": 0x76, "F8": 0x77, "F9": 0x78, "F10": 0x79, "F11": 0x7A, "F12": 0x7B,
}


def to_absolute(x, y, view_w, view_h):
    """Browser pixel -> the 0..65535 grid SendInput wants.

    The page may be showing the desk scaled to any width, so the client sends
    the size it drew at and the mapping happens here rather than in JavaScript,
    where a stale value would silently offset every click.
    """
    view_w = max(1, int(view_w or SCREEN_W))
    view_h = max(1, int(view_h or SCREEN_H))
    fx = min(max(float(x) / view_w, 0.0), 1.0)
    fy = min(max(float(y) / view_h, 0.0), 1.0)
    return int(fx * 65535), int(fy * 65535)


def mouse(x, y, view_w, view_h, flags=0, data=0):
    if not IS_WINDOWS:
        return
    ax, ay = to_absolute(x, y, view_w, view_h)
    user32.mouse_event(MOUSEEVENTF_MOVE | MOUSEEVENTF_ABSOLUTE | flags,
                       ax, ay, data, 0)


def key_event(name, down):
    if not IS_WINDOWS:
        return
    flags = 0 if down else KEYEVENTF_KEYUP
    if name in VK:
        user32.keybd_event(VK[name], 0, flags, 0)
    elif len(name) == 1:
        # Unicode path: works for every printable character without caring
        # about the keyboard layout the runner happens to have.
        user32.keybd_event(0, ord(name), KEYEVENTF_UNICODE | flags, 0)


def grab_jpeg(quality=QUALITY, width=None):
    im = ImageGrab.grab().convert("RGB")
    if width and MIN_WIDTH <= width < im.width:
        height = max(1, round(im.height * width / im.width))
        im = im.resize((width, height))
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=quality)
    return buf.getvalue()


def query(path):
    """Parse ?w= and ?fps= off a request path, clamped to something sane."""
    out = {}
    if "?" in path:
        for part in path.split("?", 1)[1].split("&"):
            if "=" in part:
                k, v = part.split("=", 1)
                out[k] = v
    try:
        width = int(out.get("w", 0)) or None
    except ValueError:
        width = None
    try:
        fps = float(out.get("fps", 0)) or None
    except ValueError:
        fps = None
    if fps:
        fps = min(max(fps, 0.2), 15.0)
    return width, fps


PAGE = """<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Windows desk</title>
<style>
  html,body{margin:0;height:100%;background:#000;overflow:hidden;
            font:14px -apple-system,Segoe UI,Roboto,sans-serif;color:#ddd}
  #wrap{position:relative;width:100%;height:100%;display:flex;
        align-items:center;justify-content:center}
  img{max-width:100%;max-height:100%;display:block;touch-action:none}
  #kb{position:fixed;left:-1000px;top:0;opacity:0}
  #bar{position:fixed;right:8px;bottom:8px;display:flex;gap:6px;z-index:5}
  button{font:inherit;border:0;border-radius:8px;padding:8px 10px;
         background:#222;color:#8fe;opacity:.85}
</style></head>
<body>
<div id="wrap"><img id="d" src="/stream" draggable="false"></div>
<input id="kb" autocapitalize="off" autocomplete="off" spellcheck="false">
<div id="bar">
  <button onclick="document.getElementById('kb').focus()">Клавиатура</button>
  <button onclick="send({t:'key',k:'Meta',d:true});send({t:'key',k:'Meta',d:false})">Пуск</button>
</div>
<script>
const img = document.getElementById('d');
function send(o){
  o.w = img.clientWidth; o.h = img.clientHeight;
  fetch('/input', {method:'POST', body: JSON.stringify(o)}).catch(()=>{});
}
function at(e){
  const r = img.getBoundingClientRect();
  const p = e.touches ? e.touches[0] : e;
  return {x: p.clientX - r.left, y: p.clientY - r.top};
}
img.addEventListener('mousemove', e => { const p = at(e); send({t:'move', x:p.x, y:p.y}); });
img.addEventListener('mousedown', e => { const p = at(e); send({t:'down', x:p.x, y:p.y, b:e.button}); e.preventDefault(); });
img.addEventListener('mouseup',   e => { const p = at(e); send({t:'up',   x:p.x, y:p.y, b:e.button}); e.preventDefault(); });
img.addEventListener('contextmenu', e => e.preventDefault());
img.addEventListener('wheel', e => { const p = at(e); send({t:'wheel', x:p.x, y:p.y, d:-e.deltaY}); e.preventDefault(); }, {passive:false});
// Touch: a tap is a left click at that point.
img.addEventListener('touchstart', e => { const p = at(e); send({t:'down', x:p.x, y:p.y, b:0}); e.preventDefault(); }, {passive:false});
img.addEventListener('touchend',   e => { send({t:'up', x:-1, y:-1, b:0}); e.preventDefault(); }, {passive:false});
img.addEventListener('touchmove',  e => { const p = at(e); send({t:'move', x:p.x, y:p.y}); e.preventDefault(); }, {passive:false});
document.addEventListener('keydown', e => { send({t:'key', k:e.key, d:true});  e.preventDefault(); });
document.addEventListener('keyup',   e => { send({t:'key', k:e.key, d:false}); e.preventDefault(); });
</script></body></html>
"""


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass  # a request per frame would bury anything useful

    def _bytes(self, body, ctype):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?")[0]


        if path == "/stream":
            width, fps = query(self.path)
            self.send_response(200)
            self.send_header("Content-Type",
                             "multipart/x-mixed-replace; boundary=frame")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            delay = 1.0 / (fps or FPS)
            last = None
            last_sent = 0.0
            try:
                while True:
                    jpg = grab_jpeg(width=width)
                    now = time.monotonic()
                    # An unchanged desktop costs nothing to not send. The
                    # keepalive is measured in SECONDS, not in loop iterations:
                    # counting iterations meant a slow stream (fps=1) waited
                    # five frames, i.e. five seconds at best and much longer in
                    # practice, and a client with a 30s timeout gave up. Seen
                    # on a live desk: the thumbnail stream stalled and the read
                    # timed out.
                    if jpg == last and (now - last_sent) < 4.0:
                        time.sleep(delay)
                        continue
                    last_sent = now
                    last = jpg
                    head = (b"--frame\r\nContent-Type: image/jpeg\r\n"
                            b"Content-Length: " + str(len(jpg)).encode() + b"\r\n")
                    short = PAD_TO - len(jpg) - len(head)
                    if short > 0:
                        head += b"X-Pad: " + (b"." * short) + b"\r\n"
                    self.wfile.write(head + b"\r\n" + jpg + b"\r\n")
                    self.wfile.flush()
                    time.sleep(delay)
            except Exception:
                return  # the client went away; that is the normal exit

        if path.split("?")[0] == "/shot":
            width, _ = query(self.path)
            return self._bytes(grab_jpeg(width=width), "image/jpeg")

        if path == "/health":
            return self._bytes(b'{"ok":true}', "application/json")

        return self._bytes(PAGE.encode("utf-8"), "text/html; charset=utf-8")

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        try:
            ev = json.loads(raw.decode("utf-8") or "{}")
        except Exception:
            ev = {}
        try:
            handle_event(ev)
        except Exception:
            pass  # a bad event must never take the desk down
        return self._bytes(b"ok", "text/plain")


def handle_event(ev):
    """Apply one input event. Split out so it can be tested without a socket."""
    kind = ev.get("t")
    x, y = ev.get("x", 0), ev.get("y", 0)
    w, h = ev.get("w"), ev.get("h")

    if kind == "move":
        mouse(x, y, w, h)
    elif kind in ("down", "up"):
        down, up = BUTTON_FLAGS.get(int(ev.get("b", 0)), BUTTON_FLAGS[0])
        mouse(x, y, w, h, down if kind == "down" else up)
    elif kind == "wheel":
        mouse(x, y, w, h, MOUSEEVENTF_WHEEL, int(ev.get("d", 0)))
    elif kind == "key":
        key_event(ev.get("k", ""), bool(ev.get("d")))
    return True


def main():
    print("desk on http://127.0.0.1:%d  (%dx%d)" % (PORT, SCREEN_W, SCREEN_H),
          flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()

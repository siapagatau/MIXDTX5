#!/usr/bin/env python3
"""Generator skin 'Mixer DTX' (1500x600): satu kotak berisi SEMUA fungsi mixer, gaya panel DTX.
Pakai: python3 gen_skin_dtx.py <out_dir> [--preview]
Menulis skin.svg + skin.json (+ preview.svg bila --preview).

Warna di skin.svg memakai TOKEN yang diganti APK saat render dengan palet tema pilihan user
(SkinDefinition.themed): @ACCENT@ @ONACCENT@ @BACKDROP@ @CTRL@ @LINE@ @PANEL@ @PAD@ @TEXT@ @DIM@.
Jadi ganti warna TEMA = seluruh skin ikut berubah. Tombol FLOATING/TEMA/SKIN ada di strip luar kotak (seperti DTX),
bukan di dalam skin."""
import json, math, os, re, sys

W, H = 1500, 600
out = sys.argv[1]
os.makedirs(out, exist_ok=True)

# Palet default (tema putih/abu) untuk preview.svg & pratinjau lokal; APK memakai palet tema sebenarnya.
DEFAULT_PALETTE = {"@ACCENT@": "#FFFFFF", "@ONACCENT@": "#201512", "@BACKDROP@": "#121212", "@CTRL@": "#1D1D1D",
                   "@LINE@": "#252525", "@PANEL@": "#3C3C3C", "@PAD@": "#4D4D4D", "@TEXT@": "#C7C7C7", "@DIM@": "#949494"}

bg, deco, ctrl, hit = [], [], [], []
controls, mapping = [], {}
FONT = 'font-family="sans-serif"'
ACC, ONA, CTRL, LINE, PANEL, PAD, TEXT, DIM = "@ACCENT@", "@ONACCENT@", "@CTRL@", "@LINE@", "@PANEL@", "@PAD@", "@TEXT@", "@DIM@"


def section(x1, y1, x2, y2, title):
    deco.append(f'<rect x="{x1}" y="{y1}" width="{x2-x1}" height="{y2-y1}" rx="8" fill="{CTRL}" stroke="{LINE}" stroke-width="2"/>')
    deco.append(f'<text x="{x1+18}" y="{y1+34}" {FONT} font-size="21" font-weight="bold" letter-spacing="1" fill="{DIM}">{title}</text>')
    deco.append(f'<line x1="{x1+14}" y1="{y1+52}" x2="{x2-14}" y2="{y1+52}" stroke="{LINE}" stroke-width="2"/>')


def label(cx, y, t):
    deco.append(f'<text x="{cx}" y="{y}" text-anchor="middle" {FONT} font-size="19" font-weight="bold" fill="{TEXT}">{t}</text>')


def hitrect(i, x, y, w, h):
    hit.append(f'<rect id="hit-{i}" x="{x}" y="{y}" width="{w}" height="{h}" fill="#000" fill-opacity="0"/>')


def toggle(name, x2, y1, fn):
    """Tombol ON/OFF persegi di pojok kanan atas bagian (gaya tombol DTX: mati = abu, hidup = warna tema)."""
    i, w, h = f"tg-{name}", 84, 34
    x, y = x2 - 16 - w, y1 + 9
    ctrl.append(f'<g id="{i}" data-type="toggle">'
                f'<g id="{i}-off"><rect x="{x}" y="{y}" width="{w}" height="{h}" rx="6" fill="{PANEL}"/>'
                f'<text x="{x+w/2}" y="{y+23}" text-anchor="middle" {FONT} font-size="18" font-weight="bold" fill="{DIM}">OFF</text></g>'
                f'<g id="{i}-on" display="none"><rect x="{x}" y="{y}" width="{w}" height="{h}" rx="6" fill="{ACC}"/>'
                f'<text x="{x+w/2}" y="{y+23}" text-anchor="middle" {FONT} font-size="18" font-weight="bold" fill="{ONA}">ON</text></g></g>')
    hitrect(i, x, y, w, h)
    controls.append({"id": i, "type": "toggle", "hit": f"hit-{i}", "states": {"off": f"{i}-off", "on": f"{i}-on"}})
    mapping[i] = {"function": fn}


def pt(cx, cy, r, deg):
    a = math.radians(deg)
    return cx + r * math.sin(a), cy - r * math.cos(a)


def knob(name, cx, cy, text, fn, default=0.5):
    i, r = f"knob-{name}", 34
    x0, y0 = pt(cx, cy, r + 8, -135)
    x1, y1 = pt(cx, cy, r + 8, 135)
    ticks = "".join('<line x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f" stroke="%s" stroke-width="3" stroke-linecap="round"/>'
                    % (*pt(cx, cy, r + 11, -135 + 45 * k), *pt(cx, cy, r + 16, -135 + 45 * k), PAD) for k in range(7))
    deco.append(f'<g>{ticks}</g>')
    ctrl.append(f'<g id="{i}" data-type="rotary" data-cx="{cx}" data-cy="{cy}" data-angle-min="-135" data-angle-max="135" data-value-default="{default}">'
                f'<path id="{i}-track" d="M {x0:.1f} {y0:.1f} A {r+8} {r+8} 0 1 1 {x1:.1f} {y1:.1f}" fill="none" stroke="{PANEL}" stroke-width="5" stroke-linecap="round"/>'
                f'<circle cx="{cx}" cy="{cy}" r="{r+3}" fill="{LINE}"/>'
                f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="url(#knobGrad)" stroke="{LINE}" stroke-width="2"/>'
                f'<circle cx="{cx}" cy="{cy}" r="{r-12}" fill="{CTRL}" fill-opacity="0.55"/>'
                f'<g id="{i}-handle"><line x1="{cx}" y1="{cy-5}" x2="{cx}" y2="{cy-r+5}" stroke="{ACC}" stroke-width="6" stroke-linecap="round"/></g></g>')
    label(cx, cy + 70, text)
    hitrect(i, cx - 48, cy - 48, 96, 96)
    controls.append({"id": i, "type": "rotary", "hit": f"hit-{i}", "track": f"{i}-track", "handle": f"{i}-handle",
                     "angleMin": -135, "angleMax": 135, "defaultValue": default})
    mapping[i] = {"function": fn}


def slider(name, cx, yb, yt, text, fn, default=0.5):
    i = f"sl-{name}"
    ym = yb + (yt - yb) * default
    ticks = "".join(
        f'<line x1="{cx-40}" y1="{yb+(yt-yb)*k/4:.1f}" x2="{cx-31}" y2="{yb+(yt-yb)*k/4:.1f}" stroke="{PAD}" stroke-width="3" stroke-linecap="round"/>'
        f'<line x1="{cx+31}" y1="{yb+(yt-yb)*k/4:.1f}" x2="{cx+40}" y2="{yb+(yt-yb)*k/4:.1f}" stroke="{PAD}" stroke-width="3" stroke-linecap="round"/>'
        for k in range(5))
    deco.append(f'<g>{ticks}</g>')
    ctrl.append(f'<g id="{i}" data-type="slider" data-x1="{cx}" data-y1="{yb}" data-x2="{cx}" data-y2="{yt}" data-value-default="{default}">'
                f'<line id="{i}-track" x1="{cx}" y1="{yb}" x2="{cx}" y2="{yt}" stroke="{LINE}" stroke-width="12" stroke-linecap="round"/>'
                f'<line x1="{cx}" y1="{yb}" x2="{cx}" y2="{yt}" stroke="{PANEL}" stroke-width="3" stroke-linecap="round"/>'
                f'<g id="{i}-handle"><rect x="{cx-26}" y="{ym-15}" width="52" height="30" rx="6" fill="url(#knobGrad)" stroke="{LINE}" stroke-width="2"/>'
                f'<line x1="{cx-20}" y1="{ym}" x2="{cx+20}" y2="{ym}" stroke="{ACC}" stroke-width="5" stroke-linecap="round"/></g></g>')
    label(cx, yb + 40, text)
    hitrect(i, cx - 34, yt - 20, 68, yb - yt + 40)
    controls.append({"id": i, "type": "slider", "hit": f"hit-{i}", "track": f"{i}-track", "handle": f"{i}-handle", "defaultValue": default})
    mapping[i] = {"function": fn}


def meter(name, x, y, text, fn, n=24, sw=28, gap=5):
    i = f"meter-{name}"
    segs, dims = [], []
    for k in range(n):
        c = ACC if k < n * 0.6 else ("#FFD43B" if k < n * 0.82 else "#FF4B4B")
        sx = x + k * (sw + gap)
        dims.append(f'<rect x="{sx}" y="{y}" width="{sw}" height="22" rx="3" fill="{c}" fill-opacity="0.18"/>')
        segs.append(f'<rect id="{i}-seg-{k+1}" x="{sx}" y="{y}" width="{sw}" height="22" rx="3" fill="{c}" display="none"/>')
    tw = n * (sw + gap) - gap
    deco.append(f'<rect x="{x-8}" y="{y-6}" width="{tw+16}" height="34" rx="6" fill="{CTRL}" stroke="{LINE}" stroke-width="2"/>')
    deco.append(f'<g id="deco-{i}-dim">{"".join(dims)}</g>')
    deco.append(f'<text x="{x-20}" y="{y+18}" text-anchor="end" {FONT} font-size="19" font-weight="bold" fill="{DIM}">{text}</text>')
    ctrl.append(f'<g id="{i}" data-type="meter">{"".join(segs)}</g>')
    controls.append({"id": i, "type": "meter", "segments": [f"{i}-seg-{k+1}" for k in range(n)]})
    mapping[i] = {"function": fn}


# ---- latar + header (LCD kiri, meter kanan) ----
bg.append(f'<rect x="0" y="0" width="{W}" height="{H}" fill="{PANEL}"/>')
ctrl.append(f'<g id="slot-display"><rect x="20" y="14" width="520" height="86" rx="8" fill="{CTRL}" stroke="{LINE}" stroke-width="2"/>'
            f'<text x="36" y="48" font-family="monospace" font-size="24" fill="{ACC}">MIC OFF</text></g>')
controls.append({"id": "slot-display", "type": "display", "lines": 2})
mapping["slot-display"] = {"function": "display.status",
                           "template": ["MIC {mic:ONOFF}  REV {reverb:ONOFF}", "ECHO {echo:ONOFF}  EQ {eqmic:ONOFF}"], "textColor": "#FFFFFF"}
meter("mic", 650, 24, "MIC", "meter.mic")
meter("output", 650, 66, "OUT", "meter.output")

# ---- baris 1: MIC | REVERB | ECHO ----
R1T, R1B = 118, 348
section(20, R1T, 340, R1B, "MIC")
toggle("mic", 340, R1T, "mic.toggle")
knob("mic-volume", 100, R1T + 130, "VOLUME", "mic.volume")
knob("mic-gate", 255, R1T + 130, "GATE", "mic.gate")

section(352, R1T, 1000, R1B, "REVERB")
toggle("reverb", 1000, R1T, "reverb.toggle")
for k, (n, t, f, d) in enumerate([("wet", "WET", "reverb.wet", 0.35), ("dry", "DRY", "reverb.dry", 0.85), ("width", "WIDTH", "reverb.width", 1.0),
                                  ("damp", "DAMP", "reverb.damp", 0.3), ("room", "ROOM", "reverb.room", 0.6), ("cess", "CESS", "reverb.cess", 0.6)]):
    knob(f"reverb-{n}", 418 + 103 * k, R1T + 130, t, f, d)

section(1012, R1T, 1480, R1B, "ECHO")
toggle("echo", 1480, R1T, "echo.toggle")
for k, (n, t, f) in enumerate([("delay", "DELAY", "echo.delay"), ("feedback", "REPEAT", "echo.feedback"), ("mix", "MIX", "echo.mix")]):
    knob(f"echo-{n}", 1100 + 145 * k, R1T + 130, t, f)

# ---- baris 2: EQ MIC | EQ AUDIO ----
R2T, R2B = 360, 584
section(20, R2T, 480, R2B, "EQ MIC")
toggle("eqmic", 480, R2T, "eqmic.toggle")
for k, (n, t) in enumerate([("low", "LOW"), ("mid", "MID"), ("high", "HIGH")]):
    knob(f"eqmic-{n}", 110 + 145 * k, R2T + 128, t, f"eqmic.{n}")

section(492, R2T, 1480, R2B, "EQ AUDIO  (suara aplikasi lain)")
toggle("eqaudio", 1480, R2T, "eqaudio.toggle")
for k, (n, t) in enumerate([("gain", "GAIN"), ("sub", "SUB"), ("low", "LOW"), ("mid", "MID"), ("high", "HIGH")]):
    slider(f"eqaudio-{n}", 620 + 188 * k, R2T + 168, R2T + 78, t, f"eqaudio.{n}")

defs = ('<defs>'
        f'<radialGradient id="knobGrad" cx="38%" cy="32%" r="80%"><stop offset="0" stop-color="{PAD}"/><stop offset="1" stop-color="{CTRL}"/></radialGradient>'
        '</defs>')
svg = (f'<svg xmlns="http://www.w3.org/2000/svg" data-skin-name="Mixer DTX" data-skin-version="2.0" data-skin-author="Mixer" '
       f'width="{W}" height="{H}" viewBox="0 0 {W} {H}">{defs}'
       f'<g id="layer-bg">{"".join(bg)}</g><g id="layer-deco">{"".join(deco)}</g>'
       f'<g id="layer-controls">{"".join(ctrl)}</g><g id="layer-hit">{"".join(hit)}</g></svg>')
open(os.path.join(out, "skin.svg"), "w", encoding="utf-8").write(svg)

js = {"schemaVersion": 1, "kind": "mixer", "name": "Mixer DTX", "version": "2.0", "author": "Mixer",
      "svg": "skin.svg", "viewBox": f"0 0 {W} {H}", "layout": {"zone": f"0 0 {W} {H}"},
      "controls": controls, "mapping": mapping}
open(os.path.join(out, "skin.json"), "w", encoding="utf-8").write(json.dumps(js, indent=1, ensure_ascii=False))


def themed(text, pal=DEFAULT_PALETTE):
    for k, v in pal.items():
        text = text.replace(k, v)
    return text


if "--preview" in sys.argv:
    # preview.svg: skin dengan palet default + overlay hit area (dipakai pemilih skin)
    s = themed(svg)
    ov = []
    for m in re.finditer(r'<rect id="hit-([^"]+)" x="([\d.]+)" y="([\d.]+)" width="([\d.]+)" height="([\d.]+)"', s):
        i, x, y, w, h = m.group(1), *map(float, m.groups()[1:])
        ov.append(f'<rect x="{x:g}" y="{y:g}" width="{w:g}" height="{h:g}" fill="#ff2d95" fill-opacity="0.18" stroke="#ff2d95" stroke-width="2"/>')
    open(os.path.join(out, "preview.svg"), "w", encoding="utf-8").write(s.replace("</svg>", f'<g id="preview-overlay">{"".join(ov)}</g></svg>'))
# salinan berwarna (palet default) untuk dilihat di browser
if "--look" in sys.argv: open(os.path.join(out, "_look.svg"), "w", encoding="utf-8").write(themed(svg))
print("ok", out, len(controls), "kontrol")

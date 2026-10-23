#!/usr/bin/env python3
"""Validator skin mixer (PROMPT_SKIN_MIXER §3,4,6). Pakai: python3 validate.py <dir skin> [...]"""
import json, os, re, sys
import xml.etree.ElementTree as ET

FORBID = {"script","foreignObject","image","use","audio","video","iframe","metadata","filter"}
TOGGLE = {"mic.toggle","reverb.toggle","echo.toggle","eqmic.toggle","eqaudio.toggle"}
VALUE = {"mic.volume","mic.gate","reverb.wet","reverb.dry","reverb.width","reverb.damp","reverb.room","reverb.cess",
         "echo.delay","echo.feedback","echo.mix","eqmic.low","eqmic.mid","eqmic.high",
         "eqaudio.gain","eqaudio.sub","eqaudio.low","eqaudio.mid","eqaudio.high"}
ALLOWED = {"toggle": TOGGLE, "rotary": VALUE, "slider": VALUE, "meter": {"meter.mic","meter.output"},
           "button": {"floating.toggle","theme.open","skin.pick"}, "display": {"display.status"}}

def tag(e): return e.tag.split('}')[-1]

def check(d):
    err, warn = [], []
    js = json.load(open(os.path.join(d, "skin.json"), encoding="utf-8"))
    svgp = js.get("svg", "skin.svg")
    if "/" in svgp or "\\" in svgp or ".." in svgp: err.append("nama svg tidak valid")
    p = os.path.join(d, svgp)
    if os.path.getsize(p) > 4*1024*1024: err.append("svg > 4 MB")
    txt = open(p, encoding="utf-8").read()
    if re.search(r"<!DOCTYPE|<!ENTITY", txt, re.I): err.append("DOCTYPE/ENTITY")
    root = ET.fromstring(txt)
    els = list(root.iter())
    if len(els) > 20000: err.append("elemen > 20000")
    for a in ["xmlns","data-skin-name","data-skin-version","data-skin-author","width","height","viewBox"]:
        if a not in root.attrib and not (a == "xmlns" and root.tag.startswith("{http://www.w3.org/2000/svg}")):
            err.append(f"root tanpa {a}")
    vb = [float(v) for v in re.split(r"[\s,]+", root.get("viewBox","").strip())]
    if vb[2] < vb[3]: warn.append("viewBox bukan landscape")
    ids = {}
    for e in els:
        t = tag(e)
        if t in FORBID: err.append(f"tag terlarang <{t}>")
        for k, v in e.attrib.items():
            kk = k.split('}')[-1]
            if kk.startswith("on"): err.append(f"atribut {kk}")
            if kk == "href": err.append("href")
            if re.search(r"https?://", v) and not k.startswith("xmlns"): err.append(f"URL di {kk}")
        i = e.get("id")
        if i:
            if i in ids: err.append(f"ID ganda {i}")
            ids[i] = e
    for l in ["layer-bg","layer-deco","layer-controls","layer-hit"]:
        if l not in ids: warn.append(f"layer {l} tidak ada")
    def rect_of(e):
        r = e if tag(e) == "rect" else next((x for x in e.iter() if tag(x) == "rect"), None)
        return None if r is None else tuple(float(r.get(k, "nan")) for k in ("x","y","width","height"))
    hits = []
    for c in js["controls"]:
        cid, ty = c["id"], c["type"]
        fn = js["mapping"].get(cid, {}).get("function")
        if cid not in ids: err.append(f"{cid}: tidak ada di SVG")
        if not fn: err.append(f"{cid}: tanpa mapping")
        elif fn not in ALLOWED[ty]: err.append(f"{cid}: function {fn} tidak cocok/tidak resmi untuk {ty}")
        if ty in ("button","toggle","rotary","slider"):
            h = ids.get(c.get("hit",""))
            if h is None: err.append(f"{cid}: hit tidak ada"); continue
            x, y, w, hh = rect_of(h)
            if x < vb[0] or y < vb[1] or x+w > vb[0]+vb[2] or y+hh > vb[1]+vb[3]: err.append(f"{cid}: hit di luar viewBox")
            hits.append((cid, x, y, w, hh))
        if ty == "toggle":
            for s in ("on","off"):
                if c["states"][s] not in ids: err.append(f"{cid}: state {s} tidak ada")
            if ids.get(c["states"]["on"]) is not None and ids[c["states"]["on"]].get("display") != "none": warn.append(f"{cid}: -on tanpa display=none")
        if ty in ("rotary","slider") and c.get("handle") not in ids: err.append(f"{cid}: handle tidak ada")
        if ty == "rotary":
            g = ids.get(cid)
            if g is not None and not all(k in g.attrib for k in ("data-cx","data-cy","data-angle-min","data-angle-max","data-value-default")): err.append(f"{cid}: data-* rotary kurang")
        if ty == "slider":
            g = ids.get(cid)
            if g is not None and not all(k in g.attrib for k in ("data-x1","data-y1","data-x2","data-y2")): err.append(f"{cid}: data-* slider kurang")
        if ty == "meter":
            for s in c["segments"]:
                if s not in ids: err.append(f"{cid}: segmen {s} tidak ada")
                elif rect_of(ids[s]) is None: err.append(f"{cid}: segmen {s} tanpa rect")
                elif ids[s].get("display") != "none": warn.append(f"{s}: tanpa display=none")
        if ty == "display" and (cid not in ids or rect_of(ids[cid]) is None): err.append(f"{cid}: display tanpa rect")
    for i in range(len(hits)):
        for j in range(i+1, len(hits)):
            a, b = hits[i], hits[j]
            if a[1] < b[1]+b[3] and b[1] < a[1]+a[3] and a[2] < b[2]+b[4] and b[2] < a[2]+a[4]: err.append(f"hit tumpang tindih {a[0]} & {b[0]}")
    for k in js.get("unsupportedInReference", []):
        if k.get("id") not in ids: warn.append(f"unsupported {k.get('id')} tidak ada di SVG")
    return err, warn

if __name__ == "__main__":
    bad = 0
    for d in sys.argv[1:]:
        e, w = check(d)
        print(d, "OK" if not e else "GAGAL", f"({len(w)} peringatan)")
        for m in e: print("  ERR ", m)
        for m in w: print("  warn", m)
        bad += bool(e)
    sys.exit(1 if bad else 0)

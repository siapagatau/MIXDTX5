#!/usr/bin/env python3
"""index.json repo (sha256 + size). Jalankan SETELAH file skin final.
Pakai: python3 index.py <root repo>          -> tulis index.json
       python3 index.py <root repo> --check  -> cek index.json cocok dengan file"""
import hashlib, json, os, sys, datetime

FILES = ("skin.json", "skin.svg", "preview.svg")
def info(p):
    b = open(p, "rb").read()
    return {"sha256": hashlib.sha256(b).hexdigest(), "size": len(b)}

def build(root):
    skins = []
    for sid in sorted(os.listdir(os.path.join(root, "skins"))):
        d = os.path.join(root, "skins", sid)
        if not os.path.isdir(d): continue
        js = json.load(open(os.path.join(d, "skin.json"), encoding="utf-8"))
        skins.append({"id": sid, "name": js["name"], "version": js["version"], "author": js["author"],
                      "path": f"skins/{sid}/", "files": {f: info(os.path.join(d, f)) for f in FILES}})
    return {"schemaVersion": 1, "repo": "skin-mixer-hp", "kind": "mixer",
            "updated": datetime.date.today().isoformat(), "skins": skins}

if __name__ == "__main__":
    root = sys.argv[1]
    if "--check" in sys.argv:
        idx = json.load(open(os.path.join(root, "index.json"), encoding="utf-8")); bad = 0
        for s in idx["skins"]:
            for f, v in s["files"].items():
                if info(os.path.join(root, s["path"], f)) != v: print("TIDAK COCOK", s["id"], f); bad += 1
        print("index OK" if not bad else "index GAGAL"); sys.exit(1 if bad else 0)
    idx = build(root)
    open(os.path.join(root, "index.json"), "w", encoding="utf-8").write(json.dumps(idx, indent=2, ensure_ascii=False) + "\n")
    print("index.json:", len(idx["skins"]), "skin")

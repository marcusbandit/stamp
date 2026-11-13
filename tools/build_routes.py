"""Build a country's rail and big-road network for Stamp from OpenStreetMap.

OpenStreetMap data is (c) OpenStreetMap contributors, ODbL. The packs are served from a
separate public data repository and downloaded by the app when a country gets a trip, so a
train or car leg can follow the real line between its stops.

Run with a Python that has pyosmium (pip install osmium):
  python3 -I tools/build_routes.py --out <dir> --cache <dir> JP DE AT ...

Each country's extract comes from Geofabrik, is read twice (the chosen ways, then only their
nodes) and deleted afterwards. Kept: railway=rail that is not a siding, yard or spur, and
highway=motorway, trunk and their links. Ways are cut at every junction, the stretch between
two junctions simplified to about 30 m, and written as <CC>.bin.gz:

  "SRT1"
  varint node count, then each node as zigzag varint deltas of lat and lon in 1e-5 degrees
  varint segment count, then each segment as
    u8 kind (0 rail, 1 road), varint node a, varint node b, varint point count,
    points between a and b as zigzag varint deltas, starting from node a
"""
import argparse
import gzip
import json
import os
import struct
import sys
import time
import urllib.request

import osmium

INDEX_URL = "https://download.geofabrik.de/index-v1-nogeom.json"
RAIL, ROAD = 0, 1
ROAD_TAGS = ("motorway", "trunk", "motorway_link", "trunk_link")
SKIP_SERVICE = {"siding", "yard", "spur", "crossover"}
EPS = 0.0003  # degrees, about 30 m
UA = "stamp-route-builder/1 (+https://github.com/marcusbandit/stamp)"


def fetch(url, path):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req) as r, open(path + ".part", "wb") as f:
        while True:
            b = r.read(1 << 20)
            if not b:
                break
            f.write(b)
    os.replace(path + ".part", path)


def extract_url(index, cc):
    """The one Geofabrik extract that covers country [cc], the biggest when there are several."""
    best = None
    for f in index["features"]:
        p = f["properties"]
        codes = p.get("iso3166-1:alpha2") or []
        pbf = (p.get("urls") or {}).get("pbf")
        if cc in codes and pbf and len(codes) == 1:
            # A country beats its own regions: the shortest id path is the whole country.
            depth = p["id"].count("/") + (1 if p.get("parent") else 0)
            if best is None or depth < best[0]:
                best = (depth, pbf)
    return best[1] if best else None


def kind_of(tags):
    if tags.get("railway") == "rail" and tags.get("service") not in SKIP_SERVICE and "service" not in tags:
        return RAIL
    if tags.get("highway") in ROAD_TAGS:
        return ROAD
    return None


def rdp(pts, eps):
    if len(pts) < 3:
        return pts
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        a, b = stack.pop()
        (y1, x1), (y2, x2) = pts[a], pts[b]
        dx, dy = x2 - x1, y2 - y1
        n = (dx * dx + dy * dy) ** 0.5 or 1e-12
        far, at = 0.0, -1
        for i in range(a + 1, b):
            y, x = pts[i]
            d = abs(dy * (x - x1) - dx * (y - y1)) / n
            if d > far:
                far, at = d, i
        if far > eps and at > 0:
            keep[at] = True
            stack.append((a, at))
            stack.append((at, b))
    return [p for p, k in zip(pts, keep) if k]


def varint(n, out):
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return


def zigzag(n):
    return (n << 1) ^ (n >> 63)


def build(pbf, out_path):
    tags = [("railway", "rail")] + [("highway", t) for t in ROAD_TAGS]
    ways = []
    for w in osmium.FileProcessor(pbf, osmium.osm.WAY).with_filter(osmium.filter.TagFilter(*tags)):
        k = kind_of(w.tags)
        if k is not None and len(w.nodes) >= 2:
            ways.append((k, [n.ref for n in w.nodes]))
    need = {n for _, refs in ways for n in refs}
    loc = {}
    for n in osmium.FileProcessor(pbf, osmium.osm.NODE).with_filter(osmium.filter.IdFilter(need)):
        if n.location.valid():
            loc[n.id] = (round(n.location.lat * 1e5), round(n.location.lon * 1e5))
    # A junction is any node two ways share, or the end of a way.
    uses = {}
    for _, refs in ways:
        for i, r in enumerate(refs):
            uses[r] = uses.get(r, 0) + (2 if i in (0, len(refs) - 1) else 1)
    node_ix = {}
    nodes = []
    segs = []

    def ix(r):
        if r not in node_ix:
            node_ix[r] = len(nodes)
            nodes.append(loc[r])
        return node_ix[r]

    for k, refs in ways:
        refs = [r for r in refs if r in loc]
        if len(refs) < 2:
            continue
        start = 0
        for i in range(1, len(refs)):
            if i == len(refs) - 1 or uses.get(refs[i], 0) > 1:
                part = refs[start:i + 1]
                pts = rdp([loc[r] for r in part], EPS * 1e5)
                segs.append((k, ix(part[0]), ix(part[-1]), pts[1:-1]))
                start = i
    out = bytearray(b"SRT1")
    varint(len(nodes), out)
    py = px = 0
    for y, x in nodes:
        varint(zigzag(y - py), out); varint(zigzag(x - px), out)
        py, px = y, x
    varint(len(segs), out)
    for k, a, b, pts in segs:
        out.append(k)
        varint(a, out); varint(b, out); varint(len(pts), out)
        py, px = nodes[a]
        for y, x in pts:
            varint(zigzag(y - py), out); varint(zigzag(x - px), out)
            py, px = y, x
    with gzip.open(out_path, "wb", compresslevel=9) as f:
        f.write(bytes(out))
    rails = sum(1 for s in segs if s[0] == RAIL)
    return len(nodes), rails, len(segs) - rails


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--cache", required=True)
    ap.add_argument("--keep", action="store_true", help="keep the downloaded extracts")
    ap.add_argument("codes", nargs="+")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    os.makedirs(a.cache, exist_ok=True)
    idx_path = os.path.join(a.cache, "geofabrik-index.json")
    if not os.path.exists(idx_path) or time.time() - os.path.getmtime(idx_path) > 7 * 86400:
        fetch(INDEX_URL, idx_path)
    index = json.load(open(idx_path))
    for cc in (c.upper() for c in a.codes):
        out_path = os.path.join(a.out, cc + ".bin.gz")
        url = extract_url(index, cc)
        if not url:
            print(cc, "no extract", flush=True)
            continue
        pbf = os.path.join(a.cache, cc + ".osm.pbf")
        t0 = time.time()
        if not os.path.exists(pbf):
            fetch(url, pbf)
        n, r, d = build(pbf, out_path)
        if not a.keep:
            os.remove(pbf)
        print(cc, n, "nodes", r, "rail", d, "road", os.path.getsize(out_path) // 1024, "KB",
              int(time.time() - t0), "s", flush=True)


if __name__ == "__main__":
    sys.exit(main())

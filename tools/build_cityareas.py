#!/usr/bin/env python3
"""Build simplified city boundary polygons keyed by GeoNames id.

Pipeline (all network steps cached under ./download, so reruns are free):
  1. cities.tsv rows with population >= MIN_POP (or the 30 city sample)
  2. Wikidata SPARQL: GeoNames id (P1566) -> item, plus OSM relation id (P402)
  3. Overpass: fetch the relations by id / wikidata tag, 10 cities per request
     (fallback for cities with no Wikidata link: Overpass is_in on the point)
  4. shapely: assemble member ways into polygons, drop far islands, drop tiny
     holes and slivers, Douglas-Peucker simplify, round to 1e-4 deg (~11 m)
  5. write cityareas.geojson, cityareas.bin (varint deltas), report.json

Run with a venv that has shapely and pyshp, from a working directory of its own (the
download cache lands next to the script, so copy it there first):
  python3 -m venv venv && venv/bin/pip install shapely pyshp
  venv/bin/python -I build_cityareas.py --all --land download/ne/ne_10m_land.shp --out out
  cp out/cityareas.bin <repo>/app/src/main/assets/
The whole run is about 5 hours of polite Overpass requests; reruns come from the cache.
Data (c) OpenStreetMap contributors, ODbL.
"""
import argparse
import hashlib
import json
import math
import os
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import zlib

from shapely.geometry import LineString, MultiPolygon, Point, Polygon, mapping
from shapely.ops import polygonize, unary_union
from shapely.validation import make_valid

HERE = os.path.dirname(os.path.abspath(__file__))
DL = os.path.join(HERE, "download")
UA = "stamp-cityareas/1.0 (offline travel map; build-time only)"
SPARQL = "https://query.wikidata.org/sparql"
OVERPASS = ["https://overpass.openstreetmap.fr/api/interpreter",
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter"]
KM = 111.2  # km per degree of latitude

# The 30 city sample, GeoNames ids.
SAMPLE = [1850147, 2988507, 5128581, 2618425, 1853909, 3448439, 2332459, 1275339,
          2643743, 2950159, 3117735, 3169070, 524901, 745044, 360630, 1816670,
          1835848, 3530597, 1273294, 1642911, 184745, 1880252, 2911298, 3413829,
          993800, 5368361, 6167865, 3435910, 2147714, 1857910, 1848354, 3936456]

# Cities where the GeoNames place is not what Wikidata calls the same thing.
# Tokyo: GeoNames means the built-up 23 wards (+ Tama), Wikidata Q1490 is the
# whole prefecture incl. the Izu and Ogasawara islands. Build it from children.
CHILD_CLASSES = {
    "wards": ["Q5327704"],                                   # special ward of Japan
    "tama": ["Q5327704", "Q494721", "Q1059478", "Q4174776"],  # + city, town, village
}


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def progress(stage, done, total):
    """One line in ./progress for anyone watching: stage, done, total, and a bar."""
    n = 30
    fill = int(n * done / total) if total else n
    line = f"{stage}  [{'#' * fill}{'.' * (n - fill)}] {done}/{total}  {100 * done // max(total, 1)}%"
    try:
        with open(os.path.join(HERE, "progress"), "w") as f:
            f.write(line + "\n")
    except OSError:
        pass


def norm(s):
    s = unicodedata.normalize("NFKD", s or "")
    return "".join(c for c in s if not unicodedata.combining(c)).casefold().strip()


def cached(path, producer):
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    data = producer()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f)
    return data


def http(url, data=None, accept="application/json", timeout=400):
    req = urllib.request.Request(url, data=data, headers={"User-Agent": UA, "Accept": accept})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def sparql(query):
    """POST (no URL length limit). Honours Retry-After: WDQS can answer 429 with
    retry-after of 1000 s when a client bursts, so never retry blindly."""
    body = urllib.parse.urlencode({"query": query}).encode()
    for attempt in range(6):
        try:
            return json.loads(http(SPARQL, data=body, accept="application/sparql-results+json"))
        except urllib.error.HTTPError as e:
            wait = min(int(e.headers.get("Retry-After", "60") or 60), 1200) + 5
            log("sparql", e.code, f"waiting {wait}s")
            time.sleep(wait)
    raise RuntimeError("sparql failed")


def overpass(query):
    body = urllib.parse.urlencode({"data": query}).encode()
    for attempt in range(3):
        url = OVERPASS[attempt % len(OVERPASS)]
        try:
            raw = http(url, data=body)
            return json.loads(raw)
        except (urllib.error.HTTPError, urllib.error.URLError, json.JSONDecodeError, TimeoutError) as e:
            wait = 30 * (attempt + 1)
            log("overpass", url.split("/")[2], getattr(e, "code", e), f"retry in {wait}s")
            time.sleep(wait)
    raise RuntimeError("overpass failed")


def cached_overpass(query, polite=6.0):
    key = hashlib.sha1(query.encode()).hexdigest()[:16]
    path = os.path.join(DL, "overpass", key + ".json")
    if os.path.exists(path):
        return cached(path, None)
    time.sleep(polite)  # be polite between live requests
    return cached(path, lambda: overpass(query))


# ---------------------------------------------------------------- 1. cities

def load_cities(path, min_pop, sample, ids=None):
    rows = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            c = dict(id=int(p[0]), name=p[1], ascii=p[2], cc=p[3], lat=float(p[5]),
                     lon=float(p[6]), pop=int(p[7]))
            if c["pop"] >= min_pop:
                rows[c["id"]] = c
    if ids:
        rows = {i: rows[i] for i in ids if i in rows}
    elif sample:
        rows = {i: rows[i] for i in SAMPLE if i in rows}
    return rows


# ------------------------------------------------------------- 2. wikidata

def wikidata_links(ids):
    """gn id -> (qid, osm relation id or None). One query per 300 ids."""
    out = {}
    ids = sorted(ids)
    for n in range(0, len(ids), 300):
        chunk = ids[n:n + 300]
        q = ("SELECT ?gn ?item ?osm WHERE { VALUES ?gn { %s } ?item wdt:P1566 ?gn. "
             "OPTIONAL { ?item wdt:P402 ?osm } }" % " ".join(f'"{i}"' for i in chunk))
        key = hashlib.sha1(q.encode()).hexdigest()[:12]
        res = cached(os.path.join(DL, "wikidata", f"links_{key}.json"), lambda q=q: sparql(q))
        for b in res["results"]["bindings"]:
            gn = int(b["gn"]["value"])
            qid = b["item"]["value"].rsplit("/", 1)[-1]
            osm = int(b["osm"]["value"]) if "osm" in b and b["osm"]["value"].isdigit() else None
            if gn not in out or (osm and not out[gn][1]):
                out[gn] = (qid, osm)
        time.sleep(1.5)
    return out


def wikidata_children(parent_qid, classes):
    q = ("SELECT ?t ?osm WHERE { wd:%s wdt:P150 ?t. ?t wdt:P31 ?c. VALUES ?c { %s } "
         "OPTIONAL { ?t wdt:P402 ?osm } }" % (parent_qid, " ".join("wd:" + c for c in classes)))
    key = hashlib.sha1(q.encode()).hexdigest()[:12]
    res = cached(os.path.join(DL, "wikidata", f"children_{key}.json"), lambda: sparql(q))
    kids = {}
    for b in res["results"]["bindings"]:
        qid = b["t"]["value"].rsplit("/", 1)[-1]
        osm = int(b["osm"]["value"]) if "osm" in b and b["osm"]["value"].isdigit() else None
        kids[qid] = osm or kids.get(qid)
    return kids


# -------------------------------------------------------------- 3. overpass

def fetch_relations(items, batch=6):
    """items: {key: (qid, osm_id)}. Returns {relation id: element}.
    A batch that times out is split in half and retried, down to single cities."""
    elements = {}
    keys = list(items)

    def run(part):
        ids = sorted({o for _, o in part if o})
        stmts = []
        if ids:
            stmts.append("rel(id:%s);" % ",".join(map(str, ids)))
        for qid, _ in part:
            stmts.append('rel["wikidata"="%s"]["boundary"~"administrative|statistical"];' % qid)
        q = ("[out:json][timeout:300][maxsize:1073741824];(%s);out geom qt;" % "".join(stmts))
        try:
            res = cached_overpass(q)
        except RuntimeError:
            if len(part) == 1:
                log("giving up on", part)
                return
            h = len(part) // 2
            log(f"splitting batch of {len(part)}")
            run(part[:h])
            run(part[h:])
            return
        for el in res["elements"]:
            if el["type"] == "relation":
                elements[el["id"]] = el

    for n in range(0, len(keys), batch):
        log(f"overpass batch {n // batch + 1}/{math.ceil(len(keys) / batch)}")
        progress("1/2 boundaries", n // batch + 1, math.ceil(len(keys) / batch))
        run([items[k] for k in keys[n:n + batch]])
    return elements


def is_area_relation(e):
    t = e.get("tags", {})
    return t.get("type") in ("boundary", "multipolygon") and (
        t.get("boundary") in ("administrative", "statistical") or t.get("place"))


def candidates(qid, osm_id, elements):
    """OSM relations that may represent this Wikidata item, best first.
    Wikidata's P402 is wrong often enough (Mumbai's points at a bus route) that
    every candidate must be an area relation and is checked against the point later."""
    out = []
    if osm_id in elements and is_area_relation(elements[osm_id]):
        out.append(elements[osm_id])
    tagged = [e for e in elements.values() if e.get("tags", {}).get("wikidata") == qid
              and is_area_relation(e) and e not in out]

    def key(e):
        try:
            lvl = int(e["tags"].get("admin_level", "99"))
        except ValueError:
            lvl = 99
        return (e["tags"].get("boundary") != "administrative", lvl)
    return out + sorted(tagged, key=key)


def fallback_relation(city):
    """No usable Wikidata link: admin relations containing the point whose name
    matches the city. Exact name beats 'contains', then the smallest unit
    (highest admin_level up to 8) wins; levels 2-4 only as a last resort, which
    gives region-sized results (Singapore is a country, Istanbul a province)."""
    q = ('[out:json][timeout:60];is_in(%f,%f)->.a;'
         'rel(pivot.a)["boundary"="administrative"]["admin_level"~"^[2-9]$"];out tags;'
         % (city["lat"], city["lon"]))
    res = cached_overpass(q)
    names = {norm(city["name"]), norm(city["ascii"])}
    hits = []
    for e in res["elements"]:
        t = e.get("tags", {})
        have = {norm(t.get(k)) for k in ("name", "name:en", "int_name", "official_name")} - {""}
        exact = bool(names & have)
        loose = any(n and n in h for n in names for h in have)
        if exact or loose:
            lvl = int(t["admin_level"])
            hits.append((exact, lvl <= 8 and lvl >= 5, lvl, e["id"], t.get("wikidata")))
    if not hits:
        return None
    ex, mid, lvl, rid, qid = max(hits)
    return qid, rid


# -------------------------------------------------------------- 4. geometry

def assemble(rel):
    outer, inner = [], []
    for m in rel.get("members", []):
        if m["type"] != "way" or "geometry" not in m:
            continue
        pts = [(p["lon"], p["lat"]) for p in m["geometry"] if p]
        if len(pts) < 2:
            continue
        (inner if m.get("role") == "inner" else outer).append(LineString(pts))
    if not outer:
        return None
    faces = list(polygonize(unary_union(outer)))
    if not faces:
        return None
    geom = unary_union(faces)
    if inner:
        holes = list(polygonize(unary_union(inner)))
        if holes:
            geom = geom.difference(unary_union(holes))
    if not geom.is_valid:
        geom = make_valid(geom)
    polys = [g for g in getattr(geom, "geoms", [geom]) if isinstance(g, (Polygon, MultiPolygon))]
    flat = []
    for g in polys:
        flat += list(g.geoms) if isinstance(g, MultiPolygon) else [g]
    return flat or None


class Land:
    """Natural Earth 10m land (public domain), buffered ~1 km so the coarse coast
    never cuts real land. Used to cut sea out of maritime admin boundaries."""

    def __init__(self, shp):
        import shapefile  # pyshp
        from shapely.geometry import shape
        from shapely.strtree import STRtree
        r = shapefile.Reader(shp)
        self.polys = [shape(x.__geo_interface__).buffer(0.01) for x in r.shapes()]
        self.tree = STRtree(self.polys)

    def clip(self, parts):
        u = unary_union(parts)
        near = self.tree.query(u)
        if len(near) == 0:
            return None
        res = u.intersection(unary_union([self.polys[i] for i in near]))
        flat = []
        for g in getattr(res, "geoms", [res]):
            flat += list(g.geoms) if isinstance(g, MultiPolygon) else ([g] if isinstance(g, Polygon) else [])
        return [g for g in flat if not g.is_empty] or None


def deg_area_km2(poly, lat0):
    return poly.area * KM * KM * math.cos(math.radians(lat0))


def clean(parts, city, far_km, min_hole_km2, min_part_km2):
    """Drop far islands, slivers and tiny holes. Returns (parts, dropped_info)."""
    lat0 = city["lat"]
    k = math.cos(math.radians(lat0))
    areas = [deg_area_km2(p, lat0) for p in parts]
    main = parts[max(range(len(parts)), key=areas.__getitem__)]
    # distance in km between a part and the main body (scaled degrees)
    def dist_km(p):
        return main.distance(p) * KM * (1.0 if k == 0 else 1.0) if p is not main else 0.0
    pt = Point(city["lon"], city["lat"])
    kept, dropped = [], []
    for p, a in zip(parts, areas):
        d = dist_km(p)
        has_point = p.buffer(0.02).contains(pt)
        if p is main or has_point or (d <= far_km and a >= min_part_km2):
            holes = [r for r in p.interiors if Polygon(r).area * KM * KM * k >= min_hole_km2]
            kept.append(Polygon(p.exterior, holes))
        else:
            dropped.append((round(a, 1), round(d)))
    return kept, dropped


def simplify_round(parts, tol_factor, tol_min, tol_max, digits=4):
    total = sum(p.area for p in parts)
    tol = min(tol_max, max(tol_min, tol_factor * math.sqrt(total)))
    out = []
    for p in parts:
        s = p.simplify(tol, preserve_topology=True)
        for q in getattr(s, "geoms", [s]):
            if not isinstance(q, Polygon) or q.is_empty:
                continue
            ext = [(round(x, digits), round(y, digits)) for x, y in q.exterior.coords]
            holes = []
            for r in q.interiors:
                rc = [(round(x, digits), round(y, digits)) for x, y in r.coords]
                if len(set(rc)) >= 4:
                    holes.append(rc)
            if len(set(ext)) >= 4:
                np_ = Polygon(ext, holes)
                if not np_.is_valid:
                    np_ = make_valid(np_.buffer(0))
                for z in getattr(np_, "geoms", [np_]):
                    if isinstance(z, Polygon) and not z.is_empty:
                        out.append(z)
    return out, tol


# ---------------------------------------------------------------- 5. output

def varint(n, buf):
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            buf.append(b | 0x80)
        else:
            buf.append(b)
            return


def zigzag(n):
    return (n << 1) ^ (n >> 63)


def encode_bin(cities):
    """gnid, nparts, per part nrings, per ring npts + zigzag varint deltas (1e-4 deg)."""
    buf = bytearray(b"CAR1")
    for gid, polys in cities:
        varint(gid, buf)
        varint(len(polys), buf)
        for p in polys:
            rings = [p.exterior] + list(p.interiors)
            varint(len(rings), buf)
            for r in rings:
                c = list(r.coords)[:-1]  # closing point is implied
                varint(len(c), buf)
                px = py = 0
                for x, y in c:
                    ix, iy = round(x * 1e4), round(y * 1e4)
                    varint(zigzag(ix - px), buf)
                    varint(zigzag(iy - py), buf)
                    px, py = ix, iy
    return bytes(buf)


def geojson_feature(c, polys, meta):
    geom = mapping(MultiPolygon(polys)) if len(polys) > 1 else mapping(polys[0])
    geom["coordinates"] = json.loads(json.dumps(geom["coordinates"]))
    return {"type": "Feature", "id": c["id"],
            "properties": {"n": c["name"], **meta}, "geometry": geom}


def nverts(polys):
    return sum(len(p.exterior.coords) + sum(len(r.coords) for r in p.interiors) for p in polys)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cities", default=os.path.join(DL, "cities100k.tsv"))
    ap.add_argument("--min-pop", type=int, default=100000)
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--sample", action="store_true")
    g.add_argument("--all", action="store_true")
    ap.add_argument("--out", default=os.path.join(HERE, "out"))
    ap.add_argument("--tol-factor", type=float, default=0.002, help="simplify tol = factor*sqrt(area deg2)")
    ap.add_argument("--tol-min", type=float, default=0.0002)
    ap.add_argument("--tol-max", type=float, default=0.002)
    ap.add_argument("--far-km", type=float, default=25, help="drop parts farther than this from the main body")
    ap.add_argument("--min-hole-km2", type=float, default=0.5)
    ap.add_argument("--min-part-km2", type=float, default=0.3)
    ap.add_argument("--ids", help="comma separated GeoNames ids, overrides --sample")
    ap.add_argument("--child-radius-km", type=float, default=80)
    ap.add_argument("--land", help="Natural Earth ne_10m_land.shp: clip to land (buffered ~1 km)")
    ap.add_argument("--tokyo", choices=["tama", "wards"], default="tama")
    a = ap.parse_args()

    land = Land(a.land) if a.land else None
    cities = load_cities(a.cities, a.min_pop, a.sample, [int(x) for x in a.ids.split(",")] if a.ids else None)
    log(len(cities), "cities")
    links = wikidata_links(cities)
    log(len(links), "have a Wikidata item via P1566")

    # overrides: Tokyo is built from its children
    children = {}
    if 1850147 in cities:
        children[1850147] = wikidata_children("Q1490", CHILD_CLASSES[a.tokyo])

    # pass 1: fetch everything wikidata knows
    want = {}
    for gid in cities:
        if gid in children:
            for qid, osm in children[gid].items():
                want[("k", gid, qid)] = (qid, osm)
        elif gid in links:
            want[("c", gid)] = links[gid]
    elements = fetch_relations(want)

    # pass 2: fallback for cities with no usable relation
    report, feats, binlist, fallbacks = [], [], [], 0
    for done, (gid, c) in enumerate(cities.items(), 1):
        if done % 10 == 0 or done == len(cities):
            progress("2/2 cities", done, len(cities))
        meta = {"src": None}
        rels = []
        if gid in children:
            for qid, osm in children[gid].items():
                cs = candidates(qid, osm, elements)
                ps = assemble(cs[0]) if cs else None
                if not ps:
                    continue
                big = max(ps, key=lambda p: p.area).centroid
                if math.hypot((big.x - c["lon"]) * math.cos(math.radians(c["lat"])), big.y - c["lat"]) * KM > a.child_radius_km:
                    continue  # island municipalities (Izu, Ogasawara)
                rels.append(cs[0])
            meta["src"] = f"children of Q1490 ({a.tokyo}) x{len(rels)}"
        else:
            r = None
            tries = []
            if gid in links:
                tries = candidates(links[gid][0], links[gid][1], elements)
                meta["src"] = None
            for cand in tries:
                ps = assemble(cand)
                if ps and unary_union(ps).buffer(0.03).contains(Point(c["lon"], c["lat"])):
                    r = cand
                    meta["src"] = "P402" if links[gid][1] == cand["id"] else "wikidata tag"
                    break
            if r is None:
                try:
                    fb = fallback_relation(c)
                except RuntimeError:
                    log("fallback query failed for", c["name"])
                    fb = None
                if fb:
                    fallbacks += 1
                    sub = fetch_relations({("f", gid): (fb[0] or "Q0", fb[1])}, batch=1)
                    elements.update(sub)
                    r = sub.get(fb[1])
                    meta["src"] = "fallback is_in" + (" (bad link)" if tries else "")
            if r is not None:
                rels = [r]
        row = {"id": gid, "name": c["name"], "cc": c["cc"], "pop": c["pop"], "src": meta["src"]}
        if not rels:
            row["status"] = "NO MATCH"
            report.append(row)
            continue
        parts = []
        for r in rels:
            parts += assemble(r) or []
        if not parts:
            row["status"] = "NO GEOMETRY"
            report.append(row)
            continue
        raw_area = sum(deg_area_km2(p, c["lat"]) for p in parts)
        if land:
            clipped = land.clip(parts)
            if clipped:
                parts = clipped
        parts, dropped = clean(parts, c, a.far_km, a.min_hole_km2, a.min_part_km2)
        polys, tol = simplify_round(parts, a.tol_factor, a.tol_min, a.tol_max)
        union = unary_union(polys)
        area = deg_area_km2(union, c["lat"])
        r0 = rels[0]["tags"] if len(rels) == 1 else {}
        pt = Point(c["lon"], c["lat"])
        row.update(status="ok", rel=rels[0]["id"] if len(rels) == 1 else None,
                   admin_level=r0.get("admin_level"), osm_name=r0.get("name"),
                   area_km2=round(area), raw_area_km2=round(raw_area),
                   parts=len(polys), dropped=dropped, verts=nverts(polys),
                   tol_deg=round(tol, 5), has_point=bool(union.buffer(0.01).contains(pt)))
        flags = []
        if not row["has_point"]:
            flags.append("point outside")
        try:
            if r0 and int(r0.get("admin_level", 9)) <= 5:
                flags.append("admin_level<=5 (region-sized?)")
        except ValueError:
            pass
        if area > 6000:
            flags.append("area>6000km2")
        if area / max(c["pop"], 1) * 1e6 > 5e4 and area > 1500:  # >50k m2 per person and big
            flags.append("sparse")
        if dropped:
            flags.append(f"dropped {len(dropped)} far/tiny parts")
        row["flags"] = flags
        report.append(row)
        feats.append(geojson_feature(c, polys, {"wd": links.get(gid, (None,))[0], "al": r0.get("admin_level")}))
        # A boundary the size of a region (Beijing municipality, Lagos State) is not the city
        # people mean; the app shows a dot for those instead of a misleading area.
        if not any(f.startswith(("admin_level<=5", "area>6000")) for f in flags):
            binlist.append((gid, polys))

    os.makedirs(a.out, exist_ok=True)
    gj = json.dumps({"type": "FeatureCollection", "features": feats}, separators=(",", ":"), ensure_ascii=False)
    # drop trailing zeros in numbers to be fair about size
    with open(os.path.join(a.out, "cityareas.geojson"), "w", encoding="utf-8") as f:
        f.write(gj)
    bn = encode_bin(binlist)
    with open(os.path.join(a.out, "cityareas.bin"), "wb") as f:
        f.write(bn)
    with open(os.path.join(a.out, "report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, indent=1, ensure_ascii=False)

    ok = [r for r in report if r["status"] == "ok"]
    log(f"matched {len(ok)}/{len(cities)}  fallbacks {fallbacks}")
    log(f"geojson {len(gj.encode()) / 1e3:.0f} KB  gz {len(zlib.compress(gj.encode(), 9)) / 1e3:.0f} KB"
        f"  bin {len(bn) / 1e3:.0f} KB  bin+deflate {len(zlib.compress(bn, 9)) / 1e3:.0f} KB")
    for r in report:
        print(r["id"], r["name"], r["cc"], r["status"], r.get("src"), "al=" + str(r.get("admin_level")),
              f"area={r.get('area_km2')}km2 (raw {r.get('raw_area_km2')})", f"verts={r.get('verts')}",
              r.get("flags", ""), sep=" | ")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Build cityareas.bin from Overture Maps divisions, all on this machine, in minutes.

Overture publishes OpenStreetMap's administrative areas as GeoParquet, already clipped to land
(is_land) and tied to Wikidata. That replaces build_cityareas.py's thousands of Overpass
requests with local queries. Download the two tables once (about 4.6 GB):

  aws s3 sync --no-sign-request \\
    s3://overturemaps-us-west-2/release/<release>/theme=divisions/type=division_area/ division_area/
  aws s3 sync --no-sign-request \\
    s3://overturemaps-us-west-2/release/<release>/theme=divisions/type=division/ division/

Then, with the venv build_cityareas.py uses plus duckdb:

  venv/bin/python -I build_cityareas_overture.py --overture <dir with both tables> \\
      --cities download/cities100k.tsv --links-from <build_cityareas.py dir> --out out

A city is matched in this order: its Wikidata item (Overture's division.wikidata), its OSM
relation from Wikidata (P402, against the area's OSM source record), then the smallest
locality-level area that contains the city's point and shares its country. Areas the size of a
region are left out and the city stays a dot. Tokyo keeps the 23 wards plus Tama outline from
--keep, since Overture has Tokyo only as a point.

Data: Overture Maps Foundation, from OpenStreetMap contributors, ODbL.
"""
import argparse
import json
import math
import os
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))

# The densest a whole city gets, in people per km2 (Manila, Dhaka). An area that would put the
# city above this is one of its districts, not the city.
DENSEST = 25000


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--overture", required=True, help="folder holding division/ and division_area/")
    ap.add_argument("--cities", required=True, help="cities100k.tsv from build_cityareas.py")
    ap.add_argument("--links-from", required=True, help="folder of build_cityareas.py with its download/ cache")
    ap.add_argument("--keep", help="an existing cityareas.bin whose outlines win, such as Tokyo's")
    ap.add_argument("--out", required=True)
    ap.add_argument("--max-km2", type=float, default=6000, help="larger areas are a region, not the city")
    ap.add_argument("--memory", default="2GB", help="DuckDB memory limit")
    a = ap.parse_args()

    sys.path.insert(0, a.links_from)
    import build_cityareas as B  # helpers: cities, Wikidata links, cleaning, encoding
    import duckdb
    from shapely import from_wkb
    from shapely.geometry import MultiPolygon, Polygon

    os.chdir(a.links_from)  # its download/ cache resolves relative to itself
    cities = B.load_cities(a.cities, 100000, False)
    links = B.wikidata_links(cities)
    B.log(len(cities), "cities,", len(links), "with a Wikidata item")

    db = duckdb.connect()
    db.execute(f"SET memory_limit='{a.memory}'")
    db.execute("SET threads=4")
    db.execute("SET preserve_insertion_order=false")
    db.execute(f"SET temp_directory='{os.path.join(a.out, '.duckdb')}'")
    db.execute("INSTALL spatial; LOAD spatial")
    area = os.path.join(a.overture, "division_area", "*.parquet")
    div = os.path.join(a.overture, "division", "*.parquet")
    levels = "('locality', 'localadmin', 'county')"

    # Matching works on ids and bounding boxes only; shapes are read at the end, and only for
    # the areas a city actually matched. Holding every city-level shape on Earth at once does
    # not fit in memory.
    qid_of = {g: links[g][0] for g in cities if g in links}
    rel_of = {g: f"r{links[g][1]}" for g in cities if g in links and links[g][1]}
    pick = {}  # gid -> (how, division id)

    by_qid = {}
    for did, q in db.execute(
        f"SELECT id, wikidata FROM read_parquet('{div}') WHERE wikidata IN (SELECT unnest(?))",
        [sorted(set(qid_of.values()))],
    ).fetchall():
        by_qid.setdefault(q, did)
    for g, q in qid_of.items():
        if q in by_qid:
            pick[g] = ("wikidata", by_qid[q])

    by_rel = {}
    for did, rec in db.execute(
        f"""SELECT division_id, split_part(sources[1].record_id, '@', 1) FROM read_parquet('{area}')
            WHERE is_land AND split_part(sources[1].record_id, '@', 1) IN (SELECT unnest(?))""",
        [sorted(set(rel_of.values()))],
    ).fetchall():
        by_rel.setdefault(rec, did)
    for g, r in rel_of.items():
        if g not in pick and r in by_rel:
            pick[g] = ("osm relation", by_rel[r])
    B.log(len(pick), "matched by Wikidata or OSM relation")

    # Every city also gets the city-level areas whose box holds its point, in the same country:
    # a Wikidata match can turn out to be a division with no area at all, only a point, and then
    # the area around the point is the fallback. The shape test and the choice of the smallest
    # happen below, once the shapes are loaded.
    rest = [(g, cities[g]["cc"], cities[g]["lon"], cities[g]["lat"]) for g in cities]
    db.execute("CREATE TABLE r(gid BIGINT, cc VARCHAR, lon DOUBLE, lat DOUBLE)")
    db.executemany("INSERT INTO r VALUES (?, ?, ?, ?)", rest)
    boxed = {}
    names = {}
    for g, did, nm in db.execute(f"""
        SELECT r.gid, a.division_id, a.nm FROM r JOIN (
            SELECT division_id, country, bbox, names."primary" AS nm FROM read_parquet('{area}') WHERE is_land AND subtype IN {levels}
        ) a ON a.country = r.cc
          AND r.lon BETWEEN a.bbox.xmin AND a.bbox.xmax AND r.lat BETWEEN a.bbox.ymin AND a.bbox.ymax
    """).fetchall():
        boxed.setdefault(g, []).append(did)
        names[did] = nm or ""
    B.log(len(boxed), "cities have a city-level area around them")

    want = {d for _, d in pick.values()} | {d for ds in boxed.values() for d in ds}
    shapes = {}
    cur = db.execute(
        f"SELECT division_id, ST_AsWKB(geometry) FROM read_parquet('{area}') WHERE is_land AND division_id IN (SELECT unnest(?))",
        [sorted(want)],
    )
    while True:
        rows = cur.fetchmany(500)
        if not rows:
            break
        for did, wkb in rows:
            shapes.setdefault(did, from_wkb(bytes(wkb)))
    B.log(len(shapes), "shapes loaded")

    from shapely.geometry import Point
    hit = {}
    for g, (how, did) in pick.items():
        if did in shapes:
            hit[g] = (how, shapes[did])
    for g, ds in boxed.items():
        if g in hit:
            continue
        c = cities[g]
        pt = Point(c["lon"], c["lat"])
        # The smallest area around a big city is often one of its districts. An area has to be
        # big enough for the city's people (no denser than DENSEST per km2), and one that carries
        # the city's own name wins over the rest.
        floor = c["pop"] / DENSEST
        inside = [
            (d, shapes[d]) for d in ds
            if d in shapes and shapes[d].contains(pt)
            and sum(B.deg_area_km2(p, c["lat"]) for p in getattr(shapes[d], "geoms", [shapes[d]])) >= floor
        ]
        if not inside:
            continue
        own = {B.norm(c["name"]), B.norm(c["ascii"])}
        named = [x for x in inside if B.norm(names.get(x[0], "")) in own]
        d, shape = min(named or inside, key=lambda x: x[1].area)
        hit[g] = ("named area" if named else "contains point", shape)
    B.log(len(hit), "matched in all")

    keep = {}
    if a.keep and os.path.exists(a.keep):
        keep = read_bin(a.keep)

    binlist, report = [], []
    for done, (gid, c) in enumerate(cities.items(), 1):
        if done % 200 == 0 or done == len(cities):
            B.progress("outlines", done, len(cities))
        row = {"id": gid, "name": c["name"], "cc": c["cc"], "pop": c["pop"]}
        if gid == 1850147 and gid in keep:  # Tokyo: wards plus Tama, from the Overpass build
            binlist.append((gid, keep[gid]))
            report.append({**row, "status": "kept"})
            continue
        if gid not in hit:
            report.append({**row, "status": "NO MATCH"})
            continue
        src, g = hit[gid]
        parts = [p for p in getattr(g, "geoms", [g]) if isinstance(p, Polygon)]
        if not parts:
            report.append({**row, "status": "NO GEOMETRY", "src": src})
            continue
        area_km2 = sum(B.deg_area_km2(p, c["lat"]) for p in parts)
        if area_km2 > a.max_km2:
            report.append({**row, "status": "REGION", "src": src, "km2": round(area_km2)})
            continue
        parts, dropped = B.clean(parts, c, 25, 0.5, 0.3)
        polys, _ = B.simplify_round(parts, 0.002, 0.0002, 0.002)
        if not polys:
            report.append({**row, "status": "EMPTY", "src": src})
            continue
        binlist.append((gid, polys))
        report.append({**row, "status": "ok", "src": src, "km2": round(area_km2)})

    os.makedirs(a.out, exist_ok=True)
    bn = B.encode_bin(binlist)
    with open(os.path.join(a.out, "cityareas.bin"), "wb") as f:
        f.write(bn)
    with open(os.path.join(a.out, "report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, indent=1, ensure_ascii=False)
    ok = sum(1 for r in report if r["status"] in ("ok", "kept"))
    B.log(f"{ok}/{len(cities)} cities have an outline, {len(bn) / 1e6:.1f} MB, "
          f"{len(zlib.compress(bn, 9)) / 1e6:.1f} MB compressed")


def read_bin(path):
    """The polygons of an existing cityareas.bin, by GeoNames id, as shapely polygons."""
    from shapely.geometry import Polygon
    data = open(path, "rb").read()
    at = 4

    def varint():
        nonlocal at
        out = shift = 0
        while True:
            b = data[at]
            at += 1
            out |= (b & 0x7F) << shift
            if not b & 0x80:
                return out
            shift += 7

    def zz(n):
        return (n >> 1) ^ -(n & 1)

    out = {}
    while at < len(data):
        gid = varint()
        polys = []
        for _ in range(varint()):
            rings = []
            for _ in range(varint()):
                x = y = 0
                pts = []
                for _ in range(varint()):
                    x += zz(varint())
                    y += zz(varint())
                    pts.append((x / 1e4, y / 1e4))
                rings.append(pts)
            if rings and len(rings[0]) >= 3:
                polys.append(Polygon(rings[0], [r for r in rings[1:] if len(r) >= 3]))
        out[gid] = polys
    return out


if __name__ == "__main__":
    main()

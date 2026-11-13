"""Build app/src/main/assets/regions/<CC>.bin, the outlines of each country's regions.

Natural Earth's admin 1 states and provinces are public domain. The app names its regions as
GeoNames does ("JP.15"), so each Natural Earth shape is matched to a GeoNames region: by its own
GeoNames code when the app knows that code, else by the region most of the cities inside it are
in, weighted by population. Shapes that land on the same region are joined, so France's
departments become its regions. A country's regions are simplified together as one coverage, so
a border two regions share stays one line.

  curl -LO https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_admin_1_states_provinces.geojson
  uv pip install --python tools/.venv/bin/python shapely
  tools/.venv/bin/python -I tools/build_regions.py ne_10m_admin_1_states_provinces.geojson \
      app/src/main/assets/cities.tsv app/src/main/assets/regions.tsv app/src/main/assets/regions

Format, one file per country, all integers as unsigned LEB128 varints and coordinates as
zigzag deltas in units of 1e-4 degrees:
  "SRG1"
  region count
  per region: code length, code (UTF-8, the part after the dot), polygon count,
    per polygon: ring count, per ring: point count, then lat and lon deltas per point
"""
import json
import os
import sys
from collections import defaultdict

import shapely
from shapely.geometry import MultiPolygon, Point, Polygon, shape
from shapely.ops import unary_union
from shapely.strtree import STRtree

TOLERANCE = 0.01  # degrees, about a kilometre: fine for a phone map of a whole country
SCALE = 1e4


def varint(out, v):
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return


def zigzag(out, v):
    varint(out, (v << 1) ^ (v >> 63) if v < 0 else v << 1)


def polygons(g):
    if isinstance(g, Polygon):
        return [g]
    if isinstance(g, MultiPolygon):
        return list(g.geoms)
    return [p for p in getattr(g, "geoms", []) if isinstance(p, Polygon)]


def main(ne_path, cities_path, regions_path, out_dir):
    known = set()
    for line in open(regions_path, encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) >= 3:
            known.add(f"{p[0]}.{p[1]}")

    cities = defaultdict(list)
    for line in open(cities_path, encoding="utf-8"):
        p = line.rstrip("\n").split("\t")
        if len(p) < 8:
            continue
        try:
            cities[p[3]].append((float(p[5]), float(p[6]), p[4], int(p[7] or 0)))
        except ValueError:
            continue

    shapes = defaultdict(list)  # country -> [(region code, geometry)]
    unmatched = 0
    for f in json.load(open(ne_path, encoding="utf-8"))["features"]:
        props = f["properties"]
        cc = (props.get("iso_a2") or "").upper()
        if len(cc) != 2 or not cc.isalpha():
            continue
        g = shape(f["geometry"])
        if not g.is_valid:
            g = g.buffer(0)
        code = props.get("gn_a1_code")
        if code not in known:
            # The region most of its cities are in, by population.
            weight = defaultdict(int)
            prepared = shapely.prepared.prep(g)
            for lat, lon, a1, pop in cities.get(cc, []):
                if prepared.contains(Point(lon, lat)):
                    weight[a1] += max(pop, 1)
            code = None
            for a1, _ in sorted(weight.items(), key=lambda kv: -kv[1]):
                if f"{cc}.{a1}" in known:
                    code = f"{cc}.{a1}"
                    break
        if code is None:
            unmatched += 1
            continue
        shapes[cc].append((code, g))

    os.makedirs(out_dir, exist_ok=True)
    total = 0
    for cc, items in sorted(shapes.items()):
        joined = defaultdict(list)
        for code, g in items:
            joined[code].append(g)
        codes = sorted(joined)
        geoms = [unary_union(joined[c]) for c in codes]
        try:
            simple = list(shapely.coverage_simplify(geoms, TOLERANCE))
        except Exception:
            simple = [g.simplify(TOLERANCE, preserve_topology=True) for g in geoms]
        out = bytearray(b"SRG1")
        kept = [(c, g) for c, g in zip(codes, simple) if not g.is_empty]
        varint(out, len(kept))
        for code, g in kept:
            name = code.split(".", 1)[1].encode("utf-8")
            varint(out, len(name))
            out += name
            polys = [p for p in polygons(g) if p.area > TOLERANCE * TOLERANCE]
            varint(out, len(polys))
            for poly in polys:
                rings = [poly.exterior] + list(poly.interiors)
                varint(out, len(rings))
                for ring in rings:
                    pts = [(round(y * SCALE), round(x * SCALE)) for x, y in ring.coords]
                    varint(out, len(pts))
                    py = px = 0
                    for y, x in pts:
                        zigzag(out, y - py)
                        zigzag(out, x - px)
                        py, px = y, x
        with open(os.path.join(out_dir, f"{cc}.bin"), "wb") as fh:
            fh.write(out)
        total += len(out)
    print(f"{len(shapes)} countries, {total / 1e6:.2f} MB, {unmatched} shapes with no region", file=sys.stderr)


if __name__ == "__main__":
    main(*sys.argv[1:5])

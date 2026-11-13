"""Build app/src/main/assets/countries.geojson from Natural Earth admin 0 countries.

Natural Earth is public domain. Download the 50m countries and map subunits and pass their
paths. Shapely does the land mass grouping, so run it in a venv that has it:
  curl -LO https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_admin_0_countries.geojson
  curl -LO https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_admin_0_map_subunits.geojson
  python3 -m venv .venv && .venv/bin/pip install shapely
  .venv/bin/python -I tools/build_countries.py ne_50m_admin_0_countries.geojson \
      ne_50m_admin_0_map_subunits.geojson app/src/main/assets/countries.geojson

Land masses. Natural Earth's map subunits are a curated registry of the separate parts of a
country: France into the mainland, Corsica, French Guiana, Reunion and the rest, Japan into
its main islands and outer chains. Subunits closer than MERGE_KM to each other are one land
mass, so England, Scotland and Wales stay together and so do Honshu and Kyushu. Each country
becomes one feature per land mass. The largest keeps the country's id, so marks saved before
the split stay on the mainland; the others get "<country>-<subunit>" and a "country" property
naming their country.

Fine shapes. The map swaps to the 10m shapes from mid zoom. --like builds them so they match
an existing 50m file feature for feature: the same ids, the same land masses and the same
properties, with only the outlines taken from the 10m data. Pass the 50m output first:
  curl -LO https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_admin_0_countries.geojson
  .venv/bin/python -I tools/build_countries.py --like app/src/main/assets/countries.geojson \
      ne_10m_admin_0_countries.geojson app/src/main/assets/countries_10m.geojson
Each island or piece of land in a 10m country joins the 50m land mass of that country it
overlaps most, or else the nearest one. A 10m area with no country of its own in the 50m
file (the bases on Cyprus, the Patagonian ice field) joins the 50m feature it overlaps most,
and is dropped if it overlaps none, as the 50m map leaves it out too. The result is simplified as one coverage, so a border
two countries share stays one line with no gaps along it.
"""
from shapely.geometry import Point, mapping, shape
from shapely.ops import unary_union
import shapely
import math
import json
import sys

# The 193 UN member states plus the two observer states, the Holy See and Palestine.
UN = set("""
AFG ALB DZA AND AGO ATG ARG ARM AUS AUT AZE BHS BHR BGD BRB BLR BEL BLZ BEN BTN BOL BIH BWA BRA
BRN BGR BFA BDI CPV KHM CMR CAN CAF TCD CHL CHN COL COM COG COD CRI CIV HRV CUB CYP CZE DNK DJI
DMA DOM ECU EGY SLV GNQ ERI EST SWZ ETH FJI FIN FRA GAB GMB GEO DEU GHA GRC GRD GTM GIN GNB GUY
HTI HND HUN ISL IND IDN IRN IRQ IRL ISR ITA JAM JPN JOR KAZ KEN KIR PRK KOR KWT KGZ LAO LVA LBN
LSO LBR LBY LIE LTU LUX MDG MWI MYS MDV MLI MLT MHL MRT MUS MEX FSM MDA MCO MNG MNE MAR MOZ MMR
NAM NRU NPL NLD NZL NIC NER NGA MKD NOR OMN PAK PLW PAN PNG PRY PER PHL POL PRT QAT ROU RUS RWA
KNA LCA VCT WSM SMR STP SAU SEN SRB SYC SLE SGP SVK SVN SLB SOM ZAF SSD ESP LKA SDN SUR SWE CHE
SYR TJK TZA THA TLS TGO TON TTO TUN TUR TKM TUV UGA UKR ARE GBR USA URY UZB VUT VEN VNM YEM ZMB
ZWE VAT PSE
""".split())
assert len(UN) == 195, len(UN)

OCEAN_FIX = {"Eastern Africa": "Africa", "Southern Asia": "Asia", "Western Africa": "Africa",
             "Middle Africa": "Africa", "Melanesia": "Oceania", "Polynesia": "Oceania",
             "Micronesia": "Oceania", "South America": "South America", "Southern Africa": "Africa"}


def rnd(c, digits=3):
    if isinstance(c[0], (int, float)):
        return [round(c[0], digits), round(c[1], digits)]
    out = [rnd(x, digits) for x in c]
    # drop consecutive duplicate points created by rounding
    if out and isinstance(out[0][0], (int, float)):
        ded = [out[0]] + [p for i, p in enumerate(out[1:], 1) if p != out[i - 1]]
        if len(ded) >= 4:
            return ded
    return out


# Parts this close (in km) are one land mass: a strait you cross by bridge or a short ferry.
MERGE_KM = 50
# Parts that stay apart however close they are: separate territories, not islands.
KEEP_APART = {"GAZ"}
# Plain names where Natural Earth uses an administrative one.
PART_NAMES = {
    "TLP": "Oecussi", "KOJ": "Jeju", "PNB": "Bougainville", "ECG": "Galápagos Islands",
    "RUK": "Kaliningrad", "YES": "Socotra", "ITP": "Pantelleria", "NZA": "Subantarctic Islands",
}


def continent_of(p):
    cont = p["CONTINENT"]
    if cont.startswith("Seven seas"):
        cont = OCEAN_FIX.get(p["SUBREGION"], p["REGION_UN"])
        if cont == "Americas":
            cont = "South America"
    return cont


def km_apart(a, b):
    if a.intersects(b):
        return 0.0
    from shapely.ops import nearest_points
    x, y = nearest_points(a, b)
    k = math.cos(math.radians((x.y + y.y) / 2))
    return math.hypot((x.x - y.x) * 111 * k, (x.y - y.y) * 111)


def land_masses(subs):
    """Groups a country's subunits into land masses, largest first."""
    groups = [[s] for s in subs]
    merged = True
    while merged:
        merged = False
        for i in range(len(groups)):
            for j in range(i + 1, len(groups)):
                if any(s["code"] in KEEP_APART for s in groups[i] + groups[j]):
                    continue
                if any(km_apart(a["geom"], b["geom"]) <= MERGE_KM for a in groups[i] for b in groups[j]):
                    groups[i] += groups.pop(j)
                    merged = True
                    break
            if merged:
                break
    groups.sort(key=lambda g: -sum(s["geom"].area for s in g))
    return groups


def main(src, subunits_src, dst):
    data = json.load(open(src))
    subs_by_admin = {}
    for f in json.load(open(subunits_src))["features"]:
        p = f["properties"]
        subs_by_admin.setdefault(p["ADM0_A3"], []).append({
            "code": p["SU_A3"], "name": PART_NAMES.get(p["SU_A3"]) or p["NAME_EN"] or p["SUBUNIT"], "props": p,
            "geom": shape(f["geometry"]).buffer(0),
        })
    feats, seen = [], set()
    parts = 0
    for f in data["features"]:
        p = f["properties"]
        cid = p["ISO_A3_EH"] if p["ISO_A3_EH"] not in ("-99", None) else p["ADM0_A3"]
        if cid in seen:
            continue
        seen.add(cid)
        a2 = p["ISO_A2_EH"] if len(p["ISO_A2_EH"] or "") == 2 else ""
        name = p["NAME_EN"] or p["NAME"]
        groups = land_masses(subs_by_admin.get(p["ADM0_A3"], []))
        if len(groups) <= 1:
            feats.append(feature(cid, cid, name, continent_of(p), cid in UN, a2,
                                 (p["LABEL_X"], p["LABEL_Y"]), f["geometry"]))
            continue
        for k, g in enumerate(groups):
            geom = unary_union([s["geom"] for s in g])
            big = max(g, key=lambda s: s["geom"].area)
            label = geom.representative_point()
            if k == 0:
                # The country's own label point, unless it sits on another of its land masses.
                lx, ly = p["LABEL_X"], p["LABEL_Y"]
                if not geom.buffer(0.5).contains(Point(lx, ly)):
                    lx, ly = label.x, label.y
                feats.append(feature(cid, cid, name, continent_of(p), cid in UN, a2, (lx, ly), mapping(geom)))
            else:
                bp = big["props"]
                pa2 = bp["ISO_A2_EH"] if len(bp["ISO_A2_EH"] or "") == 2 else a2
                feats.append(feature(f"{cid}-{big['code']}", cid, big["name"], continent_of(bp), False, pa2,
                                     (label.x, label.y), mapping(geom)))
                parts += 1
    missing = UN - seen
    if missing:
        print("UN members missing from source:", sorted(missing), file=sys.stderr)
    feats.sort(key=lambda f: f["id"])
    json.dump({"type": "FeatureCollection", "features": feats}, open(dst, "w"), separators=(",", ":"))
    print(f"{len(feats)} features ({parts} separate land masses), {len(UN & seen)} UN, continents:",
          sorted({f['properties']['continent'] for f in feats}))


def feature(fid, country, name, continent, un, a2, label, geometry):
    return {
        "type": "Feature",
        "id": fid,
        "properties": {"id": fid, "country": country, "name": name, "continent": continent, "un": un, "a2": a2,
                       "lx": round(label[0], 3), "ly": round(label[1], 3)},
        "geometry": {"type": geometry["type"], "coordinates": rnd(json.loads(json.dumps(geometry["coordinates"])))},
    }


# The fine shapes: coordinates to 4 decimals, about 11 m, and Visvalingam simplification that
# drops wiggles smaller than about FINE_TOLERANCE degrees across, roughly 600 m.
FINE_DIGITS = 4
FINE_TOLERANCE = 0.006


def best_match(geom, candidates):
    """The candidate (id, geometry) that [geom] overlaps most, or None if it overlaps none."""
    hits = [(geom.intersection(g).area, i) for i, g in candidates if geom.intersects(g)]
    hits = [h for h in hits if h[0] > 0]
    return max(hits)[1] if hits else None


def main_like(ref_path, src, dst):
    ref = json.load(open(ref_path))["features"]
    ref_geom = {f["id"]: shape(f["geometry"]).buffer(0) for f in ref}
    ids_of = {}
    for f in ref:
        ids_of.setdefault(f["properties"]["country"], []).append(f["id"])
    pieces = {fid: [] for fid in ref_geom}
    dropped = []
    for f in json.load(open(src))["features"]:
        p = f["properties"]
        cid = p["ISO_A3_EH"] if p["ISO_A3_EH"] not in ("-99", None) else p["ADM0_A3"]
        geom = shape(f["geometry"]).buffer(0)
        ids = ids_of.get(cid)
        if ids is None:
            into = best_match(geom, ref_geom.items())
            if into is None:
                dropped.append(cid)
            else:
                pieces[into].append(geom)
            continue
        if len(ids) == 1:
            pieces[ids[0]].append(geom)
            continue
        own = [(i, ref_geom[i]) for i in ids]
        for poly in getattr(geom, "geoms", [geom]):
            into = best_match(poly, own) or min(own, key=lambda c: km_apart(poly, c[1]))[0]
            pieces[into].append(poly)
    empty = [fid for fid, g in pieces.items() if not g]
    assert not empty, f"no 10m shape for {empty}"
    order = sorted(pieces)
    merged = [unary_union(pieces[fid]) for fid in order]
    simple = shapely.coverage_simplify(merged, FINE_TOLERANCE)
    props = {f["id"]: f["properties"] for f in ref}
    feats = []
    for fid, g in zip(order, simple):
        g = mapping(shapely.make_valid(g).buffer(0) if not g.is_valid else g)
        feats.append({"type": "Feature", "id": fid, "properties": props[fid],
                      "geometry": {"type": g["type"],
                                   "coordinates": rnd(json.loads(json.dumps(g["coordinates"])), FINE_DIGITS)}})
    json.dump({"type": "FeatureCollection", "features": feats}, open(dst, "w"), separators=(",", ":"))
    print(f"{len(feats)} features like {ref_path}, dropped 10m areas with no 50m feature: {sorted(dropped)}")


if __name__ == "__main__":
    if sys.argv[1] == "--like":
        main_like(sys.argv[2], sys.argv[3], sys.argv[4])
    else:
        main(sys.argv[1], sys.argv[2], sys.argv[3])

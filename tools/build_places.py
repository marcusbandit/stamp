"""Build app/src/main/assets/cities.tsv and regions.tsv from GeoNames.

GeoNames is CC BY 4.0, credited in Settings. Download the two files and pass their paths:
  curl -LO https://download.geonames.org/export/dump/cities1000.zip && unzip cities1000.zip
  curl -LO https://download.geonames.org/export/dump/admin1CodesASCII.txt
  python3 -I tools/build_places.py cities1000.txt admin1CodesASCII.txt app/src/main/assets

cities.tsv, one city per line, grouped by country and biggest first:
  geonameid  name  ascii-name  country(alpha-2)  admin1-code  lat  lon  population
regions.tsv, one first-level region per line:
  country(alpha-2)  admin1-code  name
"""
import os
import sys

# Neighbourhoods and places that no longer exist are not cities you visit.
SKIP_CODES = {"PPLX", "PPLH", "PPLQ", "PPLW", "PPLCH"}


def clean(s):
    return s.replace("\t", " ").strip()


def main(cities_path, admin1_path, out_dir):
    rows = []
    with open(cities_path, encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) < 15 or p[7] in SKIP_CODES or not p[8]:
                continue
            rows.append((
                p[8], -int(p[14] or 0), p[0], clean(p[1]), clean(p[2]), p[10],
                "%.4f" % float(p[4]), "%.4f" % float(p[5]),
            ))
    rows.sort()
    with open(os.path.join(out_dir, "cities.tsv"), "w", encoding="utf-8") as out:
        for cc, negpop, gid, name, ascii_name, admin1, lat, lon in rows:
            out.write("\t".join([gid, name, ascii_name, cc, admin1, lat, lon, str(-negpop)]) + "\n")

    regions = []
    with open(admin1_path, encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) < 2 or "." not in p[0]:
                continue
            cc, code = p[0].split(".", 1)
            regions.append((cc, clean(p[1]), code))
    regions.sort()
    with open(os.path.join(out_dir, "regions.tsv"), "w", encoding="utf-8") as out:
        for cc, name, code in regions:
            out.write("\t".join([cc, code, name]) + "\n")
    print(f"{len(rows)} cities, {len(regions)} regions")


if __name__ == "__main__":
    main(*sys.argv[1:4])

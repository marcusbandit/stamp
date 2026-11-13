"""Build app/src/main/assets/airports.tsv from OurAirports.

OurAirports is in the public domain. Download the file and pass its path:
  curl -LO https://davidmegginson.github.io/ourairports-data/airports.csv
  python3 -I tools/build_airports.py airports.csv app/src/main/assets

Only airports with scheduled flights and an IATA code: the ones a trip flies through.
airports.tsv, one airport per line, big ones first:
  iata  name  town  country(alpha-2)  lat  lon  size (L large, M medium, S small)
"""
import csv
import os
import sys

SIZES = {"large_airport": "L", "medium_airport": "M", "small_airport": "S"}


def clean(s):
    return s.replace("\t", " ").strip()


def main(src, out_dir):
    rows = {}
    with open(src, encoding="utf-8") as f:
        for r in csv.DictReader(f):
            size = SIZES.get(r["type"])
            iata = r["iata_code"].strip().upper()
            if not size or r["scheduled_service"] != "yes" or len(iata) != 3 or not iata.isalpha():
                continue
            row = (size, iata, clean(r["name"]), clean(r["municipality"]), r["iso_country"],
                   "%.4f" % float(r["latitude_deg"]), "%.4f" % float(r["longitude_deg"]))
            # The same code twice: keep the bigger airport.
            if iata not in rows or row[0] < rows[iata][0]:
                rows[iata] = row
    with open(os.path.join(out_dir, "airports.tsv"), "w", encoding="utf-8") as out:
        for size, iata, name, town, cc, lat, lon in sorted(rows.values()):
            out.write("\t".join([iata, name, town, cc, lat, lon, size]) + "\n")
    print(len(rows), "airports")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])

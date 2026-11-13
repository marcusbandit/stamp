# Stamp

An Android app for keeping track of the countries you have been to. Tap a country on the map and
mark it as visited, lived in, or want to go. The Passport tab counts the result against the 195 UN
countries and breaks it down by continent.

Stamp can also fill the map in for you. It reads the GPS position saved in each photo, works out
which country it was taken in, and lists what it found so you can tick the ones to add. The scan
runs on the phone. Nothing is uploaded, and there is no account.

## Layout

| Path | What lives there |
|---|---|
| `app/src/main/assets/countries.geojson` | Country shapes, built from Natural Earth by `tools/build_countries.py` |
| `app/src/main/assets/countries_10m.geojson` | The same countries in finer shapes, drawn from mid zoom in |
| `data/Atlas.kt` | Parses the shapes, and finds the country a point falls in |
| `data/VisitStore.kt` | The marks, saved as JSON in the app's private storage |
| `scan/PhotoScanner.kt` | Reads EXIF positions from MediaStore and tallies them per country |
| `ui/WorldMap.kt` | MapLibre map drawn from the bundled GeoJSON, with no tiles and no network |
| `ui/Onboarding.kt` | First launch: what Stamp does, the photo scan, how to mark a country |
| `ui/Layouts.kt` | Portrait, landscape and unfolded layouts for a book-style foldable |

## Running it

Everything goes through `scripts/`, which keeps Gradle and the emulator inside a memory cap.

```sh
scripts/create-avd.sh      # once: makes the stamp_fold AVD (Pixel 10 Pro Fold)
scripts/emulator.sh        # boot it headless; --window to see it
scripts/run.sh             # build, install, launch
scripts/shot.sh name       # screenshot into captures/name.png
scripts/build.sh :app:testDebugUnitTest
```

## Logo and Play Store

`tools/build_icon.py` draws the logo, a globe with one cell each in the visited, lived and
want-to-go colours, and writes the launcher icon layers, `docs/logo.svg` and the store images.
Store text and images sit in `fastlane/metadata/android/en-US/`. `docs/play-console.md` holds the
answers for the Play Console forms, and `docs/privacy.md` is the privacy policy.

```sh
python3 -I tools/build_icon.py   # after changing the logo
scripts/setup-upload-key.sh      # once per build machine: upload key in ~/.config/stamp/
scripts/bundle.sh                # signed app-release.aab for Play
```

## Country data

The shapes are the Natural Earth admin 0 countries, which are public domain. The map draws
the 1:50m ones out to zoom 3.5 and fades to the 1:10m ones from there, so coastlines and borders
stay sharp up close. Hit testing and the photo scan use the 1:50m shapes. The 1:10m file has
exactly the same features, ids and land masses, with only the outlines finer. To rebuild both
(Shapely is needed, so use a venv):

```sh
for s in 50m 10m; do for n in countries map_subunits; do
  curl -LO https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_${s}_admin_0_$n.geojson
done; done
python3 -m venv .venv && .venv/bin/pip install shapely
.venv/bin/python -I tools/build_countries.py ne_50m_admin_0_countries.geojson \
    ne_50m_admin_0_map_subunits.geojson app/src/main/assets/countries.geojson
.venv/bin/python -I tools/build_countries.py --like app/src/main/assets/countries.geojson \
    ne_10m_admin_0_countries.geojson app/src/main/assets/countries_10m.geojson
```

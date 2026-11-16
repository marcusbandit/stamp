# CLAUDE.md

Stamp is an Android travel tracker in Kotlin and Jetpack Compose. You mark countries on a
MapLibre world map drawn from bundled Natural Earth shapes, with no tiles. The only network uses are
downloading rail and road packs for legs that follow real lines, fetching a tapped city's outline
from OpenStreetMap, and the update check.
README.md has the code layout and the build commands. This file holds the rules for working
on it.

## Releases and versions

- **1.0.0 shipped on the owner's call (2026-11-15).** A GitHub release happens only when the
  owner says a version is ready to go out; never cut one on your own.
- A release carries the APK signed with the release key, attached under the fixed name
  `stamp.apk`, so `https://github.com/marcusbandit/stamp/releases/latest/download/stamp.apk`
  always serves the newest one. The owner's site links there.
- The tag is `v` plus `versionName`. Every build that leaves this machine gets a higher
  `versionCode` and a new `versionName` first, in `app/build.gradle.kts`. Never install two
  different builds under the same code.
- Branches, pushes and PRs are fine: `<type>/<issue>-<slug>`, a `code-review --comment` pass,
  then squash merge.

## Product rules

- **Locations come from the user's own files, read on this phone.** The scan reads EXIF GPS through MediaStore,
  on the device, and with the owner's go-ahead (2026-11-13) the app may ask for the coarse
  location once per launch to mark the country the phone is in; it is asked in the
  introduction and switched off in Settings. With the owner's ask (2026-11-16) the app may also
  note the coarse location about once an hour, at a random moment, for the heat map: opt-in,
  asked in the introduction and in Settings, Heat map, with Allow all the time since it runs
  while the app is closed. The points stay on the phone in `seen.tsv` and go in the backup.
- **Google Photos comes in through Takeout only (owner's ask, 2026-11-16).** No Google API
  returns photo locations, so Settings, Memories, Import from Google Photos walks the user
  through a Google Takeout export and reads the zips they pick, on the phone: only the location
  files inside, never the images. The places go to `seen.tsv` for the heat map and the countries
  through the scan's review, and they stay when the photos or the zips are deleted. Stamp never
  signs in to Google.
- **Memories may use the system photo picker.** Attaching photos and videos to a visit's stops
  offers suggestions from this phone (taken on those dates near that place) plus the system
  gallery picker, with a lasting read grant. Videos never autoplay inside the app.
- The user's data never leaves the phone. There are no accounts. With the owner's go-ahead
  (2026-11-13) the app may download public map data: per-country rail and road packs from the
  `marcusbandit/stamp-data` repository, asked for by country code only, kept outside the backup
  and switched off in Settings. With the owner's ask (2026-11-16) a tapped city with no bundled
  outline has its outline fetched from OpenStreetMap (Nominatim, then Overpass), asked for by the
  city's name, country code and centre only: kept on the phone outside the backup for a city the
  user logged, in memory for the session otherwise, and switched off in Settings, Map. Nothing
  about the user goes with any request.
- **The GitHub copy updates itself (owner's ask, 2026-11-15).** On launch it asks the GitHub API
  for the latest release, prompts once per new version, and on Update downloads `stamp.apk` into
  Android's installer (`data/Updates.kt`). A switch in Settings, About Stamp turns the check off.
  The Play bundle (`scripts/bundle.sh`, `-Pstamp.play=true`) drops the install permission and the
  check, since Play forbids self-updating apps. Every release must carry `stamp.apk`, or no copy
  sees it.
- Every input beyond the country itself is optional: dates (year, month or day), ratings,
  visit counts, cities. A country with no other details is a complete entry.
- Onboarding introduces every feature that needs setup: the photo scan, and the photo access
  that memory suggestions reuse.

## The fold

Every screen works folded, unfolded and half folded, and the folded phone gets the best
experience. Nothing crosses the seam, and a bend moves lines without rebuilding anything.
`ui/Seam.kt` holds the pieces that make this automatic:

- Dialogs use `SeamAlertDialog` or `SeamDialog`, never Material's `AlertDialog` or a bare
  `Dialog`. Bent, they sit on one half.
- Full-screen pages go inside `HalfPage`, which gives them one half when bent and leaves the map
  on the other.
- Two-pane screens use `Split` (in `Layouts.kt`). Which pane shows what lives in
  `PaneRules.kt`, with tests.
- Navigation is a bottom bar on the folded phone and a rail on the right everywhere else. It
  never goes on the left.

## The loop

Develop on the emulator, never stop at a green build. Every script runs inside a capped
systemd scope; never call `./gradlew` directly.

    scripts/emulator.sh -skip-adb-auth   headless stamp_fold AVD
    scripts/run.sh                       build, install, launch
    scripts/shot.sh <name>               PNG into captures/ (gitignored)
    scripts/build.sh :app:testDebugUnitTest

- A fresh Play Store AVD shows "Allow USB debugging" with no window to tap, so boot it with
  `-skip-adb-auth`.
- `run.sh` uses bare `adb`. Disconnect any wireless phone first (`adb disconnect`) or it may
  install on the wrong device.
- Stop the emulator with `adb shell sync && adb emu kill`, never a bare kill.
- Test photos with GPS: write JPEGs with PIL, GPS rationals as floats and DateTimeOriginal in
  IFD 0x8769, push to `/sdcard/Pictures/`, then send `MEDIA_SCANNER_SCAN_FILE` for each one.
  Use invented places and dates only.

## Signing

The release key lives in `~/.config/stamp/` on one build machine (`upload.jks` plus
`upload.properties`, made by `scripts/setup-upload-key.sh`), never in the repo. It signs the
public APK and the Play bundle alike, so Play is set up with this key as its app signing key
and a phone can move between the download and the store. Losing it means no update can ever
install over an existing copy, so keep a private backup of that folder.

    scripts/apk.sh       the public APK as stamp.apk, signature checked
    scripts/bundle.sh    the Play bundle, same key, signature checked

There is one release key, not one per machine. A second machine that builds releases gets a copy
of `~/.config/stamp/`; running `scripts/setup-upload-key.sh` there would make a different key.

A plain `scripts/build.sh :app:assembleRelease` without the property signs with the build
machine's debug key. That build cannot update a release-key install, and the other way round,
which fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Never uninstall to get past that on the
owner's phone without a backup first: Settings, Save a backup, then Restore after.

## Installing on the owner's phone

The phone gets `stamp.apk` from `scripts/apk.sh`, never the debug build: a debuggable Compose
build is several times slower and makes the app feel broken.

The owner's phone held debug-key builds up to 0.47.1, so the first release-key install cannot go
over them. Once, and only with the owner there: in the app, Settings, Save a backup; check the
file exists; `adb uninstall com.marcusrosado.stamp`; install `stamp.apk`; then Settings, Restore
from a backup. After that, `install -r` works for every later release.

Use plain adb over Wi-Fi first: `adb mdns services` finds the phone's `_adb._tcp` entry on the
LAN, then `adb connect <ip>:5555` and `adb -s <ip>:5555 install -r <apk>`. Only when the phone
is off Wi-Fi, connect to its address on the private network instead. Addresses are discovered,
never written into this repo. After installing, run
`adb shell cmd package compile -m speed -f com.marcusrosado.stamp` so the first taps are not
spent in the JIT.

## Public repo

The repository is public. No emails, IPs, hostnames, home paths or real personal data in code,
fixtures, screenshots, commits, issues or PRs.

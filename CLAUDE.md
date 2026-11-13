# CLAUDE.md

Stamp is an Android travel tracker in Kotlin and Jetpack Compose. You mark countries on a
MapLibre world map drawn from bundled Natural Earth shapes, with no tiles and no network.
README.md has the code layout and the build commands. This file holds the rules for working
on it.

## Releases and versions

- **No GitHub releases.** Never run `gh release create` and never push a version tag until the
  owner says a version is ready. Old releases were deleted on purpose.
- **versionName stays below 1.0.** 1.0 is the release, and only the owner calls it. Within 0.x,
  bump as often as you like.
- Every build that goes to the owner's phone gets a higher `versionCode` and a new
  `versionName` first, in `app/build.gradle.kts`. Never install two different builds under the
  same code.
- Branches, pushes and PRs are fine: `<type>/<issue>-<slug>`, a `code-review --comment` pass,
  then squash merge.

## Product rules

- **Locations come from this phone, or nowhere.** The scan reads EXIF GPS through MediaStore,
  on the device, and with the owner's go-ahead (2026-11-13) the app may ask for the coarse
  location once per launch to mark the country the phone is in; it is asked in the
  introduction and switched off in Settings. There is no Google Photos route for finding countries, no Takeout
  import, and no picker used to import places. No Google API returns photo locations.
- **Memories may use the system photo picker.** Attaching photos and videos to a visit's stops
  offers suggestions from this phone (taken on those dates near that place) plus the system
  gallery picker, with a lasting read grant. Videos never autoplay inside the app.
- Nothing leaves the phone. There are no accounts and no network calls.
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

## Installing on the owner's phone

The phone gets the minified release build, never the debug one: a debuggable Compose build is
several times slower and makes the app feel broken. `scripts/build.sh :app:assembleRelease`
signs it with the build machine's debug key. Each machine has its own, so a build from a
different machine than the installed one fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Never
uninstall to get past that, since it wipes the owner's marks; build on the machine that made the
installed copy.

Use plain adb over Wi-Fi first: `adb mdns services` finds the phone's `_adb._tcp` entry on the
LAN, then `adb connect <ip>:5555` and `adb -s <ip>:5555 install -r <apk>`. Only when the phone
is off Wi-Fi, connect to its address on the private network instead. Addresses are discovered,
never written into this repo. After installing, run
`adb shell cmd package compile -m speed -f com.marcusrosado.stamp` so the first taps are not
spent in the JIT.

## Public repo

The repository is public. No emails, IPs, hostnames, home paths or real personal data in code,
fixtures, screenshots, commits, issues or PRs.

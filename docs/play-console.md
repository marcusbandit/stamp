# Play Console notes

Answers for the forms Play Console asks for, so they match what the app does. Update this file
when a feature changes what the app reads or stores.

## Store listing

Text and images live in `fastlane/metadata/android/en-US/`, in the layout fastlane `supply` and
F-Droid read:

| File | Console field | Limit |
|---|---|---|
| `title.txt` | App name | 30 |
| `short_description.txt` | Short description | 80 |
| `full_description.txt` | Full description | 4000 |
| `images/icon.png` | App icon, 512 x 512 | |
| `images/featureGraphic.png` | Feature graphic, 1024 x 500 | |
| `images/phoneScreenshots/` | Phone screenshots | 2 to 8 |
| `images/tenInchScreenshots/` | Tablet screenshots | up to 8 |

- Category: Travel & Local
- Privacy policy URL: https://marcusbandit.github.io/stamp/privacy
- Contact email: set in the console, never in this repo

## Data safety

- Does the app collect or share any of the required user data types? **No.**
- Data is processed on the device only, so nothing counts as collected. Photo locations, the
  places read from a Google Takeout export, and the rough location (once per launch, and hourly
  when the user turns the heat map on) are read on the device and never transmitted. The internet
  permission is used only for public map data: rail and road files asked for by country code, and
  a tapped city's outline from OpenStreetMap asked for by the city's name, country code and centre.
  No user data goes with either. The
  Play bundle has no update check and no install permission: `scripts/bundle.sh` builds it with
  `-Pstamp.play=true`.
- Is data encrypted in transit? Yes: every request is HTTPS, map file downloads and outline lookups alike.
- Why the outline lookups are not "shared location": they name a city the user tapped on the map,
  not where the phone is, and carry no identifier. If Play reads it differently, declare
  "Approximate location, shared, not collected, for app functionality" rather than drop the feature.
- Can users request deletion? Uninstalling deletes everything; the form question is not shown
  when nothing is collected.

## Photo and video permissions declaration

Play asks apps holding `READ_MEDIA_IMAGES` or `READ_MEDIA_VIDEO` to explain why the photo picker
is not enough. Suggested answer:

> Stamp builds a map of the countries a person has travelled to by reading the GPS location and
> date stored in every photo and video on the device. This scan is the app's core feature and
> needs the whole library: a typical library has thousands of items, and the system photo picker
> removes location metadata from the files it returns, so it cannot answer where a photo was
> taken. The same access lets Stamp suggest photos taken on a visit's dates near that place.
> Everything is read on the device, and nothing read from photos is ever transmitted. Users can grant partial
> access, and the app also offers the system picker for attaching single photos to a visit.

## Location permissions declaration (background location)

The Play bundle asks for `ACCESS_BACKGROUND_LOCATION` only when the user turns on the hourly look
for the heat map. Play reviews every app that holds it, and the console form asks for:

- **Feature that needs it:** Heat map. "About once an hour, Stamp reads the phone's approximate
  location and adds it to a heat map of where the user spends their time, built over months. It
  must run while the app is closed, since the point is to sample the user's normal day. The
  location is coarse only, is stored on the device, and is never transmitted. The feature is off
  until the user turns it on. Settings, Heat map turns it off, and Clear the heat map there
  deletes every point."
- **Prominent disclosure:** shown before every ask, in the app's own words. The introduction's heat
  map page says it in its text, and the switch in Settings, Heat map opens a dialog first:
  "Stamp collects your approximate location about once an hour, even when the app is closed or not
  in use, to build your heat map. It stays on this phone." Its buttons are Turn on and Not now.
- **Video:** up to 30 seconds on YouTube (unlisted is fine) showing the disclosure, the two Android
  prompts (While using the app, then Allow all the time), and the heat map. Record it on a phone or
  the emulator with `adb shell screenrecord`.
- **Risk:** Play rejects background location that is not core to the app. If it is refused, the Play
  bundle can ship without the hourly look and keep the heat map from photos only.

## Stamp Pro

The Play bundle sells Pro, rail and road lines plus the heat map, as one in-app product. A build
from source and the GitHub APK have every feature (LICENSE.md).

1. Payments profile: Play Console, Settings, Payments profile. A merchant account is needed
   before any product can be priced.
2. Monetize with Play, Products, One-time products, Create one-time product. Product ID
   `stamp_pro` exactly, the id `data/ProStores.kt` in `src/play` asks for. Name "Stamp Pro".
   Add a purchase option, set the price, then Activate.
3. Add the testers' accounts under Settings, License testing, so the closed test can buy Pro
   without being charged.
4. Data safety: Google Play handles the payment; Stamp only asks Play whether Pro is owned, so the
   form's answers do not change.

## Other forms

- App access: everything works without an account or login.
- Ads: the app contains no ads.
- Content rating: no violence, sexual content, language, drugs, gambling, user-to-user
  communication or location sharing. Expect Everyone / PEGI 3.
- Target audience: 13 and over. The app is not designed for children.
- News app: no. Government app: no. Financial features: none. Health: none.
- Foreground services, exact alarms, full-screen intents: none used.

## Release

1. The release key already exists in `~/.config/stamp/` on one build machine; back that folder up. It
   also signs the APK on GitHub, so Play must use it as the app signing key, or a phone that
   installed the download can never take an update from the store.
2. When creating the app, open Play App Signing, choose "Use a different key", then "Export
   and upload a key from Java keystore", and follow its PEPK steps with `upload.jks` and the
   alias `upload`. Play then signs with this same key.
3. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
4. `scripts/bundle.sh` writes `app/build/outputs/bundle/release/app-release.aab`.
5. New personal developer accounts must run a closed test with at least 12 testers for 14 days
   before production access opens.

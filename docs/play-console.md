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
- Data is processed on the device only, so nothing counts as collected. Photo locations and the
  rough location are read on the device and never transmitted. The internet permission is used
  only to download public rail and road files by country code; no user data goes with it.
- Is data encrypted in transit? Yes: the only traffic is HTTPS downloads of public map files.
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

## Other forms

- App access: everything works without an account or login.
- Ads: the app contains no ads.
- Content rating: no violence, sexual content, language, drugs, gambling, user-to-user
  communication or location sharing. Expect Everyone / PEGI 3.
- Target audience: 13 and over. The app is not designed for children.
- News app: no. Government app: no. Financial features: none. Health: none.
- Foreground services, exact alarms, full-screen intents: none used.

## Release

1. Once per build machine: `scripts/setup-upload-key.sh`, then back up `~/.config/stamp/`.
2. Enrol in Play App Signing when creating the app, and upload the certificate the setup script
   printed if the console asks for the upload key.
3. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
4. `scripts/bundle.sh` writes `app/build/outputs/bundle/release/app-release.aab`.
5. New personal developer accounts must run a closed test with at least 12 testers for 14 days
   before production access opens.

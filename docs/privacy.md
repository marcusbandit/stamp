---
title: Stamp privacy policy
---

# Stamp privacy policy

Last updated 16 November 2026, for the heat map, the Google Photos import and city outlines.

Stamp keeps everything you enter on your phone. It has no account, no ads and no analytics, and
it never sends your marks, trips, places or photos anywhere.

That also means nobody, including the developer, can recover your data if it is lost. If you
delete the app, reset or lose your phone, your marks are gone unless your own Android backup
holds a copy.

## What Stamp reads

When you ask it to scan your photos, Stamp reads the location and date saved inside each photo
and video on the phone and works out which countries they were taken in. It reads the same
details when it suggests photos for a stop on a visit. The pictures themselves are never copied,
changed or sent anywhere.

When you attach a photo or video to a visit or a place, Stamp keeps a link to that file so it can
show it later. The file stays where it is in your gallery.

When you import from Google Photos, you download a Google Takeout export yourself and choose its
zip files in Stamp. Stamp reads only the small location files inside, the place and date of each
photo, and never the pictures. Stamp never signs in to your Google account.

If you turn on the hourly look for the heat map, Stamp asks the phone's own location service
for its rough position about once an hour, at a random moment, also while Stamp is closed. The
position is saved on the phone and never sent anywhere. You can turn it off and clear every saved
place in Settings, Heat map.

## What Stamp stores

Your marks, visits, dates, ratings, notes, photo links and the places of your heat map are saved
in the app's private storage on this phone, and in the backup file you save yourself. The places
a photo scan or an import found stay after you delete those photos or the export; only the
pictures are gone. Uninstalling Stamp deletes everything.

If Android backup is turned on in your phone's settings, Android may include this storage in your
own device backup. That backup belongs to your Google account; the Stamp developer cannot see it.

## What Stamp downloads

When a trip has a leg by train, car or bus, Stamp downloads the main railways and big roads of
the countries that trip goes through, so the leg can follow the real line. Each download asks for
one country's file by its two-letter code from GitHub, which hosts the files, and nothing else.
As with any download, GitHub sees the address your phone connects from; the request carries no
account, no identifier and nothing about your trips. You can turn this off in Settings, Map.

These files are map data, not your data. They are kept outside your backup and are fetched again
when needed.

When you tap a city on the map that Stamp has no outline for, it asks OpenStreetMap's public
servers (nominatim.openstreetmap.org, and overpass-api.de run by FOSSGIS) for the outline, sending the city's name, its country code and
the area around its centre. The request carries nothing about you or your trips; like any
website, those servers see the address your phone connects from. An outline of a city you marked
is kept on the phone, outside your backup; any other is forgotten when the app closes. You can
turn this off in Settings, Map.

A copy of Stamp installed from its GitHub release also asks GitHub, when it opens, which version
is the latest. The request names no one and carries nothing about you. If you tap Update, Stamp
downloads the new version from the same release and hands it to Android's installer. You can turn
the check off in Settings, About Stamp. A copy installed from Google Play never does this; Play
updates it.

## What Stamp shares

Nothing. Stamp does not collect personal data and does not share data with anyone.

## Permissions

- Photos and videos: to read where and when they were taken. You can allow all, some or none.
- Photo location: Android hides the location inside photos unless an app asks for this.

- Rough location, if you turn on "Mark the country I'm in": to find which country the phone is in.
  It is read on the phone and never sent anywhere.
- Rough location all the time, if you turn on the hourly look: to add a point to your heat map
  about once an hour while Stamp is closed. It is read on the phone and never sent anywhere.
- Internet: only for the rail and road files, city outlines and the updates described above.
- Install apps, in the GitHub copy only: to install a newer Stamp when you tap Update. Android
  asks you once before it allows this.

## Children

Stamp collects no data from anyone, children included.

## Changes and contact

Changes to this policy are published on this page. Questions go to the issue tracker at
<https://github.com/marcusbandit/stamp/issues>.

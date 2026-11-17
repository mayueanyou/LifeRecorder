# Roadmap

Life Recorder is heading towards a full on-phone version of a day log that
already runs as a self-hosted web app: stays, journeys, routes, place names
and spending, kept on the phone and optionally written to your own calendar.
It is built to be publishable on the Play Store, which shapes every choice
below.

## Done

- **Screen time** — per day, per app, unlocks; history saved beyond Android's
  week.

## Next, in order

1. **Location tracking** — a foreground service (Android's "location" type,
   with its ongoing notification) that records fixes with a durable on-phone
   queue. Doze-proof by design: no network is needed to record.
2. **Stays and journeys** — the same rules the web app arrived at the hard
   way: a stay is anchored where it began and re-centred once; silence does
   not end it; imprecise fixes and jumps are ignored; a slow walk is a pause,
   not a stay; once a stay, always a stay; records split cleanly at midnight
   with no slivers; journeys cut into drive and walk by sustained vehicle
   runs, measured along the route.
3. **Map and timeline** — OpenStreetMap tiles (no Google billing), routes
   with direction arrows, one pin per place, a calendar-style day timeline.
4. **Place names** — your own named places first, then OpenStreetMap
   (buildings, streets for where you parked). No paid lookups.
5. **Spending** — card notifications read with a notification listener the
   user enables, each charge matched to one stay; several cost items per
   event typed by hand.
6. **Calendar** (optional) — write stays and journeys to a calendar on the
   phone through Android's own calendar provider, so no Google sign-in is
   needed at all.
7. **Export** — a day or a month as JSON or GPX.

## Play Store checklist

- Background location declaration, prominent disclosure screen, and a demo
  video showing location is the app's core feature.
- Notification-listener disclosure, before the user is sent to enable it.
- Data safety form: everything stored on device, nothing collected.
- Public privacy policy page.
- Closed test with the required number of testers before production.
- Release signing key, kept out of this repository and backed up.
- Raise the target SDK each year as Play requires.

## Deliberately left out

- **Gmail** (reading bank emails): a restricted Google scope that would need
  a paid security assessment every year for a public app.
- **Google Maps / Places**: costs grow with users, and a key inside an app
  can be extracted.

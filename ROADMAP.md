# Roadmap

Life Recorder is heading towards a full on-phone version of a day log that
already runs as a self-hosted web app: stays, journeys, routes, place names
and spending, kept on the phone and optionally written to your own calendar.
It is built to be publishable on the Play Store, which shapes every choice
below.

## Done

- **Screen time** — per day, per app, unlocks; history saved beyond Android's
  week.
- **Location recording** — a foreground service (Android's "location" type,
  with its ongoing notification) writing every fix to the phone's database;
  10 m between fixes at GPS accuracy, nothing while still; resumes after a
  reboot. No network needed to record.

- **Stays and journeys** — the web app's rules, ported and checked against
  it: on three real days, every one of 32 stays and journeys matched to the
  minute, with the same mode and distance.
- **Map and timeline** — OpenStreetMap, routes with direction arrows, one pin
  per place, a calendar-style day; selecting one side selects the other.
- **Place names** — your own places, then OpenStreetMap buildings and streets,
  looked up once per spot and kept.

- **Drive backup** — each day as JSON to your own Google Drive, nightly, with
  restore; only the drive.file permission.
- **Spending** — card notifications read with a notification listener,
  each charge matched to one stay; several typed items per event; "No cost";
  month and day totals and a month calendar.

## Next, in order

1. **Calendar** (optional) — write stays and journeys to a calendar on the
   phone through Android's own calendar provider, so no Google sign-in is
   needed at all.
3. **Export** — a day or a month as JSON or GPX.
4. **Month view** — totals over a month: time per place, distance, spending.

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

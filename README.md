# Life Recorder

An Android app that keeps a private diary of your day: how you spent your
time on your phone today, and — as it grows — where you went, how you got
there, and what you spent.

Everything stays on the phone. There is no account and no server, and
nothing is sent anywhere.

## What it does now

**Record.** Your day as a map and a calendar. The map shows each journey as
its route — blue driving, green walking, violet cycling — with sampled dots,
arrows showing the way you went, and one numbered pin per place (a place you
returned to shares a pin, "1·3"). Below it, the day as a 24-hour timeline:
each stay and journey placed at its time and as tall as it lasted, a red line
at now. Tap either side and the same entry lights up orange on the other.

Stays and journeys are worked out on the phone from its own recorded
positions, by the same rules as the self-hosted Record web app this grew
out of: a stay is anchored where it began and re-centred once; a quiet phone
does not end a stay; imprecise positions and jumps are ignored; a slow walk
is a pause, not a stay; a journey is cut into drive and walk by sustained
vehicle speed and measured along its route. Each day is rebuilt from scratch
from its positions, so midnight is a clean cut rather than state to roll
over.

Places are named by your own named places first, then OpenStreetMap: a
named building the spot is inside; otherwise the most likely named place
within 150 m (a shop or cafe before an office, never a car park); otherwise
the nearest address, "near 120 Main Street". Where you parked is named by
the street. Each spot is looked up once and the name kept; turn lookups off
in Settings and the app never goes online for names.

**Money.** What you spent, read only from your bank's notifications: each
card charge goes to the stay you were at when it arrived (15 minutes either
side, closest wins), never guessed from the merchant's name. The month's
total and the day's sit at the top; each event shows its cost; tap an event
to see the bank's charges, add your own items, or mark it "No cost". An item
you type with the same amount as a bank charge counts once. Only a
notification that reads as money spent is kept — "payment posted",
balances, refunds and rewards are not. Banks count by default; any other app
that sent a purchase is listed in Settings, off until you turn it on (a
wallet app would count each purchase twice). Tap the day's name for the
month, with what each day cost.

**Backup.** Sign in with Google in Settings and each night your days go to
your own Google Drive — *My Drive › Life Recorder › 2026-11 › 2026-11-17.json*
— as plain JSON: the day's positions, stays and journeys, purchases, typed
costs and screen time, plus your named places. Only the `drive.file`
permission is asked for, so the app can see the files it made and nothing
else in your Drive. Back up now, Restore (adds what the phone lacks, never
replaces) and Sign out are in Settings.

**Screen time.** For each day: total time on screen, the number of unlocks,
and every app you used with how long, longest first.

Each stay and journey also shows what was on your screen during it: amber
marks down its right edge at the times the phone was in use, a line such as
"📱 42m · X 30m, Chrome 8m", and, when you tap it, every app with its times
("X · 2:00–2:30 PM"). Android keeps that detail for about a week, so Life
Recorder saves each app session as it goes.

Numbers come from Android's own usage record — the same source the system's
Digital Wellbeing screen uses — read with the *Usage access* permission you
grant. Android keeps that detail for about a week; Life Recorder saves each
day as it goes, so history lasts longer.

**Settings.** Every permission, one step at a time; recording on and off;
which apps count as money; export of the day's positions; your named places.

Everything stays on the phone. The only things that go online are map tiles,
if allowed the name of a spot, and, if you sign in, your backup to your own Drive.
[Privacy policy](https://mayueanyou.github.io/LifeRecorder/privacy.html).

## Building

Requirements: JDK 17 and the Android SDK (platform 35).

```sh
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## Permissions

| Permission | Why |
|---|---|
| Usage access (`PACKAGE_USAGE_STATS`) | Read which app was on screen, and when. Granted by you in Settings; never at install. |
| Location, including "Allow all the time" | Record where you are while the app is closed. Asked for in steps, the way Android requires. |
| Foreground service (location), notifications | The ongoing "Recording your day" notice that background location needs. |
| Notification access | Read card purchases from your bank's notifications. Granted by you in Settings. Only notifications that read as a purchase are kept, on the phone. |
| Run at startup | Resume recording after the phone restarts, if it was on. |
| Wake lock, network state | Added by Android's WorkManager library, which saves recent screen time in the background. |

The app deliberately does **not** request `QUERY_ALL_PACKAGES`; app names
and icons come from the apps that appear in your launcher.

## Checking the rules against real data

`ReplayTest` replays a file of positions through the engine and writes the
stays and journeys it found, so they can be compared line by line with
another implementation of the same rules. The file is never part of the
repository; the test skips itself without one:

```sh
LR_FIXTURE=/path/fixes.json LR_OUT=/path/out.txt ./gradlew testDebugUnitTest --tests '*ReplayTest*'
```

See [ROADMAP.md](ROADMAP.md) for where it is going.

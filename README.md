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
building that contains the spot, or for where you parked, the street. Each
spot is looked up once and the name kept; turn lookups off in Settings and
the app never goes online for names.

**Screen time.** For each day: total time on screen, the number of unlocks,
and every app you used with how long, longest first.

Numbers come from Android's own usage record — the same source the system's
Digital Wellbeing screen uses — read with the *Usage access* permission you
grant. Android keeps that detail for about a week; Life Recorder saves each
day as it goes, so history lasts longer.

**Settings.** Every permission, one step at a time; recording on and off;
export of the day's positions; your named places.

Everything stays on the phone. The only things that go online are map tiles
and, if allowed, the name of a spot.

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

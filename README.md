# Life Recorder

An Android app that keeps a private diary of your day: how you spent your
time on your phone today, and — as it grows — where you went, how you got
there, and what you spent.

Everything stays on the phone. There is no account and no server, and
nothing is sent anywhere.

## What it does now

**Screen time.** For each day: total time on screen, the number of unlocks,
and every app you used with how long, longest first. Step back through
earlier days with the arrows.

Numbers come from Android's own usage record — the same source the system's
Digital Wellbeing screen uses — read with the *Usage access* permission you
grant in Settings. A session runs from the moment an app comes to the front
until another app does or the screen goes off; the home screen is not
counted; a session that crosses midnight is split between the two days.

Android keeps this detail for about a week. Life Recorder saves each day as
it goes (and every few hours in the background), so history lasts longer
than that.

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
| Wake lock, run at startup, network state, foreground service | Added by Android's WorkManager library, which saves recent days in the background. None of them asks you for anything. |

The app deliberately does **not** request `QUERY_ALL_PACKAGES`; app names
and icons come from the apps that appear in your launcher.

See [ROADMAP.md](ROADMAP.md) for where it is going.

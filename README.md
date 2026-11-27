# AQI Bar

[![Release](https://img.shields.io/github/v/release/THEME26/aqi-widget)](../../releases/latest)
[![Licence](https://img.shields.io/github/license/THEME26/aqi-widget)](LICENSE)

Air quality at a glance: four home-screen widgets and one persistent notification, both driven by the same reading. Values are computed on-device from raw pollutant concentrations, so the app works without an API key.

## Download

Take the latest APK from [Releases](../../releases/latest). Android will ask you to allow installing from unknown sources. Android 8.0 (API 26) or newer.

Versions before 1.1.0 used a different application id, so 1.1.0 installs alongside them rather than replacing them: uninstall the old copy once the new one shows a reading. From 1.1.0 onwards updates install in place.

To follow releases without a store, [add AQI Bar to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium%3A%2F%2Fapp%2F%7B%22id%22%3A%22io.github.theme26.aqibar%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FTHEME26%2Faqi-widget%22%2C%22author%22%3A%22THEME26%22%2C%22name%22%3A%22AQI%20Bar%22%7D) — one tap on a phone that already has it.

## Features

### Four widgets

| Size | Contents |
| --- | --- |
| **4×2** | Value, level name, gauge ring, station, dominant pollutant, timestamp |
| **2×1** | Gauge ring, value, action word, station |
| **1×2** | Vertical: value, level, area reading |
| **1×1** | Square: level colour fill and the number only |

All widgets read from one cached reading and refresh together, so the number, the colour and the ring never disagree.

### Persistent notification

A silent, non-dismissible notification keeps the current value in the status bar. The small icon is a face rather than a number, because the status bar only keeps an alpha silhouette and six levels of fill are indistinguishable at 24 dp:

| Level | Icon | Meaning |
| --- | --- | --- |
| 0–100 | smiling face | fine to go outside |
| 101–200 | face with mask | wear a mask |
| 201+ | masked face with slash | stay indoors |

This is a numeric grading hint, not medical advice.

On the lock screen the notification shows the number only. Station names are omitted there because they imply a rough location; a setting switches to the full version.

### Live Updates (Android 16+)

On Android 16 and later the notification can be promoted to a system Live Update, which places it on the lock screen and the always-on display. The system draws it — the app holds no foreground service and never wakes the screen. If the lock screen stays empty, the channel-level *Live Updates* toggle must be enabled in system settings; the app reports the current state and links to that page.

Before Android 16 — or for anyone who prefers the plain form — **Settings → Lock-screen card** turns the promotion off and the notification goes back to being an ordinary ongoing one in the shade.

### Lock screen widgets (Android 16 QPR1+)

All four sizes can be placed on the lock screen. Which of them a given lock screen offers is decided by its slots, not by the app: a theme that reserves a row of 1×1 boxes takes the 1×1 widget and nothing larger, while the AOSP lock-screen page added in Android 16 QPR1 is about 4×3 and takes the 4×2.

Some lock screens fill that list with **app shortcuts** rather than widgets — the entries under WeChat in ColorOS 16 (*pay*, *ride code*, *scan*) are the same items that appear when you long-press its icon. AQI Bar declares three of its own: **Refresh**, **Settings**, and one relabelled on every render with the current reading. They show up on a launcher's long-press menu regardless, and are what a lock screen would read if it chose to.

Where to look:

- **ColorOS 16 (OPPO / OnePlus):** *Settings → Home screen, Lock screen & style → All Flux themes* → pick a theme → edit → **Add widget** on the lock-screen page.
- **Android 16 QPR1 (Pixel):** *Settings → Wallpaper & style → More lock screen settings → Widgets on lock screen*, then swipe left from the lock screen.

On the AOSP page the slot is roughly four cells wide by three tall, one cell taller than the 4×2 was drawn for, so the reading centres in a slightly taller card. Tapping any of them asks for the PIN before opening the app — the system default, and the right one for a screen anyone can pick up.

Note on manufacturer lock screens: some of them do not list third-party widgets at all, whatever size is offered. ColorOS 16 was tested with all four sizes declared, and with `Refresh` and `Settings` shortcuts declared, and the app still did not appear in *Add widget*; the same list does show WeChat's pay and scan entries, so the filter is not simply "first-party only". No documented condition has been found that would let a third-party app in. On such a device the lock screen is not a reachable surface, and the notification is the way to see a reading without unlocking.

### Stale data is visible

When the last successful refresh is more than twice the refresh interval ago, the whole card switches to a neutral palette and the ring keeps its last filled segments. The number stays, because it is still a real observation — the colour simply stops claiming that the air is like this *now*.

### Readings

- **US AQI** with EPA 2024 breakpoints, computed locally for PM2.5, PM10, O₃ (8 h), NO₂ (1 h), SO₂ (1 h) and CO (8 h), including unit conversions.
- **CN AQI** with HJ 633-2012, from the same raw concentrations.
- Averaging follows the breakpoint tables — 24 h / 8 h / 1 h windows rather than the instantaneous peak.

### Area context

Around the current location the app samples grid points on a circle at 10, 25 or 50 km and reports either the median or the worst cell, so a single station is not mistaken for the whole district.

### Data sources

| Source | Key | Notes |
| --- | --- | --- |
| Open-Meteo (CAMS grid) | none | Default. Global model data. |
| WAQI | free token | Station-level readings. Optional. |

Both can be displayed side by side for comparison. Each line is labelled with its own basis, and a missing key shows a placeholder instead of reusing the other source's value.

### Getting a WAQI token

The token is free and issued directly by the World Air Quality Index Project. The procedure is the same everywhere — only the language of the form changes, so mainland China is not a special case:

1. Open the request form: [English](https://aqicn.org/data-platform/token/) · [简体中文](https://aqicn.org/data-platform/token/cn/) · [繁體中文](https://aqicn.org/data-platform/token/hk/) · [한국어](https://aqicn.org/data-platform/token/kr/) · [日本語](https://aqicn.org/data-platform/token/jp/). Other languages are linked from the same page.
2. Tick the box confirming you accept the Terms of Service.
3. Enter an email address and a name, then submit.
4. The token arrives by email, normally within a few minutes. If nothing shows up, check the spam folder before requesting again — repeated requests create new tokens rather than resending the old one.
5. Paste it into **Settings → WAQI token** in the app. It is stored locally and excluded from cloud backup.

Terms that matter before you use it:

- Free for non-commercial use. The data may not be sold, bundled into a paid app, or redistributed as a cached archive.
- Attribution to the World Air Quality Index Project is mandatory. It is listed under [Attribution](#attribution) below, and the app labels every WAQI-derived line as `WAQI`.
- The default quota is 1,000 requests per second, far above what a single phone needs.
- Aqicn.org has no separate registration path for any region. The `/cn/` and `/hk/` links above are language versions served from the same host, not a different application process.

If you skip this step entirely the app still works — Open-Meteo is the default source and needs no key.

### Languages

Chinese, English, Japanese, Korean, French, Spanish and German.

## Install

1. Install the APK and open the app.
2. Grant the location permission — *while in use* is enough; background location is never requested — or enter coordinates manually.
3. Allow notifications on Android 13+.
4. Add a widget: long-press the home screen → Widgets → **AQI Bar**.
5. Optional: to show the reading on the lock screen, enable the Live Update toggle in the app's notification channel settings.

The refresh interval is configurable at 30, 60 or 120 minutes.

## Build from source

Requirements: JDK 17 and Android SDK Platform 35 with Build Tools 35. Gradle 8.7 is pinned by the wrapper.

```bash
./gradlew :app:assembleDebug          # APK in app/build/outputs/apk/debug/
./gradlew :app:assembleRelease        # APK in app/build/outputs/apk/release/
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:lintDebug              # lint report
```

The release build is signed only if a `keystore.properties` file is present at the repository root:

```properties
storeFile=release.keystore
storePassword=…
keyAlias=…
keyPassword=…
```

Without that file the release build is left unsigned, which is what a source-based distribution needs — the distributor signs it. Do not commit either file.

The unit tests execute the real drawing code under Robolectric and compare rendered pixels, so gauge geometry and palette changes are verified without a device.

## Privacy

- Location is used only to obtain coordinates for the air-quality request. It is not stored and not sent anywhere else.
- A WAQI token, if supplied, stays in the app's private preferences and is excluded from cloud backup.
- No analytics, no crash reporting, no advertising SDKs. The only network traffic is the request to the selected air-quality source.

## Limitations

- The reading is model or station data, not a measurement at your exact position. It is not medical advice.
- Widget refresh depends on system background scheduling. Aggressive battery optimisation can delay it; the card turns grey when data is old, so a delay is visible rather than silent. Grey is a statement about the age of the reading, not about whether the last request succeeded — a failed request on a reading that is still inside the window keeps its colour and its timestamp.
- Live Update promotion is decided by the system and varies by manufacturer. The app can request it, not guarantee it.
- Widgets need to be added again after installing 1.1.0, because the application id changed.

## Acknowledgements

The code, the resources and this document were produced with AI assistance:

- **WorkBuddy** — agentic development environment in which the whole project was built and reviewed.
- **DeepSeek** — model used for code generation and review.

The tooling wrote it; the requirements and the judgement calls came from a human.

## Licence

GNU General Public License, version 3 — see [LICENSE](LICENSE). Copyright (C) 2026 THEME26.

A permissive licence would let anyone ship this code with an analytics SDK attached and call it the same app. The GPL keeps derivatives open and forces modifications to be visible, which is the whole point of an app whose only promise is that it does nothing behind your back.

## Attribution

- [Open-Meteo](https://open-meteo.com/) — weather and air-quality data, CC BY 4.0.
- [World Air Quality Index Project](https://aqicn.org/) — station data, free for non-commercial use with attribution.
- Breakpoints: US EPA 2024 AQI technical assistance document; China HJ 633-2012.

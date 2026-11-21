# AQI Bar

Air quality at a glance: four home-screen widgets and one persistent notification, both driven by the same reading. Values are computed on-device from raw pollutant concentrations, so the app works without an API key.

## Download

Take the latest APK from [Releases](../../releases/latest). The build is **debug-signed**, so Android will ask you to allow installing from unknown sources. Android 8.0 (API 26) or newer.

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

1. Open the request form: [English](https://aqicn.org/data-platform/token/) · [简体中文](https://aqicn.org/data-platform/token/cn/) · [繁體中文](https://aqicn.org/data-platform/token/hk/). Other languages are linked from the same page.
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
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:lintDebug              # lint report
```

The unit tests execute the real drawing code under Robolectric and compare rendered pixels, so gauge geometry and palette changes are verified without a device.

## Privacy

- Location is used only to obtain coordinates for the air-quality request. It is not stored and not sent anywhere else.
- A WAQI token, if supplied, stays in the app's private preferences and is excluded from cloud backup.
- No analytics, no crash reporting, no advertising SDKs. The only network traffic is the request to the selected air-quality source.

## Limitations

- The reading is model or station data, not a measurement at your exact position. It is not medical advice.
- Widget refresh depends on system background scheduling. Aggressive battery optimisation can delay it; the card turns grey when data is old, so a delay is visible rather than silent.
- Live Update promotion is decided by the system and varies by manufacturer. The app can request it, not guarantee it.
- Only debug-signed builds are published; there is no store listing.
- The project is provided as-is and is not actively maintained. Issues and pull requests may go unanswered.

## Acknowledgements

The code, the resources and this document were produced with AI assistance:

- **WorkBuddy** — agentic development environment in which the whole project was built and reviewed.
- **DeepSeek** — model used for code generation and review.

The tooling wrote it; the requirements and the judgement calls came from a human.

## Attribution

- [Open-Meteo](https://open-meteo.com/) — weather and air-quality data, CC BY 4.0.
- [World Air Quality Index Project](https://aqicn.org/) — station data, free for non-commercial use with attribution.
- Breakpoints: US EPA 2024 AQI technical assistance document; China HJ 633-2012.

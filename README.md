# air-bridge

Relays Android notifications to a Fitbit Air's vibration motor, with separate
rules for calls and for everything else.

The Air has no notification support and no app platform: Google ships it as a
screenless band whose motor only fires for alarms and Smart Wake. This app
drives the motor directly over Bluetooth instead, using
[Kazuma96550/fitbit-air](https://github.com/Kazuma96550/fitbit-air) (MIT), an
independent reverse-engineering of the Air's Golden Gate / CoAP transport.
`UPSTREAM_SHA` records the commit this fork started from.

**This is not an official Google or Fitbit SDK.** It rides private,
version-sensitive device protocols; a firmware or Google Health update can
break it without warning. Do not depend on it for anything that matters.

## What it does

- Pick which apps may buzz the Air, and what each one feels like.
- **Calls get their own rule per app.** The default is *Repeat until answered*:
  the Air pulses every few seconds while the phone, WhatsApp or Instagram is
  ringing, and stops on its own when the call ends.
- Everything else gets one pulse (or two, or a fixed strength, or silence).
- Quiet hours, a 15-minute pause, and a Stop button in the ongoing
  notification.

## How a pulse actually works

The Air exposes no "vibrate" command. It has a vibration-*strength* setting,
and it buzzes once when that setting changes. A pulse is therefore: open a
secure session, write the other strength, write the original back, verify. That
round trip takes **about 2.5 seconds** over a warm link, and Google Health
shares the same channel.

Three consequences you should expect, not work around:

- **A ring is one buzz every ~6 seconds, not a continuous ring.** The interval
  floor is 5 seconds; below that the next session starts before the last pulse
  is felt. Repeat timing is adjustable under Rules.
- **Patterns are counts and cadence, not waveforms.** "Two pulses" is two
  settings writes, not a rhythm. Distinguish apps by repetition, not texture.
- **Ringing delays Google Health syncs.** Upstream leaves a 15-second cooldown
  between sessions for exactly this reason. A ring deliberately bypasses that
  cooldown, because a call you miss is worse than a sync that lands a minute
  late. Calls are rare and short; messages still use the normal cooldown.

## What stops a ring

In order of how fast they land:

| Signal | How |
| --- | --- |
| Call answered | Audio mode becomes in-call, checked before every pulse. Covers WhatsApp and Instagram calls too. |
| Call declined, missed or cleared | The ringing app removes its notification. |
| Phone unlocked | `ACTION_USER_PRESENT`. |
| Silence button | An action on the app's own ongoing notification. |
| Timeout | 45 seconds by default. |

**Double-tapping the Air cannot stop it.** The Air handles taps entirely in
firmware and sends nothing over Bluetooth when you tap it — upstream tested
this directly: four firm double-taps over 90 seconds produced zero packets, and
even dismissing a *real* Fitbit alarm by double-tap produced zero packets.
Wrist on/off *is* reported, but arrives 10–20 seconds late, which is useless
for silencing a call. There is no software fix for this; it needs different
hardware.

## Build and install

Needs the Flutter SDK and an Android SDK. On this machine Flutter lives at
`C:\src\flutter` and is pinned to JDK 17 (`flutter config --jdk-dir`), because
the default `java` on PATH is 25 and Gradle 8.14 rejects it.

```bash
cd app && flutter build apk --release
```

The first build downloads Android NDK 28.2.13676358 (~2.3 GB), which
`integration_test` pins. That download failed once here and succeeded on a
plain retry, so retry before believing a `Failed to install ndk;28.2…` error.
A half-written `ndk/28.2.13676358` holding only `.installer` has to be moved
aside first, or configuration fails with `[CXX1101] … did not have a
source.properties file`.

Build release, not debug: a debug APK here is ~1.4 GB (Vulkan validation
layers, unstripped engine libraries), the release APK ~53 MB.

Install the APK from `app/build/app/outputs/flutter-apk/`, then, in order:

1. Pair the Air in Google Health as usual, and leave it paired — this app does
   not own the pairing and cannot create one.
2. Grant **Nearby devices** while the app is on screen. The background service
   cannot request permissions.
3. Grant **notification access** in Android settings when the app asks.
4. Pick your apps under Rules, set each one's Calls row, and press Start.

Exempt the app from battery optimisation if rings stop arriving when the screen
has been off for a while.

## Limits worth knowing before you rely on it

- Android only. iOS does not let an app read other apps' notifications.
- Arm64 phones only; upstream has not tested other ABIs.
- Background delivery is *evidenced*, not *guaranteed*: upstream has confirmed
  screen-off haptics in release mode, but not deep idle, not after a reboot,
  and not under sustained Google Health contention.
- One ring at a time, and messages are suppressed while a call is ringing — a
  single pulse in the middle of a ring is indistinguishable from the ring.
- A pulse is verified by reading the setting back. "Completed" means the write
  verified, not that you felt it.
- The LED cannot be driven at all; this was tested rather than assumed.
  See below. Colour-coding
  notifications by LED is not possible on this hardware.

## The status light is not reachable

Driving the LED was the original hope, so it was probed properly before
being written off. Three findings, from this phone:

- **It has no resource of its own.** `/led`, `/leds`, `/light`, `/display`,
  `/screen`, `/ui`, `/device`, `/status`, `/notification(s)` and
  `/md/600`–`/md/610` all answer `4.04`. That is stronger than a plain
  not-found: resources that exist behind Google Health's authentication
  answer `4.01`, as `/alarms`, `/sync/status` and `liveactivity` do. So those
  names are absent from the routing table, not hidden from us.
- **The device has exactly two settings.** `/settings` refuses `GET` with
  `4.05`, so it was enumerated with the selector-only message the haptic read
  already uses. Of the 126 selectors that fit that message's single byte, one
  answered: selector 2, replying `0a 04 12 02 08 00`, the same shape as
  haptics one field across. Selectors beyond 127 need a two-byte varint and
  were not swept.
- **Two other resources exist and are not lights.** `/sync/response` refuses
  `GET` with `4.05`, so it is write-only like `/settings`; it carries the sync
  protocol and is deliberately left alone, since probing it means sending
  messages of unknown shape into the mechanism that moves activity and sleep
  data. `/time` answers `4.01`. In `/md/600`–`/md/699`, only `606` exists.
- **Setting 2 does nothing visible.** Written to 1, held two seconds, and
  restored — twice, verified each time. No light, no vibration. Unlike
  haptics, whose whole usefulness is that *changing* it makes the Air buzz,
  setting 2 has no side effect to borrow.

So the light is driven by firmware from device state — battery on a
double-tap, low battery, charging, firmware update — and nothing the phone
can send reaches it. Colour-coding alerts by LED is not possible on this
hardware, and neither is a bare flash.

The probes live in `Developer tools` and are bounded: the resource probe only
reads, the settings probe sends a selector carrying no value, and the toggle
restores itself even when a step fails.

## What this fork changed

All changes are in `app/`; the plugin under `lib/` and `android/` is upstream's.

| File | Change |
| --- | --- |
| `RelayAlertKind.kt` | New. Classifies call vs message; ring interval/duration settings. |
| `RelayRulePolicy.kt` | `RING` preset, per-app `callPreset`, kind-aware matching. |
| `RelayRuleStore.kt` | Config v3: call presets and ring timing, v2 decodes with defaults. |
| `CalendarAlertFilter.kt` | Stops discarding ongoing notifications when they are calls. |
| `CalendarNotificationListener.kt` | Classifies alerts; reports removals so a ring can stop. |
| `CalendarRelayService.kt` | Ring sessions: repeat, stop conditions, Silence action. |
| `rules_panel.dart`, `vibration_editor.dart`, `relay_settings.dart` | Per-app Calls row, repeat timing, ring preset. |

Licensing is unchanged: MIT for the fork, Apache-2.0 for the bundled Golden
Gate transport. See `LICENSE`, `LICENSE-APACHE-2.0` and
`THIRD_PARTY_NOTICES.md`.

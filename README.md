# Air Bridge

Calls and notifications on a Fitbit Air, which Fitbit's own app cannot do.

The Air is screenless and ships with no notification support: its motor fires
for alarms and Smart Wake and nothing else. This app drives that motor over
Bluetooth, so your phone's calls and notifications reach your wrist.

Android only. Sideloaded. **Unofficial**: it rides a reverse-engineered,
version-sensitive device protocol, and a firmware or Google Health update can
break it without warning.

## What it does

- **Per-app rules.** Each app you enable gets its own vibration for
  notifications and, if it can ring, a separate one for calls.
- **Calls ring until answered.** A repeating pulse that stops when the phone
  stops ringing — answered, declined, missed, or timed out.
- **Per-person rules.** Give a caller their own vibration, or block them.
  Works for phone calls and for WhatsApp calls, separately.
- **Patterns and strength, chosen separately.** One pulse, two pulses, ring, or
  silent, at either of the Air's two strengths, with a ring's pacing adjustable
  per app and per person.
- **Quiet hours, a pause, and a Stop button** in its own notification.

## How a pulse actually works

The Air exposes no "vibrate" command. It has a vibration-*strength* setting,
and it buzzes once when that setting changes. Everything here is built on that
one side effect.

A single pulse therefore costs a secure session: connect, handshake, write,
verify. A ring instead opens that session **once and holds it**, so pulses land
**97–175 ms** apart rather than seconds. Three consequences worth expecting:

- **A call's first buzz is 2.5–3.7 s late.** That is the handshake, and it
  cannot be removed. Pulses after it are fast.
- **Patterns are counts and cadence, not waveforms.** "Two pulses" is two
  settings writes, not a rhythm.
- **Ringing delays Google Health syncs**, because it holds the tracker channel.
  Calls are short; messages leave a cooldown between sessions.

## Two things this hardware cannot do

**The status light cannot be driven.** Tested, not assumed: it has no CoAP
resource (light-shaped paths answer `4.04`, while resources that exist behind
Google Health's credentials answer `4.01`), and the device exposes exactly two
settings within a one-byte selector — haptics, and one that does nothing
visible when toggled. Colour-coding alerts by LED is not possible.

**Wrist taps cannot be detected.** The Air handles them in firmware and sends
nothing to the phone; upstream measured zero traffic from double-taps,
including taps that dismissed a real Fitbit alarm. Tap-to-silence is
impossible, not unimplemented.

## Build

Needs the Flutter SDK and an Android SDK. The transport comes from a pinned
git dependency, so nothing is vendored here.

```bash
flutter build apk --release
```

The first build downloads an Android NDK (~2.3 GB), which `integration_test`
pins. Build release, not debug: a debug APK is ~1.4 GB of Vulkan validation
layers and unstripped engine libraries, against ~53 MB for release.

## Setting it up

1. Pair the Air in Google Health and leave it paired. This app does not own
   the pairing and cannot create one.
2. Grant **Nearby devices** while the app is on screen — the background
   service cannot request permissions.
3. Grant **notification access** when asked.
4. Allow it to **keep running in the background** when Rules offers. On this
   phone that matters more than anything in the code: without it, Android
   stops background work once the screen has been off a while.
5. Pick your apps under Rules, set each one's rows, and press Start.

Contacts are optional and asked for only when you open a caller list; they are
read to show names, and a contact's number is turned into the same hashed
identity a call arrives with.

## Limits worth knowing

- Android only. iOS does not let an app read other apps' notifications.
- Arm64 phones only; other ABIs are untested upstream.
- Background delivery is *evidenced*, not *guaranteed*: screen-off haptics are
  confirmed, deep idle and post-reboot are not.
- A pulse is verified by reading the setting back. "Completed" means the write
  verified, not that you felt it.
- One ring at a time, and messages stay silent during a call.

## Credit

Built on [ThoreKoritzius/fitbit-air](https://github.com/ThoreKoritzius/fitbit-air)
(MIT) — an independent reverse-engineering of the Air's Golden Gate / DTLS /
CoAP transport, and the reason any of this is possible. This app began as that
project's example relay and has diverged into a product; the transport itself
is used as a dependency, unmodified.

The held-session ring and the settings/resource probes developed here are
offered back upstream in
[#15](https://github.com/ThoreKoritzius/fitbit-air/pull/15) and
[#16](https://github.com/ThoreKoritzius/fitbit-air/pull/16). Until #15 lands,
the dependency points at a fork carrying it.

MIT, with Apache-2.0 for the bundled Golden Gate transport. See `LICENSE`,
`LICENSE-APACHE-2.0` and `THIRD_PARTY_NOTICES.md`.

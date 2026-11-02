# Contributing

Issues and focused pull requests are welcome. Please keep observed behavior,
decompiled-code conclusions, and hypotheses clearly separated.

On a development machine or GitHub-hosted CI, before opening a pull request, run:

```bash
./tool/check.sh
```

This runs Dart analysis/tests, an Android debug build, native JVM tests, and a
publication dry-run. Android builds/tests require a JDK; the script can use
Android Studio's bundled JDK at its standard macOS location. Publication
validation expects a clean Git state. Use synthetic protobuf fixtures, never
captured private device-info bodies, in decoder tests.

Device-facing changes should include the Android version, Air firmware version,
whether Google Health remained functional afterward, and sanitized logs. Never
commit account tokens, Bluetooth addresses, device identifiers, pairing keys,
APK-extracted libraries, or private app data.

For demo UX, operation ownership and agent-driven experimentation, see the
[demo guide](doc/demo.md). Keep stable keys/action IDs and structured result
schemas covered by tests. Haptic integration tests require an explicit flag;
never turn a protocol success into a synthetic physical-delivery observation.

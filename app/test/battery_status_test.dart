import 'dart:async';
import 'package:fitbit_air/fitbit_air.dart';
import 'package:fitbit_air_example/demo/air_status_strip.dart';
import 'package:fitbit_air_example/demo/demo_controller.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const device = MethodChannel('fitbit_air');
  late DemoController controller;
  var reads = 0;
  var starts = 0;
  var relayReads = 0;
  var signalReads = 0;
  var relayBusy = false;
  Map<Object?, Object?>? saved;
  Completer<Map<String, Object?>>? pending;
  var fail = false;
  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(
          const MethodChannel('fitbit_air/connection'),
          (call) async => null,
        );
    reads = 0;
    starts = 0;
    relayReads = 0;
    signalReads = 0;
    relayBusy = false;
    saved = null;
    pending = null;
    fail = false;
    controller = DemoController()
      ..initialized = true
      ..bluetoothPermission = true;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(device, (call) async {
          if (call.method == 'isAirPaired') return true;
          if (call.method == 'readBattery') {
            reads++;
            if (fail) throw PlatformException(code: 'TIMEOUT');
            return pending == null
                ? {'percent': 55, 'onCharger': true}
                : await pending!.future;
          }
          return null;
        });
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(DemoController.channel, (call) async {
          if (call.method == 'readRelaySignal') {
            signalReads++;
            return {'rssi': -67};
          }
          if (call.method == 'start') starts++;
          if (call.method == 'readRelayBattery') {
            relayReads++;
            if (relayBusy) throw PlatformException(code: 'BUSY');
            return {'percent': 66, 'onCharger': false};
          }
          if (call.method == 'saveBattery') saved = call.arguments as Map;
          if (call.method == 'batteryCache') return saved;
          if (call.method == 'apps') return [];
          return null;
        });
  });
  tearDown(() {
    controller.dispose();
  });

  test(
    'signal reads share the active worker and reuse a recent reading',
    () async {
      controller.relayRunning = true;
      controller.relayReady = true;
      await controller.refreshSignalStrength();
      await controller.refreshSignalStrength();
      expect(signalReads, 1);
      expect(reads, 0);
      expect(controller.signalRssi, -67);
      expect(controller.signalReadAt, isNotNull);
    },
  );

  testWidgets('an open app refreshes when its reading reaches an hour old', (
    tester,
  ) async {
    controller.setupCompleted = true;
    controller.battery = const AirBattery(percent: 40);
    controller.batteryReadAt = DateTime.now().subtract(
      const Duration(minutes: 5),
    );
    controller.setVisible(true);
    await tester.pump(const Duration(seconds: 3));
    expect(reads, 0);
    controller.batteryReadAt = DateTime.now().subtract(
      const Duration(hours: 1),
    );
    await controller.refresh();
    await tester.pump(const Duration(seconds: 3));
    await tester.pump();
    expect(reads, 1);
    controller.setVisible(false);
    await tester.pump();
  });

  for (final ageMinutes in [5, 20]) {
    testWidgets(
      'resume refreshes a $ageMinutes minute old reading only when needed',
      (tester) async {
        controller.setupCompleted = true;
        controller.battery = const AirBattery(percent: 40);
        controller.batteryReadAt = DateTime.now().subtract(
          Duration(minutes: ageMinutes),
        );
        controller.setVisible(true);
        await tester.pump(const Duration(seconds: 3));
        await tester.pump();
        expect(reads, ageMinutes >= 15 ? 1 : 0);
        controller.setVisible(false);
        await tester.pump();
      },
    );
  }

  testWidgets(
    'an older percentage stays visible while refreshing with an age tooltip',
    (tester) async {
      controller.battery = const AirBattery(percent: 55);
      controller.batteryReadAt = DateTime.now().subtract(
        const Duration(hours: 2),
      );
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: AnimatedBuilder(
              animation: controller,
              builder: (context, child) =>
                  AirStatusStrip(controller: controller),
            ),
          ),
        ),
      );
      expect(find.byKey(const ValueKey('battery-stale-clock')), findsOneWidget);
      expect(find.text('55%'), findsOneWidget);
      expect(
        find.byTooltip(
          'Fitbit Air battery · Checked 2 h ago · Reading is out of date',
        ),
        findsOneWidget,
      );
      pending = Completer();
      final read = controller.refreshBattery();
      await tester.pump();
      expect(find.text('55%'), findsOneWidget);
      expect(
        find.byKey(const ValueKey('battery-refresh-progress')),
        findsOneWidget,
      );
      pending!.complete({'percent': 50});
      await read;
      await tester.pumpAndSettle();
      expect(find.text('50%'), findsOneWidget);
      expect(find.byKey(const ValueKey('battery-stale-clock')), findsNothing);
      expect(
        find.byKey(const ValueKey('battery-refresh-progress')),
        findsNothing,
      );
    },
  );

  testWidgets(
    'foreground reads a missing battery without a passive connection report and reuses it on resume',
    (tester) async {
      controller.setupCompleted = true;
      controller.airConnected = null;
      controller.setVisible(true);
      await tester.pump(const Duration(seconds: 2));
      expect(reads, 0);
      await tester.pump(const Duration(seconds: 1));
      await tester.pump();
      expect(reads, 1);
      expect(controller.battery!.percent, 55);
      controller.setVisible(false);
      controller.airConnected = null;
      controller.setVisible(true);
      await tester.pump(const Duration(seconds: 10));
      expect(reads, 1);
      controller.setVisible(false);
      await tester.pump();
    },
  );

  testWidgets(
    'automatic read waits for relay shutdown and cancels when backgrounded',
    (tester) async {
      controller.setupCompleted = true;
      controller.airConnected = true;
      controller.relayRunning = true;
      controller.setVisible(true);
      await tester.pump(const Duration(seconds: 5));
      expect(reads, 0);
      controller.relayRunning = false;
      await controller.refresh();
      controller.setVisible(false);
      await tester.pump(const Duration(seconds: 5));
      expect(reads, 0);
      controller.airConnected = true;
      controller.setVisible(true);
      await tester.pump(const Duration(seconds: 3));
      await tester.pump();
      expect(reads, 1);
      controller.setVisible(false);
      await tester.pump();
    },
  );

  testWidgets(
    'failed automatic read backs off across status refreshes and resume',
    (tester) async {
      fail = true;
      controller.setupCompleted = true;
      controller.airConnected = true;
      controller.setVisible(true);
      await tester.pump(const Duration(seconds: 3));
      await tester.pump();
      expect(reads, 1);
      controller.setVisible(false);
      controller.airConnected = true;
      controller.setVisible(true);
      await tester.pump(const Duration(seconds: 10));
      await controller.refresh();
      await tester.pump(const Duration(seconds: 5));
      expect(reads, 1);
      expect(controller.batteryError, isNotNull);
      controller.setVisible(false);
      await tester.pump();
    },
  );

  testWidgets(
    'automatic battery uses the running relay worker without a UI engine read',
    (tester) async {
      controller.setupCompleted = true;
      controller.relayRunning = true;
      controller.relayReady = true;
      controller.airConnected = false;
      controller.setVisible(true);
      await tester.pump(const Duration(seconds: 3));
      await tester.pump();
      expect(relayReads, 1);
      expect(reads, 0);
      expect(controller.relayRunning, isTrue);
      expect(controller.battery!.percent, 66);
      controller.setVisible(false);
      await tester.pump();
    },
  );

  testWidgets('busy relay does not retry on every status poll', (tester) async {
    relayBusy = true;
    controller.setupCompleted = true;
    controller.relayRunning = true;
    controller.relayReady = true;
    controller.setVisible(true);
    await tester.pump(const Duration(seconds: 3));
    await tester.pump();
    expect(relayReads, 1);
    await tester.pump(const Duration(seconds: 10));
    expect(relayReads, 1);
    expect(reads, 0);
    expect(controller.batteryReading, isFalse);
    controller.setVisible(false);
    await tester.pump();
  });

  test(
    'passive connection subscription is shared across refreshes and released in background',
    () async {
      final events = <String>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
            const MethodChannel('fitbit_air/connection'),
            (call) async {
              events.add(call.method);
              return null;
            },
          );
      controller.setVisible(true);
      await controller.refresh();
      await controller.refresh();
      expect(events.where((e) => e == 'listen').length, 1);
      controller.setVisible(false);
      await Future<void>.delayed(Duration.zero);
      expect(events.where((e) => e == 'cancel').length, 1);
      expect(reads, 0);
    },
  );

  test(
    'persist readings with their original time and restore without reading Bluetooth',
    () async {
      await controller.refreshBattery();
      expect(saved?['percent'], 55);
      expect(saved?['onCharger'], true);
      final restored = DemoController();
      await restored.initialize();
      expect(restored.battery!.percent, 55);
      expect(
        restored.batteryReadAt!.millisecondsSinceEpoch,
        controller.batteryReadAt!.millisecondsSinceEpoch,
      );
      expect(reads, 1);
      await controller.refreshBattery();
      expect(reads, 1); // Fresh values cannot trigger another secure session.
      restored.dispose();
    },
  );

  test(
    'battery cannot compete with a relay, cleanup, preview or another request',
    () async {
      controller.relayRunning = true;
      await controller.refreshBattery();
      controller.relayRunning = false;
      controller.relayStopping = true;
      await controller.refreshBattery();
      controller.relayStopping = false;
      controller.previewing = true;
      await controller.refreshBattery();
      controller.previewing = false;
      expect(reads, 0);
      pending = Completer();
      final task = controller.refreshBattery();
      await controller.refreshBattery();
      final start = controller.startRelay();
      expect(controller.relayTransition, RelayTransition.starting);
      expect(starts, 0);
      expect(controller.controlsBlocked, isTrue);
      pending!.complete({'percent': 50});
      await task;
      await start;
      expect(starts, 1);
      expect(reads, 1);
      expect(controller.batteryReading, isFalse);
    },
  );

  test(
    'failure retains the reading and timestamp without automatic retries',
    () async {
      controller.battery = const AirBattery(percent: 20);
      final at = DateTime.now().subtract(const Duration(days: 1));
      controller.batteryReadAt = at;
      fail = true;
      await controller.refreshBattery();
      expect(controller.battery!.percent, 20);
      expect(controller.batteryReadAt, at);
      expect(controller.batteryError, isNotNull);
      expect(
        controller.report()['battery'],
        containsPair('errorCode', 'TIMEOUT'),
      );
      expect(reads, 1);
    },
  );

  testWidgets(
    'saved low battery opens a readable sheet with refresh disabled during warmup',
    (tester) async {
      controller.battery = const AirBattery(percent: 15, onCharger: true);
      controller.batteryReadAt = DateTime.now().subtract(
        const Duration(days: 1),
      );
      controller.relayRunning = true;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(body: AirStatusStrip(controller: controller)),
        ),
      );
      expect(find.text('15%'), findsOneWidget);
      expect(find.byKey(const ValueKey('battery-stale-clock')), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('battery-status')));
      await tester.pumpAndSettle();
      expect(find.text('Checked 1 d ago'), findsOneWidget);
      expect(find.text('On charger when checked'), findsOneWidget);
      expect(
        tester
            .widget<FilledButton>(find.byKey(const ValueKey('refresh-battery')))
            .onPressed,
        isNull,
      );
      expect(reads, 0);
    },
  );
}

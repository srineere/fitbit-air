import 'dart:async';
import 'package:air_bridge/demo/demo_controller.dart';
import 'package:air_bridge/demo/relay_settings.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const device = MethodChannel('fitbit_air');
  tearDown(() {
    messenger.setMockMethodCallHandler(DemoController.channel, null);
    messenger.setMockMethodCallHandler(device, null);
  });
  test(
    'typed settings preserve defaults overrides and silence without exporting private identities',
    () async {
      final controller = DemoController();
      addTearDown(controller.dispose);
      messenger.setMockMethodCallHandler(
        device,
        (_) async => <String, Object?>{},
      );
      messenger.setMockMethodCallHandler(
        DemoController.channel,
        (_) async => {
          'rules': {
            'defaultPreset': 'double',
            'apps': {
              'com.whatsapp': {'enabled': true, 'preset': 'low'},
            },
            'chats': [
              {
                'id': 'private-chat-identity',
                'label': 'Private label',
                'packageName': 'com.whatsapp',
                'kind': 'name',
                'preset': 'mute',
              },
            ],
            'everyoneElseSilent': true,
            'pauseUntil': -1,
            'silenceReason': 'paused',
            'quietEnabled': true,
            'startMinute': 1320,
            'endMinute': 420,
          },
        },
      );
      await controller.refresh();
      expect(controller.settings.defaultPreset, const VibrationPreset(VibrationPattern.two));
      expect(
        controller.settings.apps['com.whatsapp']?.preset,
        const VibrationPreset(VibrationPattern.one, VibrationStrength.low),
      );
      final chat = controller.settings.chats.single;
      expect(chat.preset, VibrationPreset.silent);
      expect(chat.nameMatch, isTrue);
      expect(controller.settings.everyoneElseSilent, isTrue);
      expect(controller.exportJson(), isNot(contains('Private label')));
      expect(controller.settings.silenceReason, 'paused');
      expect(controller.exportJson(), isNot(contains('private-chat-identity')));
      expect(
        controller.report()['settings'],
        containsPair('chatOverrideCount', 1),
      );
    },
  );
  test(
    'running worker preview waits for completion and leaves pause available',
    () async {
      final controller = DemoController();
      addTearDown(controller.dispose);
      controller.relayRunning = true;
      controller.relayReady = true;
      final delivery = Completer<void>();
      final started = Completer<void>();
      var paused = false;
      messenger.setMockMethodCallHandler(
        device,
        (_) async => <String, Object?>{},
      );
      messenger.setMockMethodCallHandler(DemoController.channel, (call) async {
        if (call.method == 'previewPreset') {
          expect(call.arguments, {'preset': 'one:3'});
          started.complete();
          await delivery.future;
        }
        if (call.method == 'setPause') paused = true;
        if (call.method == 'status') return {'running': true, 'ready': true};
        return null;
      });
      var completed = false;
      final future = controller
          .previewPreset(const VibrationPreset(VibrationPattern.one))
          .then((_) => completed = true);
      await started.future;
      expect(controller.previewing, isTrue);
      expect(controller.changingRelay, isFalse);
      expect(completed, isFalse);
      await controller.setPause(15);
      expect(paused, isTrue);
      await expectLater(
        controller.previewPreset(const VibrationPreset(VibrationPattern.one, VibrationStrength.low)),
        throwsStateError,
      );
      delivery.complete();
      await future;
      expect(completed, isTrue);
      expect(controller.previewing, isFalse);
    },
  );}

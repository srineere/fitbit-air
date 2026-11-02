import 'package:fitbit_air_example/demo/app_theme.dart';
import 'package:fitbit_air_example/demo/demo_controller.dart';
import 'package:fitbit_air_example/demo/relay_toggle_button.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('only enabled Start and Stop taps request phone haptics', (
    tester,
  ) async {
    final feedback = <Object?>[];
    var taps = 0;
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.platform,
      (call) async {
        if (call.method == 'HapticFeedback.vibrate') {
          feedback.add(call.arguments);
        }
        return null;
      },
    );
    addTearDown(
      () => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      ),
    );
    Future<void> render(bool running, RelayTransition? transition) =>
        tester.pumpWidget(
          MaterialApp(
            home: Scaffold(
              body: RelayToggleButton(
                running: running,
                transition: transition,
                onPressed: () => taps++,
              ),
            ),
          ),
        );
    await render(false, null);
    await tester.tap(find.byKey(const ValueKey('relay-toggle')));
    await tester.pump();
    await render(true, null);
    await tester.tap(find.byKey(const ValueKey('relay-toggle')));
    await tester.pump();
    expect(taps, 2);
    expect(feedback, [
      'HapticFeedbackType.selectionClick',
      'HapticFeedbackType.selectionClick',
    ]);
    await render(true, RelayTransition.stopping);
    await tester.tap(find.byKey(const ValueKey('relay-toggle')));
    await tester.pump(const Duration(milliseconds: 150));
    expect(feedback.length, 2);
    await tester.pumpWidget(const SizedBox());
  });

  testWidgets('fast operations never flash a busy indicator', (tester) async {
    Future<void> render(bool running, RelayTransition? transition) =>
        tester.pumpWidget(
          MaterialApp(
            theme: airRelayTheme(Brightness.light),
            home: Scaffold(
              body: Center(
                child: SizedBox(
                  width: 280,
                  child: RelayToggleButton(
                    running: running,
                    transition: transition,
                    onPressed: () {},
                  ),
                ),
              ),
            ),
          ),
        );
    await render(false, null);
    final rect = tester.getRect(find.byKey(const ValueKey('relay-toggle')));
    await render(false, RelayTransition.starting);
    await tester.pump(const Duration(milliseconds: 80));
    expect(find.byKey(const ValueKey('relay-toggle-progress')), findsNothing);
    await render(true, null);
    await tester.pumpAndSettle();
    expect(find.text('Starting…'), findsNothing);
    expect(find.byKey(const ValueKey('relay-toggle-progress')), findsNothing);
    expect(tester.getRect(find.byKey(const ValueKey('relay-toggle'))), rect);
  });

  testWidgets(
    'large text and reduced motion retain geometry and disable repeat taps',
    (tester) async {
      var taps = 0;
      Future<void> render(RelayTransition? transition) => tester.pumpWidget(
        MaterialApp(
          theme: airRelayTheme(Brightness.dark),
          builder: (context, child) => MediaQuery(
            data: MediaQuery.of(context).copyWith(
              disableAnimations: true,
              textScaler: TextScaler.linear(1.6),
            ),
            child: child!,
          ),
          home: Scaffold(
            body: Center(
              child: SizedBox(
                width: 272,
                child: RelayToggleButton(
                  running: false,
                  transition: transition,
                  onPressed: () => taps++,
                ),
              ),
            ),
          ),
        ),
      );
      await render(null);
      final button = find.byKey(const ValueKey('relay-toggle'));
      final rect = tester.getRect(button);
      await render(RelayTransition.starting);
      await tester.pump(const Duration(milliseconds: 180));
      expect(find.byIcon(Icons.hourglass_top), findsOneWidget);
      expect(find.byType(CircularProgressIndicator), findsNothing);
      expect(
        tester.widget<FilledButton>(button).style!.animationDuration,
        Duration.zero,
      );
      expect(tester.getRect(button), rect);
      await tester.tap(button);
      expect(taps, 0);
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(const SizedBox());
    },
  );

  testWidgets('leaving the screen cancels deferred progress', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: RelayToggleButton(
          running: false,
          transition: RelayTransition.starting,
          onPressed: () {},
        ),
      ),
    );
    await tester.pump(const Duration(milliseconds: 40));
    await tester.pumpWidget(const SizedBox());
    await tester.pump(const Duration(seconds: 1));
    expect(tester.takeException(), isNull);
  });
}

import 'package:fitbit_air/fitbit_air.dart';

import 'demo_controller.dart';

enum RelayRecovery { setup, bluetooth, notifications, rules, update, none }

/// Describes observed readiness, never an assumed live connection to the band.
class RelayStatus {
  const RelayStatus(
    this.title,
    this.detail, {
    this.recovery = RelayRecovery.none,
    this.attention = false,
  });
  final String title;
  final String detail;
  final RelayRecovery recovery;

  /// Worth the user's attention although there is nothing to fix in setup.
  final bool attention;

  factory RelayStatus.fromController(DemoController state) {
    if (!state.initialized) {
      return const RelayStatus(
        'Checking setup',
        'Checking permissions and notification settings.',
      );
    }
    if (state.relayStopping) {
      return const RelayStatus(
        'Finishing up',
        'Letting the current vibration finish and releasing the Air.',
      );
    }
    if (!state.bluetoothPermission) {
      return const RelayStatus(
        'Allow nearby devices',
        'Air Relay needs Bluetooth permission to reach your band.',
        recovery: RelayRecovery.setup,
      );
    }
    if (state.bluetoothEnabled == false) {
      return const RelayStatus(
        'Bluetooth is off',
        'Turn it on to send notifications to your wrist.',
        recovery: RelayRecovery.bluetooth,
      );
    }
    if (!state.access) {
      return const RelayStatus(
        'Allow notification access',
        'Choose which notifications can become a vibration.',
        recovery: RelayRecovery.notifications,
      );
    }
    if (!state.listenerConnected) {
      return const RelayStatus(
        'Notification access disconnected',
        'Android isn’t sending notifications to Air Relay yet. Check notification access, then return here.',
        recovery: RelayRecovery.notifications,
      );
    }
    if (state.bluetoothEnabled == null) {
      return const RelayStatus(
        'Check your Air',
        'Check Bluetooth and pairing to finish getting connected.',
        recovery: RelayRecovery.setup,
      );
    }
    if (state.paired == false) {
      return const RelayStatus(
        'Pair your Air first',
        'Pair it in Google Health, then return to check again.',
        recovery: RelayRecovery.setup,
      );
    }
    if (state.selectedApps.isEmpty ||
        (state.apps.isNotEmpty && state.selectedInstalledAppCount == 0)) {
      return const RelayStatus(
        'Choose your apps',
        'Select at least one app to receive its notifications.',
        recovery: RelayRecovery.rules,
      );
    }
    if (!state.relayRunning && !state.setupCompleted && state.paired == null) {
      return const RelayStatus(
        'Finish setting up',
        'Check your paired Air before starting notifications.',
        recovery: RelayRecovery.setup,
      );
    }
    if (!state.relayRunning) {
      return const RelayStatus(
        'Ready when you are',
        'Start notifications when you want alerts on your wrist.',
      );
    }
    if (state.settings.silenceReason == 'paused') {
      return const RelayStatus(
        'Taking a break',
        'Notifications are paused. Missed alerts will not be replayed.',
      );
    }
    if (state.settings.silenceReason == 'quiet_hours') {
      return const RelayStatus(
        'Quiet hours',
        'Your schedule is keeping notifications quiet.',
      );
    }
    if (!state.relayReady) {
      return const RelayStatus(
        'Starting notifications',
        'Getting ready to listen for your alerts.',
      );
    }
    if (state.airConnected == false) {
      return const RelayStatus(
        'Air not connected',
        'Alerts can’t reach your wrist until the Air is back near this phone. Missed alerts are not replayed.',
        attention: true,
      );
    }
    switch (state.deliveryProblem) {
      case AirFailureKind.compatibility:
        return const RelayStatus(
          'Alerts aren’t reaching your Air',
          'Your Air keeps answering in an unexpected way, possibly after a firmware update. An Air Relay update may be needed.',
          recovery: RelayRecovery.update,
          attention: true,
        );
      case AirFailureKind.setup:
        return RelayStatus(
          'Alerts aren’t reaching your Air',
          DemoController.recoveryHint(state.lastFailureCode ?? ''),
          recovery: RelayRecovery.setup,
          attention: true,
        );
      case AirFailureKind.temporary || AirFailureKind.unknown:
        return const RelayStatus(
          'Recent alerts weren’t confirmed',
          'Keep the Air near your phone. Air Relay tries again with the next alert.',
          attention: true,
        );
      case null:
        break;
    }
    return const RelayStatus(
      'Ready for notifications',
      'Listening for your chosen apps. The Air connects only when an alert is sent.',
    );
  }
}

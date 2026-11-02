import 'package:fitbit_air/fitbit_air.dart';

enum VibrationPreset {
  single('single', 'One pulse', 'Uses your current strength'),
  low('low', 'One pulse · Low', 'The band keeps low strength afterward'),
  high('high', 'One pulse · High', 'The band keeps high strength afterward'),
  doublePulse('double', 'Two pulses', 'Restores your original strength'),
  ring(
    'ring',
    'Repeat until answered',
    'One pulse every few seconds while the call rings',
    callsOnly: true,
  ),
  mute('mute', 'Silent', 'No vibration for this chat');

  const VibrationPreset(
    this.id,
    this.label,
    this.description, {
    this.callsOnly = false,
  });
  final String id;
  final String label;
  final String description;

  /// A repeat has nothing to stop it unless a call is still ringing.
  final bool callsOnly;
  HapticIntensity? get intensity => switch (this) {
    low => HapticIntensity.low,
    high => HapticIntensity.high,
    _ => null,
  };
  static VibrationPreset parse(Object? value) =>
      values.firstWhere((item) => item.id == value, orElse: () => single);
}

class AppNotificationRule {
  const AppNotificationRule({
    required this.enabled,
    this.preset,
    this.callPreset,
  });
  final bool enabled;
  final VibrationPreset? preset;

  /// Null uses [RelaySettings.defaultCallPreset] for this app's calls.
  final VibrationPreset? callPreset;
}

/// A WhatsApp chat saved on this phone. [preset] null uses the app vibration.
class SavedChat {
  const SavedChat({
    required this.id,
    required this.label,
    required this.packageName,
    this.nameMatch = false,
    this.preset,
  });
  final String id;
  final String label;
  final String packageName;

  /// Matched by exact chat name because WhatsApp gave no conversation ID.
  final bool nameMatch;
  final VibrationPreset? preset;
  bool get business => packageName == 'com.whatsapp.w4b';

  factory SavedChat.fromMap(Map map) => SavedChat(
    id: map['id'] as String,
    label: map['label'] as String? ?? 'Chat',
    packageName: map['packageName'] as String? ?? 'com.whatsapp',
    nameMatch: map['kind'] == 'name',
    preset: map['preset'] == null ? null : VibrationPreset.parse(map['preset']),
  );
}

class RelaySettings {
  const RelaySettings({
    this.defaultPreset = VibrationPreset.single,
    this.defaultCallPreset = VibrationPreset.ring,
    this.ringIntervalSeconds = 6,
    this.ringMaxSeconds = 45,
    this.apps = const {},
    this.chats = const [],
    this.everyoneElseSilent = false,
    this.pauseUntil = 0,
    this.quietEnabled = false,
    this.startMinute = 1320,
    this.endMinute = 420,
    this.silenceReason,
    this.toneEnabled = false,
    this.toneUrgent,
    this.toneNegative,
    this.toneNeutral,
  });
  final VibrationPreset defaultPreset;

  /// Used for calls from apps without their own call vibration.
  final VibrationPreset defaultCallPreset;

  /// Seconds between pulses while a call rings, and when the ring gives up.
  final int ringIntervalSeconds;
  final int ringMaxSeconds;
  final Map<String, AppNotificationRule> apps;
  final List<SavedChat> chats;

  /// Only saved WhatsApp chats may vibrate.
  final bool everyoneElseSilent;
  final int pauseUntil;
  final bool quietEnabled;
  final int startMinute;
  final int endMinute;
  final String? silenceReason;

  /// Message tone presets; null keeps the app or chat vibration.
  final bool toneEnabled;
  final VibrationPreset? toneUrgent;
  final VibrationPreset? toneNegative;
  final VibrationPreset? toneNeutral;

  factory RelaySettings.fromMap(Map map) {
    final appMap = map['apps'] as Map? ?? {};
    VibrationPreset? optional(Object? value) =>
        value == null ? null : VibrationPreset.parse(value);
    return RelaySettings(
      defaultPreset: VibrationPreset.parse(map['defaultPreset']),
      defaultCallPreset: map['defaultCallPreset'] == null
          ? VibrationPreset.ring
          : VibrationPreset.parse(map['defaultCallPreset']),
      ringIntervalSeconds: map['ringIntervalSeconds'] as int? ?? 6,
      ringMaxSeconds: map['ringMaxSeconds'] as int? ?? 45,
      apps: appMap.map((key, value) {
        final rule = value as Map;
        return MapEntry(
          key as String,
          AppNotificationRule(
            enabled: rule['enabled'] == true,
            preset: rule['preset'] == null
                ? null
                : VibrationPreset.parse(rule['preset']),
            callPreset: rule['callPreset'] == null
                ? null
                : VibrationPreset.parse(rule['callPreset']),
          ),
        );
      }),
      chats: [
        for (final chat in map['chats'] as List? ?? const [])
          SavedChat.fromMap(chat as Map),
      ],
      everyoneElseSilent: map['everyoneElseSilent'] == true,
      pauseUntil: map['pauseUntil'] as int? ?? 0,
      quietEnabled: map['quietEnabled'] == true,
      startMinute: map['startMinute'] as int? ?? 1320,
      endMinute: map['endMinute'] as int? ?? 420,
      silenceReason: map['silenceReason'] as String?,
      toneEnabled: map['toneEnabled'] == true,
      toneUrgent: optional(map['toneUrgent']),
      toneNegative: optional(map['toneNegative']),
      toneNeutral: optional(map['toneNeutral']),
    );
  }
}

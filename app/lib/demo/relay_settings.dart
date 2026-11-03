import 'package:fitbit_air/fitbit_air.dart';

/// What a vibration does, independent of how hard it does it.
enum VibrationPattern {
  one('one', 'One pulse', 1),
  two('two', 'Two pulses', 2),
  ring('ring', 'Ring until answered', null, callsOnly: true),
  silent('silent', 'Silent', 0);

  const VibrationPattern(
    this.id,
    this.label,
    this.pulses, {
    this.callsOnly = false,
  });
  final String id;
  final String label;

  /// How many pulses it plays; null repeats until the phone stops ringing.
  final int? pulses;

  /// A repeat has nothing to stop it unless a call is still ringing.
  final bool callsOnly;

  static VibrationPattern parse(String id) =>
      values.firstWhere((item) => item.id == id, orElse: () => one);
}

/// The Air has exactly two strengths, and every pattern can use either.
enum VibrationStrength {
  low(2, 'Light'),
  full(3, 'Strong');

  const VibrationStrength(this.wire, this.label);
  final int wire;
  final String label;

  static VibrationStrength parse(int? wire) =>
      wire == low.wire ? low : full;
}

/// A pattern and the strength it plays at, stored as `pattern:strength`.
class VibrationPreset {
  const VibrationPreset(this.pattern, [this.strength = VibrationStrength.full]);

  final VibrationPattern pattern;
  final VibrationStrength strength;

  static const silent = VibrationPreset(VibrationPattern.silent);

  bool get isSilent => pattern == VibrationPattern.silent;
  bool get callsOnly => pattern.callsOnly;

  String get id =>
      isSilent ? VibrationPattern.silent.id : '${pattern.id}:${strength.wire}';

  /// What to show on a row: the strength only matters when something plays.
  String get label => isSilent ? pattern.label : '${pattern.label} · ${strength.label}';

  HapticIntensity? get intensity =>
      isSilent ? null : HapticIntensity.fromWireValue(strength.wire);

  /// Accepts ids written before pattern and strength were chosen separately.
  static VibrationPreset parse(Object? value) {
    final id = value is String ? value : '';
    const legacy = {
      'single': VibrationPreset(VibrationPattern.one),
      'low': VibrationPreset(VibrationPattern.one, VibrationStrength.low),
      'high': VibrationPreset(VibrationPattern.one),
      'double': VibrationPreset(VibrationPattern.two),
      'ring': VibrationPreset(VibrationPattern.ring),
      'mute': silent,
    };
    final known = legacy[id];
    if (known != null) return known;
    final parts = id.split(':');
    final pattern = VibrationPattern.parse(parts.first);
    if (pattern == VibrationPattern.silent) return silent;
    return VibrationPreset(
      pattern,
      VibrationStrength.parse(
        parts.length > 1 ? int.tryParse(parts[1]) : null,
      ),
    );
  }

  @override
  bool operator ==(Object other) =>
      other is VibrationPreset &&
      other.pattern == pattern &&
      (isSilent || other.strength == strength);

  @override
  int get hashCode => Object.hash(pattern, isSilent ? 0 : strength);
}

class AppNotificationRule {
  const AppNotificationRule({
    required this.enabled,
    this.preset,
    this.callPreset,
    this.ringGapMillis,
    this.ringMaxSeconds,
  });
  final bool enabled;
  final VibrationPreset? preset;

  /// Null uses [RelaySettings.defaultCallPreset] for this app's calls.
  final VibrationPreset? callPreset;

  /// Its own ring cadence; null uses the shared one.
  final int? ringGapMillis;
  final int? ringMaxSeconds;
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

/// A caller the user saved, with the vibration their calls get.
class SavedCaller {
  const SavedCaller({
    required this.id,
    required this.label,
    required this.packageName,
    this.detail,
    this.preset,
    this.ringGapMillis,
    this.ringMaxSeconds,
  });
  final String id;
  final String label;
  final String packageName;

  /// The number this rule is for; one contact often has several.
  final String? detail;

  /// Null uses the app's call vibration; silent mutes this caller alone.
  final VibrationPreset? preset;

  /// Its own ring cadence; null uses the app's, then the shared one.
  final int? ringGapMillis;
  final int? ringMaxSeconds;

  factory SavedCaller.fromMap(Map map) => SavedCaller(
    id: map['id'] as String,
    label: map['label'] as String? ?? 'Caller',
    packageName: map['packageName'] as String? ?? 'com.google.android.dialer',
    detail: (map['detail'] as String?)?.trim().isNotEmpty == true
        ? (map['detail'] as String).trim()
        : null,
    preset: map['preset'] == null ? null : VibrationPreset.parse(map['preset']),
    ringGapMillis: map['ringGapMillis'] as int?,
    ringMaxSeconds: map['ringMaxSeconds'] as int?,
  );
}

class RelaySettings {
  const RelaySettings({
    this.defaultPreset = const VibrationPreset(VibrationPattern.one),
    this.defaultCallPreset = const VibrationPreset(VibrationPattern.ring),
    this.ringGapMillis = 700,
    this.ringMaxSeconds = 45,
    this.apps = const {},
    this.chats = const [],
    this.callers = const [],
    this.recentCallers = const [],
    this.onlySavedCallers = false,
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

  /// Milliseconds between pulses while a call rings, and when it gives up.
  final int ringGapMillis;
  final int ringMaxSeconds;
  final Map<String, AppNotificationRule> apps;
  final List<SavedChat> chats;

  /// Callers with a rule of their own, and callers seen since the relay
  /// started that have no rule yet.
  final List<SavedCaller> callers;
  final List<SavedCaller> recentCallers;

  /// Only saved callers ring; calls from anyone else stay silent.
  final bool onlySavedCallers;

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
          ? const VibrationPreset(VibrationPattern.ring)
          : VibrationPreset.parse(map['defaultCallPreset']),
      ringGapMillis: map['ringGapMillis'] as int? ?? 700,
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
            ringGapMillis: rule['ringGapMillis'] as int?,
            ringMaxSeconds: rule['ringMaxSeconds'] as int?,
          ),
        );
      }),
      chats: [
        for (final chat in map['chats'] as List? ?? const [])
          SavedChat.fromMap(chat as Map),
      ],
      callers: [
        for (final caller in map['callers'] as List? ?? const [])
          SavedCaller.fromMap(caller as Map),
      ],
      recentCallers: [
        for (final caller in map['recentCallers'] as List? ?? const [])
          SavedCaller.fromMap(caller as Map),
      ],
      onlySavedCallers: map['onlySavedCallers'] == true,
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

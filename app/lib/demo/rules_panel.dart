import 'package:flutter/material.dart';
import 'demo_controller.dart';
import 'app_icon.dart';
import 'conversation_picker.dart';
import 'relay_settings.dart';
import 'vibration_editor.dart';

class RulesPanel extends StatefulWidget {
  const RulesPanel({
    super.key,
    required this.controller,
    required this.perform,
    this.scrollController,
  });
  final DemoController controller;
  final ScrollController? scrollController;
  final void Function(Future<void> Function()) perform;
  @override
  State<RulesPanel> createState() => _RulesPanelState();
}

class _RulesPanelState extends State<RulesPanel> {
  Set<String>? _selectedAtSort;

  @override
  void didUpdateWidget(covariant RulesPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      _selectedAtSort = null;
    }
  }

  @override
  Widget build(BuildContext context) {
    final state = widget.controller;
    // Keep rows under the user's finger until they explicitly regroup or leave.
    if (state.apps.isNotEmpty) {
      _selectedAtSort ??= Set.of(state.selectedApps);
    }
    final selectedAtSort = _selectedAtSort ?? <String>{};
    final apps = state.filteredApps.toList()
      ..sort((a, b) {
        final selected =
            (selectedAtSort.contains(b['packageName']) ? 1 : 0) -
            (selectedAtSort.contains(a['packageName']) ? 1 : 0);
        if (selected != 0) return selected;
        final label = (a['label'] as String).toLowerCase().compareTo(
          (b['label'] as String).toLowerCase(),
        );
        return label != 0
            ? label
            : (a['packageName'] as String).compareTo(
                b['packageName'] as String,
              );
      });
    return ListView(
      key: const ValueKey('rules-scroll'),
      controller: widget.scrollController,
      padding: const EdgeInsets.all(20),
      children: [
        Text(
          'Rules',
          style: Theme.of(
            context,
          ).textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.w600),
        ),
        const SizedBox(height: 20),
        if (!state.batteryExempt)
          Card(
            key: const ValueKey('battery-exemption'),
            child: ListTile(
              leading: const Icon(Icons.battery_alert),
              title: const Text('Let the relay keep running'),
              subtitle: const Text(
                'Android battery optimisation can stop the relay once the '
                'screen has been off for a while. Allow it to run in the '
                'background to keep calls reaching your Air.',
              ),
              isThreeLine: true,
              trailing: const Icon(Icons.chevron_right),
              onTap: () => widget.perform(state.requestBatteryExemption),
            ),
          ),
        if (!state.batteryExempt) const SizedBox(height: 12),
        ConversationPicker(controller: state, perform: widget.perform),
        const SizedBox(height: 12),
        ListTile(
          contentPadding: EdgeInsets.zero,
          key: const ValueKey('default-vibration'),
          leading: const Icon(Icons.vibration),
          title: const Text('Default vibration'),
          subtitle: Text(state.settings.defaultPreset.label),
          trailing: const Icon(Icons.chevron_right),
          onTap: () => editVibration(
            context,
            state,
            title: 'Default vibration',
            initial: state.settings.defaultPreset,
            onSave: (preset) => state.saveDefaultPreset(preset!),
          ),
        ),
        const SizedBox(height: 12),
        ListTile(
          contentPadding: EdgeInsets.zero,
          key: const ValueKey('default-call-vibration'),
          leading: const Icon(Icons.call),
          title: const Text('Default call vibration'),
          subtitle: Text(state.settings.defaultCallPreset.label),
          trailing: const Icon(Icons.chevron_right),
          onTap: () => editVibration(
            context,
            state,
            title: 'Default call vibration',
            initial: state.settings.defaultCallPreset,
            allowMute: true,
            allowRing: true,
            onSave: (preset) => state.saveDefaultCallPreset(preset!),
          ),
        ),
        if (_repeatsAnyCall(state.settings)) ...[
          const SizedBox(height: 12),
          ListTile(
            contentPadding: EdgeInsets.zero,
            key: const ValueKey('ring-timing'),
            leading: const Icon(Icons.timer_outlined),
            title: const Text('Repeat timing'),
            subtitle: Text(
              'A pulse every ${state.settings.ringGapMillis} ms · '
              'stops after ${state.settings.ringMaxSeconds} s',
            ),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => editRingTiming(context, state, widget.perform),
          ),
        ],
        const SizedBox(height: 12),
        ..._callerRules(context, state, widget.perform),
        const SizedBox(height: 12),
        ListTile(
          contentPadding: EdgeInsets.zero,
          key: const ValueKey('quiet-hours'),
          leading: const Icon(Icons.bedtime_outlined),
          title: const Text('Quiet hours'),
          subtitle: Text(
            state.settings.quietEnabled
                ? '${formatMinute(context, state.settings.startMinute)} to ${formatMinute(context, state.settings.endMinute)} · Every day'
                : 'Off · Set a daily time to stay quiet',
          ),
          trailing: const Icon(Icons.chevron_right),
          onTap: () => editQuietHours(context, state),
        ),
        const SizedBox(height: 12),
        ..._toneRules(context, state, widget.perform),
        const SizedBox(height: 24),
        Wrap(
          spacing: 12,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            Text('Your apps', style: Theme.of(context).textTheme.titleLarge),
            TextButton.icon(
              key: const ValueKey('sort-selected-apps'),
              onPressed: () =>
                  setState(() => _selectedAtSort = Set.of(state.selectedApps)),
              icon: const Icon(Icons.vertical_align_top, size: 18),
              label: const Text('Selected first'),
            ),
          ],
        ),
        const SizedBox(height: 12),
        TextFormField(
          key: const ValueKey('app-search'),
          initialValue: state.appSearch,
          decoration: const InputDecoration(
            labelText: 'Search apps',
            prefixIcon: Icon(Icons.search),
          ),
          onChanged: state.searchApps,
        ),
        const SizedBox(height: 12),
        if (state.filteredApps.isEmpty)
          const Padding(
            padding: EdgeInsets.all(12),
            child: Text(
              'No matching apps. Try another search or refresh the list.',
            ),
          ),
        for (final app in apps)
          Builder(
            builder: (context) {
              final pkg = app['packageName']! as String;
              final label = app['label']! as String;
              final rule = state.settings.apps[pkg];
              final enabled = state.selectedApps.contains(pkg);
              final preset = rule?.preset;
              final callPreset = rule?.callPreset;
              final icon = app['icon'] as String?;
              return Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  // The toggle decides whether this app is in play at all;
                  // what each kind of alert feels like lives in its own row,
                  // so neither is hidden behind tapping the other.
                  ListTile(
                    key: ValueKey('app-$pkg'),
                    contentPadding: EdgeInsets.zero,
                    leading: AppIcon(encoded: icon),
                    title: Text(label),
                    trailing: Switch(
                      key: ValueKey('app-enabled-$pkg'),
                      value: enabled,
                      onChanged: state.changingRelay
                          ? null
                          : (value) => widget.perform(
                              () => state.saveAppRule(
                                pkg,
                                value,
                                preset,
                                callPreset,
                              ),
                            ),
                    ),
                    onTap: state.changingRelay
                        ? null
                        : () => widget.perform(
                            () => state.saveAppRule(
                              pkg,
                              !enabled,
                              preset,
                              callPreset,
                            ),
                          ),
                  ),
                  if (enabled) ...[
                    _alertRow(
                      key: ValueKey('app-notifications-$pkg'),
                      icon: Icons.chat_bubble_outline,
                      title: 'Notifications',
                      value:
                          preset?.label ??
                          'Default: ${state.settings.defaultPreset.label}',
                      onTap: () => editVibration(
                        context,
                        state,
                        title: '$label notifications',
                        initial: preset,
                        // Silent here is what makes "calls only" possible:
                        // the app stays on, so its calls still ring.
                        allowMute: true,
                        onSave: (value) =>
                            state.saveAppRule(pkg, enabled, value, callPreset),
                      ),
                    ),
                    _alertRow(
                      key: ValueKey('app-calls-$pkg'),
                      icon: Icons.call_outlined,
                      title: 'Calls',
                      value:
                          callPreset?.label ??
                          'Default: ${state.settings.defaultCallPreset.label}',
                      onTap: () => editVibration(
                        context,
                        state,
                        title: '$label calls',
                        initial: callPreset,
                        allowMute: true,
                        allowRing: true,
                        inheritedPreset: state.settings.defaultCallPreset,
                        onSave: (value) =>
                            state.saveAppRule(pkg, enabled, preset, value),
                      ),
                    ),
                  ],
                ],
              );
            },
          ),
        TextButton(
          key: const ValueKey('refresh-apps'),
          onPressed: () => widget.perform(state.loadApps),
          child: const Text('Refresh app list'),
        ),
        const SizedBox(height: 16),
        const ExpansionTile(
          tilePadding: EdgeInsets.zero,
          title: Text('How alerts work'),
          children: [
            Padding(
              padding: EdgeInsets.only(bottom: 16),
              child: Text(
                'Nearby alerts can share one vibration to leave time for Google Health to sync. A saved person’s vibration takes priority, unless message tone marks another alert as urgent.',
              ),
            ),
          ],
        ),
      ],
    );
  }
}

List<Widget> _toneRules(
  BuildContext context,
  DemoController state,
  void Function(Future<void> Function()) perform,
) {
  final s = state.settings;
  Future<void> save({
    bool? enabled,
    VibrationPreset? Function()? urgent,
    VibrationPreset? Function()? negative,
    VibrationPreset? Function()? neutral,
  }) => state.saveToneRules(
    enabled: enabled ?? s.toneEnabled,
    urgent: urgent != null ? urgent() : s.toneUrgent,
    negative: negative != null ? negative() : s.toneNegative,
    neutral: neutral != null ? neutral() : s.toneNeutral,
  );
  Widget row(
    String key,
    String title,
    VibrationPreset? preset,
    Future<void> Function(VibrationPreset?) onSave,
  ) => ListTile(
    key: ValueKey('tone-$key'),
    contentPadding: const EdgeInsets.only(left: 40),
    title: Text(title),
    subtitle: Text(preset?.label ?? 'Keep app or chat vibration'),
    trailing: const Icon(Icons.chevron_right),
    onTap: () => editVibration(
      context,
      state,
      title: title,
      initial: preset,
      allowMute: true,
      onSave: onSave,
    ),
  );
  final fresh =
      s.toneUrgent == null && s.toneNegative == null && s.toneNeutral == null;
  return [
    SwitchListTile(
      key: const ValueKey('message-tone'),
      contentPadding: EdgeInsets.zero,
      secondary: const Icon(Icons.priority_high),
      title: Wrap(
        spacing: 8,
        runSpacing: 4,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [const Text('Message tone'), _experimentalLabel(context)],
      ),
      subtitle: const Text(
        'Reads message text on this phone to vary the vibration for urgent or negative messages. Text is never stored or shared.',
      ),
      value: s.toneEnabled,
      onChanged: state.changingRelay
          ? null
          : (value) async {
              if (value && !await _confirmMessageTone(context)) return;
              perform(
                () => state.saveToneRules(
                  enabled: value,
                  // Suggest a starting point the first time it is switched on.
                  urgent: value && fresh
                      ? const VibrationPreset(VibrationPattern.two)
                      : s.toneUrgent,
                  negative: value && fresh
                      ? const VibrationPreset(VibrationPattern.one)
                      : s.toneNegative,
                  neutral: s.toneNeutral,
                ),
              );
            },
    ),
    if (s.toneEnabled) ...[
      row(
        'urgent',
        'Urgent',
        s.toneUrgent,
        (value) => save(urgent: () => value),
      ),
      row(
        'negative',
        'Negative',
        s.toneNegative,
        (value) => save(negative: () => value),
      ),
      row(
        'neutral',
        'Everything else',
        s.toneNeutral,
        (value) => save(neutral: () => value),
      ),
    ],
  ];
}

Widget _experimentalLabel(BuildContext context) {
  final colors = Theme.of(context).colorScheme;
  return Container(
    key: const ValueKey('message-tone-experimental'),
    padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
    decoration: BoxDecoration(
      border: Border.all(color: colors.outline),
      borderRadius: BorderRadius.circular(6),
    ),
    child: Text(
      'Experimental',
      style: Theme.of(
        context,
      ).textTheme.labelSmall?.copyWith(color: colors.onSurfaceVariant),
    ),
  );
}

/// Explicit consent each time message text reading is switched on.
Future<bool> _confirmMessageTone(BuildContext context) async =>
    await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        key: const ValueKey('message-tone-consent'),
        title: const Text('Read message text?'),
        content: const Text(
          'Air Relay will read the text of notifications from your chosen apps to pick a vibration for urgent or negative messages.\n\n'
          'The text is checked on this phone, in memory, and is never saved or sent; only the category (urgent, negative or neutral) appears in your history. '
          'It’s experimental and will sometimes misjudge a message. You can turn it off anytime.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            key: const ValueKey('message-tone-confirm'),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Turn on'),
          ),
        ],
      ),
    ) ??
    false;

String formatMinute(BuildContext context, int minute) =>
    TimeOfDay(hour: minute ~/ 60, minute: minute % 60).format(context);
Future<void> editQuietHours(
  BuildContext context,
  DemoController controller,
) async {
  var enabled = controller.settings.quietEnabled;
  var start = controller.settings.startMinute;
  var end = controller.settings.endMinute;
  var saving = false;
  String? error;
  await showDialog<void>(
    context: context,
    builder: (context) => StatefulBuilder(
      builder: (context, update) => AlertDialog(
        title: const Text('Quiet hours'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              SwitchListTile(
                key: const ValueKey('quiet-enabled'),
                title: const Text('Every day'),
                value: enabled,
                onChanged: saving ? null : (v) => update(() => enabled = v),
              ),
              for (final isStart in [true, false])
                ListTile(
                  title: Text(isStart ? 'From' : 'Until'),
                  trailing: Text(formatMinute(context, isStart ? start : end)),
                  onTap: saving
                      ? null
                      : () async {
                          final value = isStart ? start : end;
                          final time = await showTimePicker(
                            context: context,
                            initialTime: TimeOfDay(
                              hour: value ~/ 60,
                              minute: value % 60,
                            ),
                          );
                          if (time != null && context.mounted) {
                            update(() {
                              if (isStart) {
                                start = time.hour * 60 + time.minute;
                              } else {
                                end = time.hour * 60 + time.minute;
                              }
                            });
                          }
                        },
                ),
              const Text(
                'Uses your phone’s local time. Overnight schedules are supported. Missed notifications are not replayed.',
              ),
              if (error != null) Text(error!),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: saving ? null : () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          FilledButton(
            key: const ValueKey('save-quiet-hours'),
            onPressed: saving
                ? null
                : () async {
                    if (enabled && start == end) {
                      update(
                        () => error = 'Choose different start and end times.',
                      );
                      return;
                    }
                    update(() {
                      saving = true;
                      error = null;
                    });
                    try {
                      await controller.saveQuietHours(enabled, start, end);
                      if (context.mounted) Navigator.pop(context);
                    } catch (_) {
                      if (context.mounted) {
                        update(() {
                          saving = false;
                          error = 'Could not save. Try again.';
                        });
                      }
                    }
                  },
            child: const Text('Save'),
          ),
        ],
      ),
    ),
  );
}

/// Repeat timing only matters when something is actually set to repeat.
bool _repeatsAnyCall(RelaySettings settings) =>
    settings.defaultCallPreset.pattern == VibrationPattern.ring ||
    settings.apps.values.any(
      (rule) => rule.enabled && rule.callPreset?.pattern == VibrationPattern.ring,
    );

Future<void> editRingTiming(
  BuildContext context,
  DemoController state,
  void Function(Future<void> Function()) perform,
) => showModalBottomSheet<void>(
  context: context,
  isScrollControlled: true,
  useSafeArea: true,
  builder: (context) => _RingTimingEditor(state: state, perform: perform),
);

class _RingTimingEditor extends StatefulWidget {
  const _RingTimingEditor({required this.state, required this.perform});
  final DemoController state;
  final void Function(Future<void> Function()) perform;
  @override
  State<_RingTimingEditor> createState() => _RingTimingEditorState();
}

class _RingTimingEditorState extends State<_RingTimingEditor> {
  late double gap = widget.state.settings.ringGapMillis.toDouble();
  late double maximum = widget.state.settings.ringMaxSeconds.toDouble();

  @override
  Widget build(BuildContext context) => Padding(
    padding: EdgeInsets.fromLTRB(
      24,
      24,
      24,
      MediaQuery.viewInsetsOf(context).bottom + 24,
    ),
    child: Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'Repeat timing',
          style: Theme.of(context).textTheme.headlineSmall,
        ),
        const SizedBox(height: 8),
        const Text(
          'A ring holds one connection open and pulses inside it. The Air '
          'plays one pulse per change it notices, so too short a gap merges '
          'buzzes into fewer, longer ones. While it rings, Google Health '
          'cannot sync.',
        ),
        const SizedBox(height: 16),
        Text('A pulse every ${gap.round()} ms'),
        Slider(
          key: const ValueKey('ring-gap'),
          min: 250,
          max: 2000,
          divisions: 35,
          value: gap,
          label: '${gap.round()} ms',
          onChanged: (value) => setState(() => gap = value),
        ),
        Text('Give up after ${maximum.round()} seconds'),
        Slider(
          key: const ValueKey('ring-maximum'),
          min: 10,
          max: 180,
          divisions: 34,
          value: maximum,
          label: '${maximum.round()} s',
          onChanged: (value) => setState(() => maximum = value),
        ),
        const SizedBox(height: 16),
        FilledButton(
          key: const ValueKey('save-ring-timing'),
          onPressed: () {
            widget.perform(
              () => widget.state.saveRingSettings(
                gap.round(),
                maximum.round(),
              ),
            );
            Navigator.pop(context);
          },
          child: const Text('Save'),
        ),
      ],
    ),
  );
}

/// One kind of alert under an app: what it is, what it feels like, and a way
/// to change it. Indented so it reads as belonging to the app above it.
Widget _alertRow({
  required Key key,
  required IconData icon,
  required String title,
  required String value,
  required VoidCallback onTap,
}) => ListTile(
  key: key,
  contentPadding: const EdgeInsets.only(left: 40, right: 4),
  dense: true,
  visualDensity: VisualDensity.compact,
  leading: Icon(icon, size: 20),
  title: Text(title),
  subtitle: Text(value),
  trailing: const Icon(Icons.chevron_right, size: 20),
  onTap: onTap,
);

/// Rules for individual callers: who always rings, and who never does.
///
/// Callers are learned from the calls that arrive rather than read from
/// contacts, so the app needs no access to them. The cost is that someone has
/// to call once before they can be given a rule.
List<Widget> _callerRules(
  BuildContext context,
  DemoController state,
  void Function(Future<void> Function()) perform,
) {
  final settings = state.settings;
  final saved = settings.callers;
  final recent = settings.recentCallers;

  Future<void> edit(SavedCaller caller, {bool isNew = false}) => editVibration(
    context,
    state,
    title: caller.label,
    initial: caller.preset,
    allowMute: true,
    allowRing: true,
    inheritedPreset: settings.defaultCallPreset,
    onSave: (value) => state.saveCaller(
      caller.id,
      value,
      label: isNew ? caller.label : null,
    ),
  );

  return [
    ExpansionTile(
      key: const ValueKey('caller-rules'),
      tilePadding: EdgeInsets.zero,
      leading: const Icon(Icons.person_outline),
      title: const Text('Callers'),
      subtitle: Text(
        saved.isEmpty
            ? 'Give someone their own vibration, or silence them'
            : '${saved.length} with their own rule'
                  '${settings.onlySavedCallers ? " · only these ring" : ""}',
      ),
      children: [
        SwitchListTile(
          key: const ValueKey('only-saved-callers'),
          contentPadding: EdgeInsets.zero,
          title: const Text('Only these callers ring'),
          subtitle: const Text(
            'Calls from anyone else stay silent. Calls the app does not '
            'attribute to a person still ring.',
          ),
          value: settings.onlySavedCallers,
          onChanged: state.changingRelay
              ? null
              : (value) => perform(() => state.setOnlySavedCallers(value)),
        ),
        for (final caller in saved)
          ListTile(
            key: ValueKey('caller-${caller.id}'),
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.person),
            title: Text(caller.label),
            subtitle: Text(
              caller.preset?.label ??
                  'Default: ${settings.defaultCallPreset.label}',
            ),
            trailing: IconButton(
              key: ValueKey('remove-caller-${caller.id}'),
              tooltip: 'Remove',
              icon: const Icon(Icons.close),
              onPressed: state.changingRelay
                  ? null
                  : () => perform(() => state.removeCaller(caller.id)),
            ),
            onTap: () => edit(caller),
          ),
        if (recent.isNotEmpty) ...[
          const Padding(
            padding: EdgeInsets.only(top: 8, bottom: 4),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text('Recent callers'),
            ),
          ),
          for (final caller in recent)
            ListTile(
              key: ValueKey('recent-caller-${caller.id}'),
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.person_add_alt),
              title: Text(caller.label),
              subtitle: const Text('Tap to give them their own vibration'),
              onTap: () => edit(caller, isNew: true),
            ),
        ],
        if (saved.isEmpty && recent.isEmpty)
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 8),
            child: Text(
              'Callers appear here once they call while the relay is running. '
              "Nothing is read from your contacts, and a caller's number is "
              'never stored.',
            ),
          ),
      ],
    ),
  ];
}

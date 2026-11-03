import 'package:flutter/material.dart';
import 'demo_controller.dart';
import 'relay_settings.dart';

/// Picks what an alert feels like: a pattern, and the strength it plays at.
///
/// The two are chosen separately because every pattern can use either
/// strength; offering each combination as its own row made a short list long
/// and hid the choice that actually matters. Each tap saves, so there is no
/// Save button to forget.
Future<void> editVibration(
  BuildContext context,
  DemoController controller, {
  required String title,
  required VibrationPreset? initial,
  required Future<void> Function(VibrationPreset?) onSave,
  bool allowMute = false,
  bool allowRing = false,

  /// What this rule falls back to while nothing has been chosen for it.
  VibrationPreset? inheritedPreset,
}) => showModalBottomSheet<void>(
  context: context,
  isScrollControlled: true,
  useSafeArea: true,
  builder: (context) => _VibrationEditor(
    controller: controller,
    title: title,
    initial: initial,
    onSave: onSave,
    allowMute: allowMute,
    allowRing: allowRing,
    inheritedPreset: inheritedPreset,
  ),
);

class _VibrationEditor extends StatefulWidget {
  const _VibrationEditor({
    required this.controller,
    required this.title,
    required this.initial,
    required this.onSave,
    required this.allowMute,
    required this.allowRing,
    required this.inheritedPreset,
  });
  final DemoController controller;
  final String title;
  final VibrationPreset? initial;
  final Future<void> Function(VibrationPreset?) onSave;
  final bool allowMute, allowRing;
  final VibrationPreset? inheritedPreset;
  @override
  State<_VibrationEditor> createState() => _VibrationEditorState();
}

class _VibrationEditorState extends State<_VibrationEditor> {
  late VibrationPreset selected =
      widget.initial ??
      widget.inheritedPreset ??
      widget.controller.settings.defaultPreset;
  bool working = false;
  String? error;
  String? feedback;

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_refresh);
  }

  void _refresh() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    widget.controller.removeListener(_refresh);
    super.dispose();
  }

  Iterable<VibrationPattern> get _patterns => VibrationPattern.values.where(
    (pattern) =>
        (widget.allowRing || !pattern.callsOnly) &&
        (widget.allowMute || pattern != VibrationPattern.silent),
  );

  /// Keeps the chosen strength while moving between patterns, so picking a
  /// pattern never quietly changes how hard it vibrates.
  Future<void> _choose(VibrationPreset next) async {
    setState(() {
      selected = next;
      error = null;
      feedback = null;
      working = true;
    });
    try {
      // No confirmation: the radio moving is the confirmation, and a line of
      // text appearing under every tap is noise.
      await widget.onSave(next);
    } catch (_) {
      if (mounted) {
        setState(() => error = 'Could not save that. Try again.');
      }
    } finally {
      if (mounted) setState(() => working = false);
    }
  }

  Future<void> _preview() async {
    setState(() {
      working = true;
      error = null;
      feedback = null;
    });
    try {
      await widget.controller.previewPreset(selected);
      if (mounted) setState(() => feedback = 'Sent. Did you feel it?');
    } catch (_) {
      if (mounted) {
        setState(
          () => error =
              'Could not send that. Check your Air and relay status, then try again.',
        );
      }
    } finally {
      if (mounted) setState(() => working = false);
    }
  }

  bool get _canPreview =>
      !working &&
      !selected.isSilent &&
      !selected.callsOnly &&
      !widget.controller.busy &&
      !widget.controller.previewing &&
      !widget.controller.changingRelay &&
      !(widget.controller.relayRunning &&
          widget.controller.settings.silenceReason != null);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return FractionallySizedBox(
      heightFactor: .9,
      child: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Row(
            children: [
              Expanded(
                child: Text(widget.title, style: theme.textTheme.headlineSmall),
              ),
              IconButton(
                tooltip: 'Close',
                onPressed: () => Navigator.pop(context),
                icon: const Icon(Icons.close),
              ),
            ],
          ),
          const SizedBox(height: 8),
          const Text('Changes save as you make them.'),
          const SizedBox(height: 8),
          for (final pattern in _patterns)
            ListTile(
              key: ValueKey('pattern-${pattern.id}'),
              contentPadding: EdgeInsets.zero,
              title: Text(pattern.label),
              leading: Icon(
                selected.pattern == pattern
                    ? Icons.radio_button_checked
                    : Icons.radio_button_off,
              ),
              onTap: working
                  ? null
                  : () => _choose(
                      pattern == VibrationPattern.silent
                          ? VibrationPreset.silent
                          : VibrationPreset(pattern, selected.strength),
                    ),
            ),
          // Strength is a property of whatever pattern is selected, so it sits
          // under the list rather than multiplying it.
          if (!selected.isSilent) ...[
            const Divider(height: 32),
            Text('Strength', style: theme.textTheme.titleMedium),
            const SizedBox(height: 8),
            SegmentedButton<VibrationStrength>(
              segments: [
                for (final strength in VibrationStrength.values)
                  ButtonSegment(
                    value: strength,
                    label: Text(strength.label),
                  ),
              ],
              selected: {selected.strength},
              onSelectionChanged: working
                  ? null
                  : (choice) =>
                        _choose(VibrationPreset(selected.pattern, choice.first)),
            ),
            const SizedBox(height: 8),
            Text(
              'The Air has two strengths, and it keeps the one it was last '
              'sent. A lighter setting here is lighter for everything until '
              'something asks for the stronger one.',
              style: theme.textTheme.bodySmall,
            ),
          ],
          const SizedBox(height: 16),
          if (selected.callsOnly)
            const Padding(
              padding: EdgeInsets.only(bottom: 12),
              child: Text(
                'A repeat only runs while a call is actually ringing, so there '
                'is nothing to try here. It stops as soon as the phone does.',
              ),
            )
          else
            OutlinedButton.icon(
              key: const ValueKey('preview-rule'),
              onPressed: _canPreview ? _preview : null,
              icon: working
                  ? const SizedBox.square(
                      dimension: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.vibration),
              label: Text(working ? 'Working…' : 'Try this vibration'),
            ),
          if (widget.controller.relayRunning &&
              widget.controller.settings.silenceReason != null)
            const Padding(
              padding: EdgeInsets.only(top: 8),
              child: Text(
                'Resume notifications or leave quiet hours before trying a vibration.',
              ),
            ),
          if (feedback != null)
            Semantics(liveRegion: true, child: Text(feedback!)),
          if (error != null)
            Semantics(
              liveRegion: true,
              child: Text(
                error!,
                style: TextStyle(color: theme.colorScheme.error),
              ),
            ),
          SizedBox(height: MediaQuery.viewInsetsOf(context).bottom + 16),
        ],
      ),
    );
  }
}

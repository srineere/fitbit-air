import 'package:flutter/material.dart';

import 'demo_controller.dart';
import 'relay_settings.dart';
import 'vibration_editor.dart';

/// Rules for who is calling: a default for everyone, and overrides per person.
///
/// People reach this list two ways. Contacts need the contacts permission and
/// let a rule exist before anyone calls; recent callers need no permission at
/// all but only appear once someone has called. Both end up as the same hashed
/// identity, so a contact and the call they make are recognised as one person.
class CallerRulesPage extends StatefulWidget {
  const CallerRulesPage({
    super.key,
    required this.controller,
    required this.perform,
  });

  final DemoController controller;
  final void Function(Future<void> Function()) perform;

  @override
  State<CallerRulesPage> createState() => _CallerRulesPageState();
}

class _CallerRulesPageState extends State<CallerRulesPage> {
  String search = '';

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_refresh);
    widget.perform(widget.controller.loadContacts);
  }

  void _refresh() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    widget.controller.removeListener(_refresh);
    super.dispose();
  }

  DemoController get state => widget.controller;
  RelaySettings get settings => state.settings;

  bool _matches(SavedCaller caller) =>
      search.isEmpty || caller.label.toLowerCase().contains(search.toLowerCase());

  Future<void> _edit(SavedCaller caller, {required bool isNew}) =>
      editVibration(
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

  Widget _person(SavedCaller caller, {required bool isNew}) => ListTile(
    key: ValueKey('caller-${caller.id}'),
    leading: CircleAvatar(
      child: Text(
        caller.label.characters.firstOrNull?.toUpperCase() ?? '?',
      ),
    ),
    title: Text(caller.label),
    subtitle: Text(
      isNew
          ? 'Uses the default · tap to change'
          : caller.preset?.label ??
                'Default: ${settings.defaultCallPreset.label}',
    ),
    trailing: isNew
        ? const Icon(Icons.chevron_right)
        : IconButton(
            key: ValueKey('remove-caller-${caller.id}'),
            tooltip: 'Remove rule',
            icon: const Icon(Icons.close),
            onPressed: state.changingRelay
                ? null
                : () => widget.perform(() => state.removeCaller(caller.id)),
          ),
    onTap: () => _edit(caller, isNew: isNew),
  );

  List<Widget> _section(String title, List<Widget> rows) => rows.isEmpty
      ? const []
      : [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 20, 16, 4),
            child: Text(
              title,
              style: Theme.of(context).textTheme.titleSmall,
            ),
          ),
          ...rows,
        ];

  @override
  Widget build(BuildContext context) {
    final saved = settings.callers.where(_matches).toList();
    final blocked = saved.where((c) => c.preset?.isSilent == true).toList();
    final ruled = saved.where((c) => c.preset?.isSilent != true).toList();
    final ruledIds = settings.callers.map((c) => c.id).toSet();
    final recent = settings.recentCallers.where(_matches).toList();
    final contacts = state.contacts
        .where((c) => !ruledIds.contains(c.id) && _matches(c))
        .toList();

    return Scaffold(
      appBar: AppBar(title: const Text('Callers')),
      body: ListView(
        children: [
          ListTile(
            key: const ValueKey('default-call-vibration'),
            leading: const Icon(Icons.call),
            title: const Text('Default for calls'),
            subtitle: Text(settings.defaultCallPreset.label),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => editVibration(
              context,
              state,
              title: 'Default for calls',
              initial: settings.defaultCallPreset,
              allowMute: true,
              allowRing: true,
              onSave: (preset) => state.saveDefaultCallPreset(preset!),
            ),
          ),
          SwitchListTile(
            key: const ValueKey('only-saved-callers'),
            title: const Text('Only these callers ring'),
            subtitle: const Text(
              'Everyone else stays silent. A call that does not say who is '
              'calling still rings.',
            ),
            value: settings.onlySavedCallers,
            onChanged: state.changingRelay
                ? null
                : (value) =>
                      widget.perform(() => state.setOnlySavedCallers(value)),
          ),
          const Divider(height: 1),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
            child: TextField(
              key: const ValueKey('caller-search'),
              decoration: const InputDecoration(
                prefixIcon: Icon(Icons.search),
                labelText: 'Search people',
              ),
              onChanged: (value) => setState(() => search = value),
            ),
          ),
          ..._section('Own vibration', [
            for (final caller in ruled) _person(caller, isNew: false),
          ]),
          ..._section('Blocked', [
            for (final caller in blocked) _person(caller, isNew: false),
          ]),
          ..._section('Recent callers', [
            for (final caller in recent) _person(caller, isNew: true),
          ]),
          if (!state.contactsGranted)
            Padding(
              padding: const EdgeInsets.all(16),
              child: Card(
                child: ListTile(
                  key: const ValueKey('grant-contacts'),
                  leading: const Icon(Icons.contacts_outlined),
                  title: const Text('Show my contacts'),
                  subtitle: const Text(
                    'Lets you set a rule before someone calls. Names are read '
                    'to show this list; numbers are hashed and never stored.',
                  ),
                  isThreeLine: true,
                  onTap: () => widget.perform(() async {
                    await state.requestContacts();
                    await state.loadContacts();
                  }),
                ),
              ),
            )
          else
            ..._section('Contacts', [
              for (final caller in contacts) _person(caller, isNew: true),
            ]),
          if (state.contactsGranted && contacts.isEmpty && search.isNotEmpty)
            const Padding(
              padding: EdgeInsets.all(16),
              child: Text('Nobody by that name.'),
            ),
          const SizedBox(height: 24),
        ],
      ),
    );
  }
}

import 'dart:async';

import 'package:fitbit_air/fitbit_air.dart';
import 'package:fitbit_air/fitbit_air_commands.dart';
import 'package:fitbit_air/fitbit_air_diagnostics.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'demo/demo_controller.dart';
import 'demo/app_theme.dart';
import 'demo/relay_panel.dart';
import 'demo/rules_panel.dart';
import 'demo/setup_page.dart';
import 'demo/relay_operation_queue.dart';
import 'demo/device_overview.dart';

void main() {
  FitbitAir.registerLicenses();
  runApp(const DemoApp());
}

/// Service-owned worker. Calendar contents never cross this channel.
@pragma('vm:entry-point')
Future<void> airCalendarRelay() async {
  WidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('air_bridge/calendar_worker');
  final dispatcher = AirHapticDispatcher();
  final operations = RelayOperationQueue();
  channel.setMethodCallHandler((call) async {
    if (call.method == 'close') {
      dispatcher.close();
      unawaited(FitbitAir.stopRing().catchError((Object _) {}));
      unawaited(FitbitAir.keepLinkWarm(false).catchError((Object _) {}));
      return null;
    }
    // Deliberately outside the operation queue: the queue is busy holding the
    // ring, and this is what releases it.
    if (call.method == 'stopRing') {
      await FitbitAir.stopRing();
      return null;
    }
    if (call.method == 'ring') {
      final args = Map<String, Object?>.from(call.arguments as Map);
      try {
        final result = await operations.run(
          () => FitbitAir.ring(
            intensity: args['intensity'] == null
                ? null
                : HapticIntensity.fromWireValue(args['intensity']! as int),
            gap: Duration(milliseconds: args['gapMillis']! as int),
            maximum: Duration(milliseconds: args['maxMillis']! as int),
          ),
        );
        return {
          'status': 'completed',
          'pulses': result.pulses,
          'restored': result.restored,
        };
      } on FitbitAirException catch (error) {
        return {'status': 'failed', 'errorCode': error.code};
      }
    }
    if (const {
      'readBattery',
      'readSignalStrength',
      'readDeviceInfo',
    }.contains(call.method)) {
      if (!operations.isIdle) throw PlatformException(code: 'BUSY');
      try {
        if (call.method == 'readSignalStrength') {
          return {'rssi': await operations.run(FitbitAir.readSignalStrength)};
        }
        if (call.method == 'readDeviceInfo') {
          final info = await operations.run(FitbitAir.readDeviceInfo);
          return {'firmware': info.firmware};
        }
        final value = await operations.run(FitbitAir.readBattery);
        return {'percent': value.percent, 'onCharger': value.onCharger};
      } on FitbitAirException catch (error) {
        throw PlatformException(code: error.code, message: error.message);
      }
    }
    if (call.method != 'dispatch') throw MissingPluginException();
    final args = Map<String, Object?>.from(call.arguments as Map);
    final pulses = args['pulses'] as int? ?? 1;
    // More than one pulse plays as a short bounded ring, so the pattern shares
    // a single session and every pulse uses the strength the rule asked for.
    if (pulses > 1) {
      try {
        final ring = await operations.run(
          () => FitbitAir.ring(
            intensity: args['intensity'] == null
                ? null
                : HapticIntensity.fromWireValue(args['intensity']! as int),
            gap: Duration(milliseconds: args['gapMillis'] as int? ?? 300),
            maximum: const Duration(seconds: 20),
            pulses: pulses,
          ),
        );
        return {
          'status': ring.pulses >= pulses ? 'completed' : 'failed',
          'errorCode': ring.pulses >= pulses ? null : 'PARTIAL',
        };
      } on FitbitAirException catch (error) {
        return {'status': 'failed', 'errorCode': error.code};
      }
    }
    final result = await operations.run(
      () => dispatcher.submit(
        AirHapticCommand(
          id: args['id']! as String,
          expiresAt: DateTime.fromMillisecondsSinceEpoch(
            args['expiresAt']! as int,
          ),
          kind: args['kind'] == 'tap'
              ? AirHapticKind.tap
              : AirHapticKind.preview,
          intensity: args['intensity'] == null
              ? null
              : HapticIntensity.fromWireValue(args['intensity'] as int),
        ),
      ),
    );
    return {'status': result.status.name, 'errorCode': result.error?.code};
  });
  // Commands then reuse an open link: about a second faster per alert.
  try {
    await FitbitAir.keepLinkWarm(true);
  } on FitbitAirException {
    // Commands still work with a fresh connection each time.
  }
  await channel.invokeMethod<void>('ready');
}

class DemoApp extends StatelessWidget {
  const DemoApp({super.key, this.controller});
  final DemoController? controller;

  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Air Bridge',
    debugShowCheckedModeBanner: false,
    theme: airRelayTheme(Brightness.light),
    darkTheme: airRelayTheme(Brightness.dark),
    home: DemoPage(controller: controller),
  );
}

class DemoPage extends StatefulWidget {
  const DemoPage({super.key, this.controller});
  final DemoController? controller;

  @override
  State<DemoPage> createState() => _DemoPageState();
}

class _DemoPageState extends State<DemoPage> with WidgetsBindingObserver {
  late final DemoController _controller;
  int _destination = 0;
  bool _consideredSetup = false;
  bool _launchingSetup = false;
  bool get _busy => _controller.busy;

  /// Inline Test vibration state on the Relay tab: null, 'sending' or 'sent'.
  String? _testFeedback;
  Timer? _testFeedbackTimer;
  bool get _deviceControlsBlocked =>
      _controller.busy || _controller.changingRelay;

  @override
  void initState() {
    super.initState();
    _controller = widget.controller ?? DemoController();
    _controller.addListener(_redraw);
    _registerActions();
    WidgetsBinding.instance.addObserver(this);
    unawaited(_controller.initialize());
    _controller.setVisible(true);
  }

  void _redraw() {
    if (!mounted) return;
    if (_controller.initialized && !_consideredSetup) {
      _consideredSetup = true;
      if (!_controller.setupCompleted) {
        _launchingSetup = true;
        WidgetsBinding.instance.addPostFrameCallback((_) async {
          if (!mounted) return;
          await showRelaySetup(
            context,
            _controller,
            initialStep: _controller.setupStep,
          );
          if (mounted) setState(() => _launchingSetup = false);
        });
      }
    }
    setState(() {});
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _controller.setVisible(state == AppLifecycleState.resumed);
    if (state == AppLifecycleState.resumed) unawaited(_controller.loadApps());
  }

  void _registerActions() {
    _controller.actions.addAll({
      'previewDefault': DemoAction(
        () => _controller.previewPreset(_controller.settings.defaultPreset),
        exclusive: false,
      ),
      'pause15': DemoAction(() => _controller.setPause(15), exclusive: false),
      'pause60': DemoAction(() => _controller.setPause(60), exclusive: false),
      'pauseUntilResumed': DemoAction(
        () => _controller.setPause(-1),
        exclusive: false,
      ),
      'resumeRelay': DemoAction(
        () => _controller.setPause(0),
        exclusive: false,
      ),
      'preview': DemoAction(_vibrate),
    });
    _controller.cancelCurrent = _cancelOperation;
  }

  static const _repositoryUrl = 'https://github.com/ThoreKoritzius/fitbit-air';

  Widget _repositoryLink(BuildContext context) => InkWell(
    key: const ValueKey('about-repository'),
    onTap: () async {
      try {
        await DemoController.channel.invokeMethod<void>('openRepository');
      } on PlatformException {
        await Clipboard.setData(const ClipboardData(text: _repositoryUrl));
        if (context.mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('Link copied to the clipboard.')),
          );
        }
      }
    },
    child: Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          const Icon(Icons.code, size: 18),
          const SizedBox(width: 8),
          Flexible(
            child: Text(
              'github.com/ThoreKoritzius/fitbit-air',
              style: TextStyle(
                color: Theme.of(context).colorScheme.primary,
                decoration: TextDecoration.underline,
              ),
            ),
          ),
        ],
      ),
    ),
  );

  Widget _madeWithLove(BuildContext context) => Semantics(
    key: const ValueKey('about-credit'),
    label: 'Made with love by Thore (The Centaurus)',
    excludeSemantics: true,
    child: Wrap(
      crossAxisAlignment: WrapCrossAlignment.center,
      children: const [
        Text('Made with '),
        Icon(Icons.favorite, size: 16, color: Color(0xffe25563)),
        Text(' by Thore (The Centaurus)'),
      ],
    ),
  );

  /// Relay-tab test: progress stays in the button; failures use a snackbar.
  Future<void> _testVibration() async {
    _testFeedbackTimer?.cancel();
    setState(() => _testFeedback = 'sending');
    bool sent;
    sent = await _ui(
      () => _controller.previewPreset(_controller.settings.defaultPreset),
      retry: _testVibration,
    );
    if (!mounted) return;
    setState(() => _testFeedback = sent ? 'sent' : null);
    if (sent) {
      _testFeedbackTimer = Timer(const Duration(seconds: 2), () {
        if (mounted) setState(() => _testFeedback = null);
      });
    }
  }

  void _showError(String message, {VoidCallback? retry}) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          key: const ValueKey('error-bar'),
          behavior: SnackBarBehavior.floating,
          content: Text(message),
          action: retry == null
              ? null
              : SnackBarAction(label: 'Retry', onPressed: retry),
        ),
      );
  }

  Future<void> _performAction(String id) async {
    if (_controller.relayRunning) {
      final stop = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Stop the relay for this operation?'),
          content: const Text(
            'An active haptic will finish restoration first. The relay stays off afterward; start it again when ready.',
          ),
          actions: [
            TextButton(
              key: const ValueKey('keep-relay'),
              onPressed: () => Navigator.pop(context, false),
              child: const Text('Keep relay running'),
            ),
            FilledButton(
              key: const ValueKey('stop-relay-for-operation'),
              onPressed: () => Navigator.pop(context, true),
              child: const Text('Stop relay and continue'),
            ),
          ],
        ),
      );
      if (stop != true || !mounted) return;
      await _ui(_controller.stopRelayAndWait);
      if (_controller.relayRunning) return;
    }
    if (mounted) await _ui(() => _controller.runAction(id));
  }

  String _describeError(Object error) => error is FitbitAirException
      ? DemoController.recoveryHint(error.code)
      : error is PlatformException
      ? DemoController.recoveryHint(error.code)
      : error.toString();

  /// Runs [action], showing failures in a floating bar. Returns success.
  Future<bool> _ui(
    Future<void> Function() action, {
    VoidCallback? retry,
  }) async {
    try {
      await action();
      return true;
    } catch (error) {
      if (!mounted) return false;
      final message = error is FitbitAirException
          ? DemoController.recoveryHint(error.code)
          : error is PlatformException
          ? DemoController.recoveryHint(error.code)
          : _describeError(error);
      setState(() => _status = message);
      _showError(message, retry: retry);
      return false;
    }
  }

  Future<void> _copyReport() async {
    await Clipboard.setData(ClipboardData(text: _controller.exportJson()));
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            'Sanitized JSON report copied. No notification text or BLE identifiers.',
          ),
        ),
      );
    }
  }

  Future<void> _cancelOperation() async {
    try {
      if (_heartRateSubscription != null) {
        _controller.setOutcome('cancelled');
        await _heartRateSubscription!.cancel();
        _heartRateSubscription = null;
      } else if (_secureEventSubscription != null) {
        _controller.setOutcome('cancelled');
        _stopCaptureClock();
        await _secureEventSubscription!.cancel();
        _secureEventSubscription = null;
      } else if (_deviceEventSubscription != null) {
        _controller.setOutcome('cancelled');
        await _deviceEventSubscription!.cancel();
        _deviceEventSubscription = null;
      } else {
        await FitbitAir.cancelOperation();
        // One-shot futures finish cleanup before their controller action returns.
        return;
      }
      _controller.finishOperation('cancelled');
      if (mounted) {
        setState(
          () => _status =
              'Operation cancelled. Partial results remain in the report.',
        );
      }
    } on FitbitAirException catch (error) {
      if (mounted) {
        setState(() => _status = DemoController.recoveryHint(error.code));
      }
    }
  }

  AirDeviceInfo? _deviceInfo;
  StreamSubscription<AirHeartRate>? _heartRateSubscription;
  StreamSubscription<AirDeviceEvent>? _deviceEventSubscription;
  StreamSubscription<AirSecureEvent>? _secureEventSubscription;
  final _scrollController = ScrollController();
  final _deviceScrollController = ScrollController();
  final _homeScrollController = ScrollController();
  final _rulesScrollController = ScrollController();
  final _captureClock = Stopwatch();
  Timer? _captureTimer;

  void _stopCaptureClock() {
    _captureTimer?.cancel();
    _captureClock.stop();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _controller.removeListener(_redraw);
    _controller.setVisible(false);
    if (widget.controller == null) _controller.dispose();
    _stopCaptureClock();
    _testFeedbackTimer?.cancel();
    _scrollController.dispose();
    _deviceScrollController.dispose();
    _homeScrollController.dispose();
    _rulesScrollController.dispose();
    _heartRateSubscription?.cancel();
    _deviceEventSubscription?.cancel();
    _secureEventSubscription?.cancel();
    super.dispose();
  }

  String _status = 'Check for an Air paired to this phone.';

  Future<void> _run(String pending, Future<String> Function() operation) async {
    setState(() {
      _status = pending;
    });
    try {
      final status = await operation();
      if (mounted) setState(() => _status = status);
    } on FitbitAirException catch (error) {
      _controller.failOperation(error);
      if (mounted) setState(() => _status = _describeError(error));
    } finally {
      if (mounted) setState(() => _controller.finishOperation());
    }
  }

  Future<void> _vibrate() async {
    await _run('Sending a restore-safe two-pulse preview…', () async {
      final result = await FitbitAir.vibrate();
      if (!result.verified || !result.restored) {
        throw const FitbitAirException(
          'UNVERIFIED',
          'Haptic restoration was not verified.',
        );
      }
      return 'Two-pulse preview complete. Prior intensity '
          '${result.originalIntensity}; restored and verified: '
          '${result.restored && result.verified}.';
    });
  }

  Widget _action(String id, String label, IconData icon) => Padding(
    padding: const EdgeInsets.only(bottom: 10),
    child: FilledButton.icon(
      key: ValueKey('action-$id'),
      onPressed: _deviceControlsBlocked ? null : () => _performAction(id),
      icon: Icon(icon),
      label: Text(label),
    ),
  );

  List<Widget> _deviceChildren() => [
    DeviceOverview(
      controller: _controller,
      model: _deviceInfo?.model,
      firmware: _deviceInfo?.firmware ?? _controller.firmware,
    ),
    const SizedBox(height: 12),
    ListTile(
      key: const ValueKey('review-setup'),
      contentPadding: EdgeInsets.zero,
      leading: const Icon(Icons.verified_user_outlined),
      title: const Text('Permissions & setup'),
      trailing: const Icon(Icons.chevron_right),
      onTap: () => showRelaySetup(context, _controller),
    ),
    const Divider(),
    const Text(
      'Check your band is reachable, and feel what a notification feels like.',
    ),
    const SizedBox(height: 16),
    Text(_status, key: const ValueKey('device-status')),
    const SizedBox(height: 16),
    _action('preview', 'Try a vibration', Icons.vibration),
  ];


  @override
  Widget build(BuildContext context) =>
      !_controller.initialized || _launchingSetup
      ? const Scaffold(body: Center(child: CircularProgressIndicator()))
      : PopScope(
          canPop: _destination != 2,
          onPopInvokedWithResult: (didPop, result) {
            if (!didPop && _destination == 2) setState(() => _destination = 1);
          },
          child: Scaffold(
            appBar: AppBar(
              leading: _destination == 2
                  ? BackButton(
                      onPressed: () => setState(() => _destination = 1),
                    )
                  : null,
              title: const Row(
                children: [
                  AirRelayMark(size: 34),
                  SizedBox(width: 12),
                  Flexible(
                    child: Text('Air Bridge', overflow: TextOverflow.ellipsis),
                  ),
                ],
              ),
              actions: [
                IconButton(
                  key: const ValueKey('about-air-relay'),
                  tooltip: 'About Air Bridge',
                  icon: const Icon(Icons.info_outline),
                  onPressed: () => showAboutDialog(
                    context: context,
                    applicationName: 'Air Bridge',
                    applicationVersion: DemoController.appVersion,
                    applicationIcon: const AirRelayMark(size: 48),
                    children: [
                      const Text(
                        'An independent notification companion for Fitbit Air. Not affiliated with Google or Fitbit.',
                      ),
                      const SizedBox(height: 12),
                      const Text(
                        'Good to know:\n'
                        '• Vibrations usually arrive a few seconds after the notification.\n'
                        '• The Air must be near your phone; missed alerts are not replayed.\n'
                        '• Air Bridge uses an undocumented interface of the Air. A firmware update can stop it from working until Air Bridge is updated.',
                      ),
                      const SizedBox(height: 12),
                      Text(
                        'Tested with Air firmware ${FitbitAir.testedFirmware.join(', ')}'
                        '${_controller.firmware == null ? '' : '. Yours: ${_controller.firmware}'}.',
                        key: const ValueKey('about-firmware'),
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                      const SizedBox(height: 12),
                      _repositoryLink(context),
                      Wrap(
                        spacing: 4,
                        children: [
                          TextButton.icon(
                            key: const ValueKey('about-updates'),
                            onPressed: () => _ui(_controller.openReleases),
                            icon: const Icon(
                              Icons.system_update_outlined,
                              size: 18,
                            ),
                            label: const Text('Check for updates'),
                          ),
                          TextButton.icon(
                            key: const ValueKey('about-privacy'),
                            onPressed: () => _ui(_controller.openPrivacyPolicy),
                            icon: const Icon(
                              Icons.privacy_tip_outlined,
                              size: 18,
                            ),
                            label: const Text('Privacy policy'),
                          ),
                          TextButton.icon(
                            key: const ValueKey('about-report'),
                            onPressed: () => _ui(_controller.openIssues),
                            icon: const Icon(
                              Icons.bug_report_outlined,
                              size: 18,
                            ),
                            label: const Text('Report a problem'),
                          ),
                        ],
                      ),
                      const SizedBox(height: 12),
                      _madeWithLove(context),
                    ],
                  ),
                ),
              ],
            ),
            body: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 640),
                child: Column(
                  children: [
                    // The Relay tab shows its own test progress inline; other work
                    // started elsewhere still gets the owner banner.
                    if (_destination == 2 || (_busy && _testFeedback == null))
                      Semantics(
                        liveRegion: true,
                        child: ListTile(
                          key: const ValueKey('operation-owner'),
                          leading: Icon(
                            _busy
                                ? Icons.science
                                : _controller.relayRunning
                                ? Icons.notifications_active
                                : Icons.check_circle_outline,
                          ),
                          title: Text(
                            _busy
                                ? 'In progress: ${_controller.operationId}'
                                : _controller.relayRunning
                                ? 'Relay running'
                                : 'Ready',
                          ),
                          subtitle: _controller.relayStopping
                              ? const Text(
                                  'Finishing an active command before stopping.',
                                )
                              : null,
                          trailing: _busy
                              ? TextButton(
                                  key: const ValueKey('cancel-operation'),
                                  onPressed:
                                      _controller.operationId?.startsWith(
                                            'headless',
                                          ) ==
                                          true
                                      ? null
                                      : () => _ui(_cancelOperation),
                                  child: const Text('Cancel operation'),
                                )
                              : null,
                        ),
                      ),
                    Expanded(
                      child: _destination == 0
                          ? RelayPanel(
                              controller: _controller,
                              scrollController: _homeScrollController,
                              perform: (action) => _ui(action),
                              // Matches what the relay sends for the selected style.
                              testVibration: _testVibration,
                              testFeedback: _testFeedback,
                              exportReport: _copyReport,
                              openRules: () => setState(() => _destination = 3),
                            )
                          : _destination == 3
                          ? RulesPanel(
                              controller: _controller,
                              scrollController: _rulesScrollController,
                              perform: (action) => _ui(action),
                            )
                          : ListView(
                              key: ValueKey(
                                _destination == 1
                                    ? 'device-scroll'
                                    : 'lab-scroll',
                              ),
                              controller: _destination == 1
                                  ? _deviceScrollController
                                  : _scrollController,
                              padding: const EdgeInsets.all(20),
                              children: _destination == 1
                                  ? _deviceChildren()
                                  : const [],
                            ),
                    ),
                  ],
                ),
              ),
            ),
            bottomNavigationBar: NavigationBar(
              selectedIndex: _destination == 0
                  ? 0
                  : _destination == 3
                  ? 1
                  : 2,
              onDestinationSelected: (index) {
                setState(() => _destination = [0, 3, 1][index]);
                _controller.setDeviceVisible(index == 2);
              },
              destinations: const [
                NavigationDestination(
                  key: ValueKey('nav-relay'),
                  icon: Icon(Icons.notifications_outlined),
                  selectedIcon: Icon(Icons.notifications_active),
                  label: 'Home',
                ),
                NavigationDestination(
                  key: ValueKey('nav-rules'),
                  icon: Icon(Icons.tune_outlined),
                  selectedIcon: Icon(Icons.tune),
                  label: 'Rules',
                ),
                NavigationDestination(
                  key: ValueKey('nav-device'),
                  icon: Icon(Icons.watch_outlined),
                  selectedIcon: Icon(Icons.watch),
                  label: 'Device',
                ),
              ],
            ),
          ),
        );
}

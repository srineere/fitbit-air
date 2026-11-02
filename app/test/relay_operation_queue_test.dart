import 'dart:async';
import 'package:fitbit_air_example/demo/relay_operation_queue.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('notifications wait for a battery read and keep their order', () async {
    final queue = RelayOperationQueue();
    final readComplete = Completer<void>();
    final order = <String>[];
    final battery = queue.run(() async {
      order.add('battery started');
      await readComplete.future;
      order.add('battery released');
    });
    final first = queue.run(() async => order.add('notification 1'));
    final second = queue.run(() async => order.add('notification 2'));
    await Future<void>.delayed(Duration.zero);
    expect(queue.isIdle, isFalse);
    expect(order, ['battery started']);
    readComplete.complete();
    await Future.wait([battery, first, second]);
    await Future<void>.delayed(Duration.zero);
    expect(order, [
      'battery started',
      'battery released',
      'notification 1',
      'notification 2',
    ]);
    expect(queue.isIdle, isTrue);
  });

  test('a failed read releases the queue for a notification', () async {
    final queue = RelayOperationQueue();
    final read = queue.run<void>(() async {
      throw StateError('read failed');
    });
    final notification = queue.run(() async => 'delivered');
    await expectLater(read, throwsStateError);
    expect(await notification, 'delivered');
    await Future<void>.delayed(Duration.zero);
    expect(queue.isIdle, isTrue);
  });
}

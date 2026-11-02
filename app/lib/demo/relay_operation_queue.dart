/// Serializes secure device operations within the relay's existing engine.
/// A battery read may only join an idle queue; notification work can queue
/// behind an accepted read rather than colliding with its transport lease.
class RelayOperationQueue {
  Future<void> _tail = Future<void>.value();
  int _pending = 0;
  bool get isIdle => _pending == 0;

  Future<T> run<T>(Future<T> Function() operation) {
    _pending++;
    final result = _tail.then((_) => operation());
    _tail = result
        .then<void>((_) {}, onError: (Object error, StackTrace stack) {})
        .whenComplete(() => _pending--);
    return result;
  }
}

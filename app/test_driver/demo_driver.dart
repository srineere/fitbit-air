import 'dart:convert';
import 'dart:io';

import 'package:integration_test/integration_test_driver.dart';

Future<void> main() async {
  await integrationDriver(
    writeResponseOnFailure: true,
    responseDataCallback: (data) async {
      if (data == null) return;
      await Directory('build').create(recursive: true);
      await File(
        'build/air-demo-report.json',
      ).writeAsString(const JsonEncoder.withIndent('  ').convert(data));
    },
  );
}

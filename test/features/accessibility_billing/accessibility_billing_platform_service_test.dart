import 'dart:typed_data';

import 'package:beecount/features/accessibility_billing/accessibility_billing_platform_service.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel(
    AccessibilityBillingPlatformService.channelName,
  );
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  const service = AccessibilityBillingPlatformService();

  tearDown(() {
    messenger.setMockMethodCallHandler(channel, null);
  });

  test('getSystemPermissionStatus invokes getPermissionStatus', () async {
    final calls = <MethodCall>[];
    final expected = <Object?, Object?>{
      'overlayPermissionGranted': true,
      'batteryOptimizationIgnored': false,
      'notificationsEnabled': true,
    };
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return expected;
    });

    final result = await service.getSystemPermissionStatus();

    expect(result, expected);
    expect(calls, hasLength(1));
    expect(calls.single.method, 'getPermissionStatus');
    expect(calls.single.arguments, isNull);
  });

  test('dismissOverlay invokes the native overlay dismissal contract',
      () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return null;
    });

    await service.dismissOverlay();

    expect(calls, hasLength(1));
    expect(calls.single.method, 'dismissOverlay');
    expect(calls.single.arguments, isNull);
  });

  test('updateRecognitionRules returns the refreshed native status', () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return <Object?, Object?>{
        'ruleVersion': '2026.08.1',
        'ruleSource': 'remote',
        'ruleUpdateSupported': true,
        'updated': false,
        'unchanged': true,
        'adaptedApps': <Object?>[
          <Object?, Object?>{
            'packageName': 'com.tencent.mm',
            'displayName': 'WeChat',
            'enabled': true,
            'ruleCount': 4,
          },
        ],
      };
    });

    final result = await service.updateRecognitionRules();

    expect(calls.single.method, 'updateRecognitionRules');
    expect(calls.single.arguments, isNull);
    expect(result.ruleVersion, '2026.08.1');
    expect(result.ruleSource, 'remote');
    expect(result.updated, isFalse);
    expect(result.unchanged, isTrue);
    expect(result.adaptedApps.single.packageName, 'com.tencent.mm');
    expect(result.adaptedApps.single.ruleCount, 4);
  });

  test('setRecognitionPackageEnabled sends package name and enabled state',
      () async {
    final calls = <MethodCall>[];
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      return null;
    });

    await service.setRecognitionPackageEnabled(
      packageName: 'com.tencent.mm',
      enabled: false,
    );

    expect(calls.single.method, 'setRecognitionPackageEnabled');
    expect(calls.single.arguments, <String, Object?>{
      'packageName': 'com.tencent.mm',
      'enabled': false,
    });
  });

  test('onTransactionDetected forwards the native recognition payload',
      () async {
    final expected = <Object?, Object?>{
      'id': 'recognition-1',
      'amount': '28.50',
      'transactionType': 'expense',
      'merchant': 'Example merchant',
      'sourcePackage': 'com.eg.android.AlipayGphone',
    };
    final event = service.detectedTransactions.first;

    await messenger.handlePlatformMessage(
      AccessibilityBillingPlatformService.channelName,
      const StandardMethodCodec().encodeMethodCall(
        MethodCall('onTransactionDetected', expected),
      ),
      (ByteData? _) {},
    );

    expect(await event, expected);
  });
}

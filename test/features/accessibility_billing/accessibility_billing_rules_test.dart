import 'package:beecount/features/accessibility_billing/accessibility_billing_platform_service.dart';
import 'package:beecount/features/accessibility_billing/accessibility_billing_providers.dart';
import 'package:beecount/features/accessibility_billing/accessibility_billing_settings.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues(<String, Object>{});
  });

  test('parses dynamic app and remote rule metadata', () {
    final settings = AccessibilityBillingPlatformSettings(
      values: <Object?, Object?>{
        'adaptedApps': <Object?>[
          <Object?, Object?>{
            'packageName': 'com.example.pay',
            'displayName': 'Example Pay',
            'enabled': true,
            'ruleCount': '3',
          },
        ],
        'ruleVersion': '2026.08.1',
        'ruleUpdatedAt': '2026-08-08T12:30:00+08:00',
        'ruleSource': 'remote',
        'ruleUpdateSupported': true,
      },
    );

    expect(settings.hasDynamicAdaptedApps, isTrue);
    expect(settings.adaptedApps.single.packageName, 'com.example.pay');
    expect(settings.adaptedApps.single.displayName, 'Example Pay');
    expect(settings.adaptedApps.single.ruleCount, 3);
    expect(settings.ruleVersion, '2026.08.1');
    expect(settings.ruleUpdatedAt?.toUtc(), DateTime.utc(2026, 8, 8, 4, 30));
    expect(settings.ruleSource, 'remote');
    expect(settings.ruleUpdateSupported, isTrue);
  });

  test('falls back to legacy WeChat and Alipay settings', () async {
    final platform = _FakePlatformService(
      settings: const AccessibilityBillingPlatformSettings(
        values: <Object?, Object?>{
          'wechatEnabled': false,
          'alipayEnabled': true,
        },
      ),
    );
    final notifier = AccessibilityBillingNotifier(
      store: const AccessibilityBillingSettingsStore(),
      platformService: platform,
    );
    addTearDown(notifier.dispose);

    await _waitForLoad(notifier);

    expect(notifier.state.usesDynamicAdaptedApps, isFalse);
    expect(
      notifier.state.adaptedApps.map((app) => app.packageName),
      <String>['com.tencent.mm', 'com.eg.android.AlipayGphone'],
    );
    expect(notifier.state.adaptedApps.first.enabled, isFalse);

    await notifier.setAdaptedAppEnabled('com.tencent.mm', true);
    expect(platform.updatedLegacySettings?['wechatEnabled'], isTrue);
    expect(platform.packageUpdates, isEmpty);
    expect(notifier.state.adaptedApps.first.enabled, isTrue);
  });

  test('manually updates rules and toggles a dynamic package', () async {
    final platform = _FakePlatformService(
      settings: const AccessibilityBillingPlatformSettings(
        values: <Object?, Object?>{
          'adaptedApps': <Object?>[],
          'ruleVersion': 'built-in-1',
          'ruleSource': 'builtin',
          'ruleUpdateSupported': true,
        },
      ),
      refreshedStatus: AccessibilityBillingRulesStatus.fromMap(
        <Object?, Object?>{
          'adaptedApps': <Object?>[
            <Object?, Object?>{
              'packageName': 'com.example.pay',
              'displayName': 'Example Pay',
              'enabled': true,
              'ruleCount': 5,
            },
          ],
          'ruleVersion': 'remote-2',
          'ruleUpdatedAt': 1786161600000,
          'ruleSource': 'remote',
          'ruleUpdateSupported': true,
        },
      ),
    );
    final notifier = AccessibilityBillingNotifier(
      store: const AccessibilityBillingSettingsStore(),
      platformService: platform,
    );
    addTearDown(notifier.dispose);
    await _waitForLoad(notifier);

    await notifier.updateRecognitionRules();

    expect(platform.ruleUpdateCalls, 1);
    expect(notifier.state.ruleVersion, 'remote-2');
    expect(notifier.state.ruleSource, 'remote');
    expect(notifier.state.adaptedApps.single.ruleCount, 5);
    expect(notifier.state.ruleUpdateMessage, '适配规则已更新');
    expect(notifier.state.isUpdatingRules, isFalse);

    await notifier.setAdaptedAppEnabled('com.example.pay', false);
    expect(notifier.state.adaptedApps.single.enabled, isFalse);
    expect(platform.packageUpdates.single, <String, Object?>{
      'packageName': 'com.example.pay',
      'enabled': false,
    });
  });

  test('surfaces a native rule update failure without reporting success',
      () async {
    final platform = _FakePlatformService(
      settings: const AccessibilityBillingPlatformSettings(
        values: <Object?, Object?>{
          'adaptedApps': <Object?>[],
          'ruleUpdateSupported': true,
        },
      ),
      refreshedStatus: const AccessibilityBillingRulesStatus(
        updated: false,
        errorMessage: '网络不可用',
      ),
    );
    final notifier = AccessibilityBillingNotifier(
      store: const AccessibilityBillingSettingsStore(),
      platformService: platform,
    );
    addTearDown(notifier.dispose);
    await _waitForLoad(notifier);

    await notifier.updateRecognitionRules();

    expect(notifier.state.isUpdatingRules, isFalse);
    expect(notifier.state.errorMessage, '网络不可用');
    expect(notifier.state.ruleUpdateMessage, isNull);
  });

  test('treats an unchanged rule update as a successful refresh', () async {
    final platform = _FakePlatformService(
      settings: const AccessibilityBillingPlatformSettings(
        values: <Object?, Object?>{
          'adaptedApps': <Object?>[],
          'ruleUpdateSupported': true,
        },
      ),
      refreshedStatus: AccessibilityBillingRulesStatus.fromMap(
        <Object?, Object?>{
          'adaptedApps': <Object?>[
            <Object?, Object?>{
              'packageName': 'com.tencent.mm',
              'displayName': '微信',
              'enabled': true,
              'ruleCount': 4,
            },
          ],
          'ruleVersion': '2026.08.1',
          'ruleSource': 'cache',
          'ruleUpdateSupported': true,
          'updated': false,
          'unchanged': true,
        },
      ),
    );
    final notifier = AccessibilityBillingNotifier(
      store: const AccessibilityBillingSettingsStore(),
      platformService: platform,
    );
    addTearDown(notifier.dispose);
    await _waitForLoad(notifier);

    await notifier.updateRecognitionRules();

    expect(notifier.state.errorMessage, isNull);
    expect(notifier.state.ruleUpdateMessage, '已是最新适配规则');
    expect(notifier.state.ruleVersion, '2026.08.1');
    expect(notifier.state.ruleSource, 'cache');
    expect(notifier.state.adaptedApps.single.ruleCount, 4);
  });
}

Future<void> _waitForLoad(AccessibilityBillingNotifier notifier) async {
  for (var attempt = 0; attempt < 100 && notifier.state.isLoading; attempt++) {
    await Future<void>.delayed(const Duration(milliseconds: 5));
  }
  expect(notifier.state.isLoading, isFalse);
}

class _FakePlatformService extends AccessibilityBillingPlatformService {
  _FakePlatformService({
    required this.settings,
    this.refreshedStatus = const AccessibilityBillingRulesStatus(),
  });

  final AccessibilityBillingPlatformSettings settings;
  final AccessibilityBillingRulesStatus refreshedStatus;
  Map<String, bool>? updatedLegacySettings;
  final List<Map<String, Object?>> packageUpdates = <Map<String, Object?>>[];
  int ruleUpdateCalls = 0;

  @override
  Future<AccessibilityBillingPlatformSettings?> getSettings() async => settings;

  @override
  Future<bool> getServiceStatus() async => false;

  @override
  Future<Map<Object?, Object?>> getSystemPermissionStatus() async =>
      const <Object?, Object?>{};

  @override
  Future<void> updateSettings(Map<String, bool> settings) async {
    updatedLegacySettings = settings;
  }

  @override
  Future<AccessibilityBillingRulesStatus> updateRecognitionRules() async {
    ruleUpdateCalls += 1;
    return refreshedStatus;
  }

  @override
  Future<void> setRecognitionPackageEnabled({
    required String packageName,
    required bool enabled,
  }) async {
    packageUpdates.add(<String, Object?>{
      'packageName': packageName,
      'enabled': enabled,
    });
  }
}

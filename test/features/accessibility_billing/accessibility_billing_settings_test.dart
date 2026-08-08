import 'package:beecount/features/accessibility_billing/accessibility_billing_settings.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  group('AccessibilityBillingSettings', () {
    test('serializes the Android platform contract', () {
      const settings = AccessibilityBillingSettings(
        masterEnabled: true,
        wechatEnabled: false,
        alipayEnabled: true,
        autoExtractNote: false,
      );

      expect(settings.toPlatformMap(), <String, bool>{
        'masterEnabled': true,
        'wechatEnabled': false,
        'alipayEnabled': true,
        'autoExtractNote': false,
      });
    });

    test('uses fallback values for missing or invalid platform fields', () {
      const fallback = AccessibilityBillingSettings(
        masterEnabled: true,
        wechatEnabled: false,
        alipayEnabled: false,
        autoExtractNote: false,
      );

      final settings = AccessibilityBillingSettings.fromPlatformMap(
        <Object?, Object?>{
          'masterEnabled': false,
          'wechatEnabled': 'true',
          'alipayEnabled': 'invalid',
          'autoExtractNote': 'true',
        },
        fallback: fallback,
      );

      expect(
        settings,
        const AccessibilityBillingSettings(
          masterEnabled: false,
          wechatEnabled: true,
          alipayEnabled: false,
          autoExtractNote: true,
        ),
      );
    });
  });

  group('AccessibilityBillingSettingsStore', () {
    setUp(() {
      SharedPreferences.setMockInitialValues(<String, Object>{});
    });

    test(
      'defaults apps to enabled while keeping the master switch off',
      () async {
        final settings = await const AccessibilityBillingSettingsStore().load();

        expect(settings.masterEnabled, isFalse);
        expect(settings.wechatEnabled, isTrue);
        expect(settings.alipayEnabled, isTrue);
        expect(settings.autoExtractNote, isTrue);
      },
    );

    test('persists all user preferences', () async {
      const expected = AccessibilityBillingSettings(
        masterEnabled: true,
        wechatEnabled: false,
        alipayEnabled: true,
        autoExtractNote: false,
      );
      const store = AccessibilityBillingSettingsStore();

      await store.save(expected);

      expect(await store.load(), expected);
    });
  });
}

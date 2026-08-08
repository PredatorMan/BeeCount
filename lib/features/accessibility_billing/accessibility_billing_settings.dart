import 'package:shared_preferences/shared_preferences.dart';

/// User-controlled recognition settings shared by Flutter and Android.
class AccessibilityBillingSettings {
  const AccessibilityBillingSettings({
    this.masterEnabled = false,
    this.wechatEnabled = true,
    this.alipayEnabled = true,
    this.autoExtractNote = true,
  });

  static const masterEnabledKey = 'accessibility_billing_master_enabled';
  static const wechatEnabledKey = 'accessibility_billing_wechat_enabled';
  static const alipayEnabledKey = 'accessibility_billing_alipay_enabled';
  static const autoExtractNoteKey = 'accessibility_billing_auto_extract_note';

  final bool masterEnabled;
  final bool wechatEnabled;
  final bool alipayEnabled;
  final bool autoExtractNote;

  AccessibilityBillingSettings copyWith({
    bool? masterEnabled,
    bool? wechatEnabled,
    bool? alipayEnabled,
    bool? autoExtractNote,
  }) {
    return AccessibilityBillingSettings(
      masterEnabled: masterEnabled ?? this.masterEnabled,
      wechatEnabled: wechatEnabled ?? this.wechatEnabled,
      alipayEnabled: alipayEnabled ?? this.alipayEnabled,
      autoExtractNote: autoExtractNote ?? this.autoExtractNote,
    );
  }

  /// Map contract consumed by the Android accessibility service.
  Map<String, bool> toPlatformMap() => <String, bool>{
        'masterEnabled': masterEnabled,
        'wechatEnabled': wechatEnabled,
        'alipayEnabled': alipayEnabled,
        'autoExtractNote': autoExtractNote,
      };

  factory AccessibilityBillingSettings.fromPlatformMap(
    Map<Object?, Object?> map, {
    AccessibilityBillingSettings fallback =
        const AccessibilityBillingSettings(),
  }) {
    return AccessibilityBillingSettings(
      masterEnabled: _readBool(map['masterEnabled']) ?? fallback.masterEnabled,
      wechatEnabled: _readBool(map['wechatEnabled']) ?? fallback.wechatEnabled,
      alipayEnabled: _readBool(map['alipayEnabled']) ?? fallback.alipayEnabled,
      autoExtractNote:
          _readBool(map['autoExtractNote']) ?? fallback.autoExtractNote,
    );
  }

  static bool? _readBool(Object? value) {
    if (value is bool) return value;
    if (value is num) return value != 0;
    if (value is String) {
      if (value.toLowerCase() == 'true') return true;
      if (value.toLowerCase() == 'false') return false;
    }
    return null;
  }

  @override
  bool operator ==(Object other) {
    return other is AccessibilityBillingSettings &&
        other.masterEnabled == masterEnabled &&
        other.wechatEnabled == wechatEnabled &&
        other.alipayEnabled == alipayEnabled &&
        other.autoExtractNote == autoExtractNote;
  }

  @override
  int get hashCode =>
      Object.hash(masterEnabled, wechatEnabled, alipayEnabled, autoExtractNote);
}

class AccessibilityBillingSettingsStore {
  const AccessibilityBillingSettingsStore();

  Future<AccessibilityBillingSettings> load() async {
    final preferences = await SharedPreferences.getInstance();
    return AccessibilityBillingSettings(
      masterEnabled:
          preferences.getBool(AccessibilityBillingSettings.masterEnabledKey) ??
              false,
      wechatEnabled:
          preferences.getBool(AccessibilityBillingSettings.wechatEnabledKey) ??
              true,
      alipayEnabled:
          preferences.getBool(AccessibilityBillingSettings.alipayEnabledKey) ??
              true,
      autoExtractNote: preferences
              .getBool(AccessibilityBillingSettings.autoExtractNoteKey) ??
          true,
    );
  }

  Future<void> save(AccessibilityBillingSettings settings) async {
    final preferences = await SharedPreferences.getInstance();
    await Future.wait(<Future<bool>>[
      preferences.setBool(
        AccessibilityBillingSettings.masterEnabledKey,
        settings.masterEnabled,
      ),
      preferences.setBool(
        AccessibilityBillingSettings.wechatEnabledKey,
        settings.wechatEnabled,
      ),
      preferences.setBool(
        AccessibilityBillingSettings.alipayEnabledKey,
        settings.alipayEnabled,
      ),
      preferences.setBool(
        AccessibilityBillingSettings.autoExtractNoteKey,
        settings.autoExtractNote,
      ),
    ]);
  }
}

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'accessibility_billing_platform_service.dart';
import 'accessibility_billing_settings.dart';

const _wechatPackageName = 'com.tencent.mm';
const _alipayPackageName = 'com.eg.android.AlipayGphone';

final accessibilityBillingPlatformServiceProvider =
    Provider<AccessibilityBillingPlatformService>((ref) {
  return const AccessibilityBillingPlatformService();
});

final accessibilityBillingSettingsStoreProvider =
    Provider<AccessibilityBillingSettingsStore>((ref) {
  return const AccessibilityBillingSettingsStore();
});

class AccessibilityBillingState {
  const AccessibilityBillingState({
    this.settings = const AccessibilityBillingSettings(),
    this.serviceEnabled = false,
    this.overlayPermissionGranted = false,
    this.batteryOptimizationIgnored = false,
    this.notificationsEnabled = false,
    this.notificationPermissionRequired = false,
    this.autoStartSettingsSupported = false,
    this.manufacturer = '',
    this.diagnosticsSupported = false,
    this.adaptedApps = const <AccessibilityBillingAdaptedApp>[],
    this.usesDynamicAdaptedApps = false,
    this.ruleVersion = '',
    this.ruleUpdatedAt,
    this.ruleSource = '',
    this.ruleUpdateSupported = false,
    this.isUpdatingRules = false,
    this.isLoading = true,
    this.isSaving = false,
    this.errorMessage,
    this.ruleUpdateMessage,
  });

  final AccessibilityBillingSettings settings;
  final bool serviceEnabled;
  final bool overlayPermissionGranted;
  final bool batteryOptimizationIgnored;
  final bool notificationsEnabled;
  final bool notificationPermissionRequired;
  final bool autoStartSettingsSupported;
  final String manufacturer;
  final bool diagnosticsSupported;
  final List<AccessibilityBillingAdaptedApp> adaptedApps;
  final bool usesDynamicAdaptedApps;
  final String ruleVersion;
  final DateTime? ruleUpdatedAt;
  final String ruleSource;
  final bool ruleUpdateSupported;
  final bool isUpdatingRules;
  final bool isLoading;
  final bool isSaving;
  final String? errorMessage;
  final String? ruleUpdateMessage;

  AccessibilityBillingState copyWith({
    AccessibilityBillingSettings? settings,
    bool? serviceEnabled,
    bool? overlayPermissionGranted,
    bool? batteryOptimizationIgnored,
    bool? notificationsEnabled,
    bool? notificationPermissionRequired,
    bool? autoStartSettingsSupported,
    String? manufacturer,
    bool? diagnosticsSupported,
    List<AccessibilityBillingAdaptedApp>? adaptedApps,
    bool? usesDynamicAdaptedApps,
    String? ruleVersion,
    DateTime? ruleUpdatedAt,
    bool clearRuleUpdatedAt = false,
    String? ruleSource,
    bool? ruleUpdateSupported,
    bool? isUpdatingRules,
    bool? isLoading,
    bool? isSaving,
    String? errorMessage,
    bool clearError = false,
    String? ruleUpdateMessage,
    bool clearRuleUpdateMessage = false,
  }) {
    return AccessibilityBillingState(
      settings: settings ?? this.settings,
      serviceEnabled: serviceEnabled ?? this.serviceEnabled,
      overlayPermissionGranted:
          overlayPermissionGranted ?? this.overlayPermissionGranted,
      batteryOptimizationIgnored:
          batteryOptimizationIgnored ?? this.batteryOptimizationIgnored,
      notificationsEnabled: notificationsEnabled ?? this.notificationsEnabled,
      notificationPermissionRequired:
          notificationPermissionRequired ?? this.notificationPermissionRequired,
      autoStartSettingsSupported:
          autoStartSettingsSupported ?? this.autoStartSettingsSupported,
      manufacturer: manufacturer ?? this.manufacturer,
      diagnosticsSupported: diagnosticsSupported ?? this.diagnosticsSupported,
      adaptedApps: adaptedApps ?? this.adaptedApps,
      usesDynamicAdaptedApps:
          usesDynamicAdaptedApps ?? this.usesDynamicAdaptedApps,
      ruleVersion: ruleVersion ?? this.ruleVersion,
      ruleUpdatedAt:
          clearRuleUpdatedAt ? null : ruleUpdatedAt ?? this.ruleUpdatedAt,
      ruleSource: ruleSource ?? this.ruleSource,
      ruleUpdateSupported: ruleUpdateSupported ?? this.ruleUpdateSupported,
      isUpdatingRules: isUpdatingRules ?? this.isUpdatingRules,
      isLoading: isLoading ?? this.isLoading,
      isSaving: isSaving ?? this.isSaving,
      errorMessage: clearError ? null : errorMessage ?? this.errorMessage,
      ruleUpdateMessage: clearRuleUpdateMessage
          ? null
          : ruleUpdateMessage ?? this.ruleUpdateMessage,
    );
  }
}

class AccessibilityBillingNotifier
    extends StateNotifier<AccessibilityBillingState> {
  AccessibilityBillingNotifier({
    required AccessibilityBillingSettingsStore store,
    required AccessibilityBillingPlatformService platformService,
  })  : _store = store,
        _platformService = platformService,
        super(const AccessibilityBillingState()) {
    load();
  }

  final AccessibilityBillingSettingsStore _store;
  final AccessibilityBillingPlatformService _platformService;

  Future<void> load() async {
    state = state.copyWith(isLoading: true, clearError: true);
    try {
      final localSettings = await _store.load();
      state = state.copyWith(settings: localSettings);

      final platformSettings = await _platformService.getSettings();
      final settings = platformSettings == null
          ? localSettings
          : AccessibilityBillingSettings.fromPlatformMap(
              platformSettings.values,
              fallback: localSettings,
            );
      final serviceEnabled = await _platformService.getServiceStatus();
      final systemStatus = await _platformService.getSystemPermissionStatus();
      await _store.save(settings);

      if (!mounted) return;
      state = state.copyWith(
        settings: settings,
        serviceEnabled: serviceEnabled,
        overlayPermissionGranted:
            systemStatus['overlayPermissionGranted'] == true,
        batteryOptimizationIgnored:
            systemStatus['batteryOptimizationIgnored'] == true,
        notificationsEnabled: systemStatus['notificationsEnabled'] == true ||
            systemStatus['notificationPermissionGranted'] == true,
        notificationPermissionRequired:
            systemStatus['notificationPermissionRequired'] == true,
        autoStartSettingsSupported:
            systemStatus['autostartSettingsSupported'] == true ||
                systemStatus['autoStartSettingsSupported'] == true,
        manufacturer: systemStatus['manufacturer']?.toString() ?? '',
        diagnosticsSupported: platformSettings?.diagnosticsSupported ?? false,
        adaptedApps: platformSettings?.hasDynamicAdaptedApps == true
            ? platformSettings!.adaptedApps
            : _fallbackAdaptedApps(settings),
        usesDynamicAdaptedApps: platformSettings?.hasDynamicAdaptedApps == true,
        ruleVersion: platformSettings?.ruleVersion ?? '',
        ruleUpdatedAt: platformSettings?.ruleUpdatedAt,
        clearRuleUpdatedAt: platformSettings?.ruleUpdatedAt == null,
        ruleSource: platformSettings?.ruleSource ?? '',
        ruleUpdateSupported: platformSettings?.ruleUpdateSupported ?? false,
        isLoading: false,
        clearError: true,
      );
    } on PlatformException catch (error) {
      if (!mounted) return;
      state = state.copyWith(
        isLoading: false,
        errorMessage: error.message ?? error.code,
      );
    } catch (error) {
      if (!mounted) return;
      state = state.copyWith(isLoading: false, errorMessage: error.toString());
    }
  }

  Future<void> refreshServiceStatus() async {
    try {
      final enabled = await _platformService.getServiceStatus();
      final systemStatus = await _platformService.getSystemPermissionStatus();
      if (!mounted) return;
      state = state.copyWith(
        serviceEnabled: enabled,
        overlayPermissionGranted:
            systemStatus['overlayPermissionGranted'] == true,
        batteryOptimizationIgnored:
            systemStatus['batteryOptimizationIgnored'] == true,
        notificationsEnabled: systemStatus['notificationsEnabled'] == true ||
            systemStatus['notificationPermissionGranted'] == true,
        notificationPermissionRequired:
            systemStatus['notificationPermissionRequired'] == true,
        autoStartSettingsSupported:
            systemStatus['autostartSettingsSupported'] == true ||
                systemStatus['autoStartSettingsSupported'] == true,
        manufacturer: systemStatus['manufacturer']?.toString() ?? '',
        clearError: true,
      );
    } on PlatformException catch (error) {
      if (!mounted) return;
      state = state.copyWith(errorMessage: error.message ?? error.code);
    }
  }

  Future<void> openAccessibilitySettings() async {
    try {
      await _platformService.openAccessibilitySettings();
    } on PlatformException catch (error) {
      if (!mounted) return;
      state = state.copyWith(errorMessage: error.message ?? error.code);
    } on MissingPluginException {
      if (!mounted) return;
      state = state.copyWith(errorMessage: '当前版本暂不支持打开无障碍设置');
    }
  }

  Future<void> setMasterEnabled(bool enabled) {
    return _update(state.settings.copyWith(masterEnabled: enabled));
  }

  Future<void> setWechatEnabled(bool enabled) {
    return _update(state.settings.copyWith(wechatEnabled: enabled));
  }

  Future<void> setAlipayEnabled(bool enabled) {
    return _update(state.settings.copyWith(alipayEnabled: enabled));
  }

  Future<void> setAutoExtractNote(bool enabled) {
    return _update(state.settings.copyWith(autoExtractNote: enabled));
  }

  Future<void> setAdaptedAppEnabled(
    String packageName,
    bool enabled,
  ) async {
    if (!state.usesDynamicAdaptedApps) {
      if (packageName == _wechatPackageName) {
        return setWechatEnabled(enabled);
      }
      if (packageName == _alipayPackageName) {
        return setAlipayEnabled(enabled);
      }
      return;
    }

    final previousApps = state.adaptedApps;
    final previousSettings = state.settings;
    final updatedApps = previousApps
        .map(
          (app) => app.packageName == packageName
              ? app.copyWith(enabled: enabled)
              : app,
        )
        .toList(growable: false);
    final updatedSettings = _settingsForPackage(
      previousSettings,
      packageName,
      enabled,
    );
    state = state.copyWith(
      adaptedApps: updatedApps,
      settings: updatedSettings,
      isSaving: true,
      clearError: true,
    );
    try {
      await _store.save(updatedSettings);
      await _platformService.setRecognitionPackageEnabled(
        packageName: packageName,
        enabled: enabled,
      );
      if (!mounted) return;
      state = state.copyWith(isSaving: false);
    } catch (error) {
      await _store.save(previousSettings);
      if (!mounted) return;
      state = state.copyWith(
        adaptedApps: previousApps,
        settings: previousSettings,
        isSaving: false,
        errorMessage: error is PlatformException
            ? error.message ?? error.code
            : error.toString(),
      );
    }
  }

  Future<void> updateRecognitionRules() async {
    if (state.isUpdatingRules || !state.ruleUpdateSupported) return;
    state = state.copyWith(
      isUpdatingRules: true,
      clearError: true,
      clearRuleUpdateMessage: true,
    );
    try {
      final status = await _platformService.updateRecognitionRules();
      if (!mounted) return;
      if (status.updated == false && !status.unchanged) {
        state = state.copyWith(
          isUpdatingRules: false,
          errorMessage: status.errorMessage ?? '适配规则更新失败',
        );
        return;
      }
      state = state.copyWith(
        adaptedApps: status.adaptedApps,
        usesDynamicAdaptedApps: true,
        ruleVersion: status.ruleVersion,
        ruleUpdatedAt: status.ruleUpdatedAt,
        clearRuleUpdatedAt: status.ruleUpdatedAt == null,
        ruleSource: status.ruleSource,
        ruleUpdateSupported:
            status.ruleUpdateSupported || state.ruleUpdateSupported,
        isUpdatingRules: false,
        ruleUpdateMessage: status.unchanged ? '已是最新适配规则' : '适配规则已更新',
      );
    } catch (error) {
      if (!mounted) return;
      state = state.copyWith(
        isUpdatingRules: false,
        errorMessage: error is PlatformException
            ? error.message ?? error.code
            : error.toString(),
      );
    }
  }

  Future<void> openOverlaySettings() =>
      _openSystemSettings(_platformService.openOverlaySettings);

  Future<void> openBatteryOptimizationSettings() =>
      _openSystemSettings(_platformService.openBatteryOptimizationSettings);

  Future<void> openNotificationSettings() =>
      _openSystemSettings(_platformService.openNotificationSettings);

  Future<void> openAutoStartSettings() =>
      _openSystemSettings(_platformService.openAutoStartSettings);

  Future<void> openPaymentProtectionSettings() =>
      _openSystemSettings(_platformService.openPaymentProtectionSettings);

  Future<void> _openSystemSettings(Future<void> Function() action) async {
    try {
      await action();
    } on PlatformException catch (error) {
      if (!mounted) return;
      state = state.copyWith(errorMessage: error.message ?? error.code);
    } on MissingPluginException {
      if (!mounted) return;
      state = state.copyWith(errorMessage: '当前系统不支持打开此设置');
    }
  }

  Future<void> _update(AccessibilityBillingSettings settings) async {
    final previous = state.settings;
    final previousApps = state.adaptedApps;
    state = state.copyWith(
      settings: settings,
      adaptedApps: state.usesDynamicAdaptedApps
          ? state.adaptedApps
          : _fallbackAdaptedApps(settings),
      isSaving: true,
      clearError: true,
    );
    try {
      await _store.save(settings);
      await _platformService.updateSettings(settings.toPlatformMap());
      if (!mounted) return;
      state = state.copyWith(isSaving: false);
    } catch (error) {
      await _store.save(previous);
      if (!mounted) return;
      state = state.copyWith(
        settings: previous,
        adaptedApps: previousApps,
        isSaving: false,
        errorMessage: error is PlatformException
            ? error.message ?? error.code
            : error.toString(),
      );
    }
  }

  Future<bool> captureDiagnosticSnapshot() async {
    try {
      return await _platformService.captureDiagnosticSnapshot();
    } catch (error) {
      if (mounted) {
        state = state.copyWith(
          errorMessage: error is PlatformException
              ? error.message ?? error.code
              : error.toString(),
        );
      }
      return false;
    }
  }

  void clearError() {
    state = state.copyWith(clearError: true);
  }

  void clearRuleUpdateMessage() {
    state = state.copyWith(clearRuleUpdateMessage: true);
  }

  static List<AccessibilityBillingAdaptedApp> _fallbackAdaptedApps(
    AccessibilityBillingSettings settings,
  ) {
    return <AccessibilityBillingAdaptedApp>[
      AccessibilityBillingAdaptedApp(
        packageName: _wechatPackageName,
        displayName: '微信',
        enabled: settings.wechatEnabled,
        ruleCount: 0,
      ),
      AccessibilityBillingAdaptedApp(
        packageName: _alipayPackageName,
        displayName: '支付宝',
        enabled: settings.alipayEnabled,
        ruleCount: 0,
      ),
    ];
  }

  static AccessibilityBillingSettings _settingsForPackage(
    AccessibilityBillingSettings settings,
    String packageName,
    bool enabled,
  ) {
    if (packageName == _wechatPackageName) {
      return settings.copyWith(wechatEnabled: enabled);
    }
    if (packageName == _alipayPackageName) {
      return settings.copyWith(alipayEnabled: enabled);
    }
    return settings;
  }
}

final accessibilityBillingProvider = StateNotifierProvider<
    AccessibilityBillingNotifier, AccessibilityBillingState>((ref) {
  return AccessibilityBillingNotifier(
    store: ref.watch(accessibilityBillingSettingsStoreProvider),
    platformService: ref.watch(accessibilityBillingPlatformServiceProvider),
  );
});

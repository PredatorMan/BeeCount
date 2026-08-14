import 'dart:async';

import 'package:flutter/services.dart';

class AccessibilityBillingSavedTransaction {
  const AccessibilityBillingSavedTransaction({
    required this.ledgerId,
    required this.transactionId,
  });

  final int ledgerId;
  final int transactionId;

  static AccessibilityBillingSavedTransaction? fromMap(
    Map<Object?, Object?> map,
  ) {
    final ledgerId = _positiveInt(map['ledgerId']);
    final transactionId = _positiveInt(map['transactionId']);
    if (ledgerId == null || transactionId == null) return null;
    return AccessibilityBillingSavedTransaction(
      ledgerId: ledgerId,
      transactionId: transactionId,
    );
  }

  static int? _positiveInt(Object? value) {
    final parsed =
        value is num ? value.toInt() : int.tryParse(value?.toString() ?? '');
    return parsed != null && parsed > 0 ? parsed : null;
  }
}

class AccessibilityBillingPlatformSettings {
  const AccessibilityBillingPlatformSettings({
    required this.values,
  });

  final Map<Object?, Object?> values;

  bool get hasDynamicAdaptedApps => values['adaptedApps'] is List;

  List<AccessibilityBillingAdaptedApp> get adaptedApps =>
      AccessibilityBillingRulesStatus.fromMap(values).adaptedApps;

  String get ruleVersion => values['ruleVersion']?.toString().trim() ?? '';

  DateTime? get ruleUpdatedAt =>
      AccessibilityBillingRulesStatus.parseUpdatedAt(values['ruleUpdatedAt']);

  String get ruleSource => values['ruleSource']?.toString().trim() ?? '';

  bool get ruleUpdateSupported => values['ruleUpdateSupported'] == true;
}

class AccessibilityBillingAdaptedApp {
  const AccessibilityBillingAdaptedApp({
    required this.packageName,
    required this.displayName,
    required this.enabled,
    required this.ruleCount,
  });

  final String packageName;
  final String displayName;
  final bool enabled;
  final int ruleCount;

  AccessibilityBillingAdaptedApp copyWith({bool? enabled}) {
    return AccessibilityBillingAdaptedApp(
      packageName: packageName,
      displayName: displayName,
      enabled: enabled ?? this.enabled,
      ruleCount: ruleCount,
    );
  }

  factory AccessibilityBillingAdaptedApp.fromMap(
    Map<Object?, Object?> map,
  ) {
    final packageName = map['packageName']?.toString().trim() ?? '';
    final displayName = map['displayName']?.toString().trim();
    final rawRuleCount = map['ruleCount'];
    return AccessibilityBillingAdaptedApp(
      packageName: packageName,
      displayName: displayName == null || displayName.isEmpty
          ? packageName
          : displayName,
      enabled: map['enabled'] == true,
      ruleCount: rawRuleCount is num
          ? rawRuleCount.toInt()
          : int.tryParse(rawRuleCount?.toString() ?? '') ?? 0,
    );
  }
}

class AccessibilityBillingRulesStatus {
  const AccessibilityBillingRulesStatus({
    this.adaptedApps = const <AccessibilityBillingAdaptedApp>[],
    this.ruleVersion = '',
    this.ruleUpdatedAt,
    this.ruleSource = '',
    this.ruleUpdateSupported = false,
    this.updated,
    this.unchanged = false,
    this.errorMessage,
  });

  final List<AccessibilityBillingAdaptedApp> adaptedApps;
  final String ruleVersion;
  final DateTime? ruleUpdatedAt;
  final String ruleSource;
  final bool ruleUpdateSupported;
  final bool? updated;
  final bool unchanged;
  final String? errorMessage;

  factory AccessibilityBillingRulesStatus.fromMap(
    Map<Object?, Object?> map,
  ) {
    final rawApps = map['adaptedApps'];
    final apps = rawApps is List
        ? rawApps
            .whereType<Map>()
            .map(
              (item) => AccessibilityBillingAdaptedApp.fromMap(
                Map<Object?, Object?>.from(item),
              ),
            )
            .where((item) => item.packageName.isNotEmpty)
            .toList(growable: false)
        : const <AccessibilityBillingAdaptedApp>[];
    return AccessibilityBillingRulesStatus(
      adaptedApps: apps,
      ruleVersion: map['ruleVersion']?.toString().trim() ?? '',
      ruleUpdatedAt: parseUpdatedAt(map['ruleUpdatedAt']),
      ruleSource: map['ruleSource']?.toString().trim() ?? '',
      ruleUpdateSupported: map['ruleUpdateSupported'] == true,
      updated: map['updated'] is bool ? map['updated'] as bool : null,
      unchanged: map['unchanged'] == true,
      errorMessage: _nonEmptyString(map['error']),
    );
  }

  static String? _nonEmptyString(Object? value) {
    final text = value?.toString().trim();
    return text == null || text.isEmpty ? null : text;
  }

  static DateTime? parseUpdatedAt(Object? value) {
    if (value is DateTime) return value;
    if (value is int) {
      return value > 0 ? DateTime.fromMillisecondsSinceEpoch(value) : null;
    }
    if (value is num) {
      return value > 0
          ? DateTime.fromMillisecondsSinceEpoch(value.toInt())
          : null;
    }
    if (value is String && value.trim().isNotEmpty) {
      final normalized = value.trim();
      final milliseconds = int.tryParse(normalized);
      if (milliseconds != null) {
        return milliseconds > 0
            ? DateTime.fromMillisecondsSinceEpoch(milliseconds)
            : null;
      }
      return DateTime.tryParse(normalized);
    }
    return null;
  }
}

/// Flutter contract for the Android accessibility bookkeeping host.
class AccessibilityBillingPlatformService {
  const AccessibilityBillingPlatformService();

  static const channelName = 'com.tntlikely.beecount/accessibility_billing';
  static const MethodChannel _channel = MethodChannel(channelName);
  static final StreamController<Map<Object?, Object?>>
      _detectedTransactionsController =
      StreamController<Map<Object?, Object?>>.broadcast();
  static final StreamController<AccessibilityBillingSavedTransaction>
      _savedTransactionsController =
      StreamController<AccessibilityBillingSavedTransaction>.broadcast();
  static bool _handlerInstalled = false;

  Stream<Map<Object?, Object?>> get detectedTransactions {
    _installHandler();
    return _detectedTransactionsController.stream;
  }

  Stream<AccessibilityBillingSavedTransaction> get savedTransactions {
    _installHandler();
    return _savedTransactionsController.stream;
  }

  void _installHandler() {
    if (_handlerInstalled) return;
    _handlerInstalled = true;
    _channel.setMethodCallHandler((call) async {
      final arguments = call.arguments;
      if (arguments is! Map) return;
      final values = Map<Object?, Object?>.from(arguments);
      switch (call.method) {
        case 'onTransactionDetected':
          _detectedTransactionsController.add(values);
        case 'onTransactionSaved':
          final transaction =
              AccessibilityBillingSavedTransaction.fromMap(values);
          if (transaction != null) {
            _savedTransactionsController.add(transaction);
          }
      }
    });
  }

  Future<bool> getServiceStatus() async {
    try {
      final result = await _channel.invokeMethod<Object?>('getServiceStatus');
      return _parseServiceEnabled(result);
    } on MissingPluginException {
      return false;
    }
  }

  Future<void> openAccessibilitySettings() {
    return _channel.invokeMethod<void>('openAccessibilitySettings');
  }

  Future<Map<Object?, Object?>> getSystemPermissionStatus() async {
    try {
      return await _channel.invokeMapMethod<Object?, Object?>(
            'getPermissionStatus',
          ) ??
          const <Object?, Object?>{};
    } on MissingPluginException {
      return const <Object?, Object?>{};
    }
  }

  Future<void> openOverlaySettings() =>
      _channel.invokeMethod<void>('openOverlaySettings');

  Future<void> openBatteryOptimizationSettings() =>
      _channel.invokeMethod<void>('openBatteryOptimizationSettings');

  Future<void> requestIgnoreBatteryOptimizations() =>
      _channel.invokeMethod<void>('requestIgnoreBatteryOptimizations');

  Future<void> openNotificationSettings() =>
      _channel.invokeMethod<void>('openNotificationSettings');

  Future<void> openAutoStartSettings() =>
      _channel.invokeMethod<void>('openAutoStartSettings');

  Future<void> openPaymentProtectionSettings() =>
      _channel.invokeMethod<void>('openPaymentProtectionSettings');

  Future<void> dismissOverlay() =>
      _channel.invokeMethod<void>('dismissOverlay');

  Future<void> notifyTransactionSaved({
    required int ledgerId,
    required int transactionId,
  }) {
    return _channel.invokeMethod<void>(
      'notifyTransactionSaved',
      <String, Object?>{
        'ledgerId': ledgerId,
        'transactionId': transactionId,
      },
    );
  }

  Future<AccessibilityBillingRulesStatus> updateRecognitionRules() async {
    final result = await _channel.invokeMapMethod<Object?, Object?>(
      'updateRecognitionRules',
    );
    return AccessibilityBillingRulesStatus.fromMap(
      result ?? const <Object?, Object?>{},
    );
  }

  Future<void> setRecognitionPackageEnabled({
    required String packageName,
    required bool enabled,
  }) {
    return _channel.invokeMethod<void>(
      'setRecognitionPackageEnabled',
      <String, Object?>{
        'packageName': packageName,
        'enabled': enabled,
      },
    );
  }

  Future<AccessibilityBillingPlatformSettings?> getSettings() async {
    try {
      final result = await _channel.invokeMapMethod<Object?, Object?>(
        'getSettings',
      );
      if (result == null) return null;
      return AccessibilityBillingPlatformSettings(
        values: result,
      );
    } on MissingPluginException {
      return null;
    }
  }

  Future<void> updateSettings(Map<String, bool> settings) {
    return _channel.invokeMethod<void>('updateSettings', settings);
  }

  Future<List<Map<Object?, Object?>>> getPendingTransactions() async {
    _installHandler();
    try {
      final result = await _channel.invokeListMethod<Object?>(
        'getPendingTransactions',
      );
      return (result ?? const <Object?>[])
          .whereType<Map>()
          .map((item) => Map<Object?, Object?>.from(item))
          .toList(growable: false);
    } on MissingPluginException {
      return const [];
    }
  }

  Future<bool> acknowledgeTransaction(String id) async {
    try {
      return await _channel.invokeMethod<bool>(
            'acknowledgeTransaction',
            <String, Object?>{'id': id},
          ) ??
          false;
    } on MissingPluginException {
      return false;
    }
  }

  static bool _parseServiceEnabled(Object? value) {
    if (value is bool) return value;
    if (value is Map) {
      return value['enabled'] == true ||
          value['serviceEnabled'] == true ||
          value['isEnabled'] == true;
    }
    return false;
  }
}

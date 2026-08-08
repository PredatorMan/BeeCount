enum BillingDraftType {
  expense,
  income,
  transfer;

  static BillingDraftType fromValue(Object? value) {
    final normalized = value?.toString().trim().toLowerCase() ?? '';
    switch (normalized) {
      case 'income':
      case '收入':
        return BillingDraftType.income;
      case 'transfer':
      case '转账':
      case '轉賬':
        return BillingDraftType.transfer;
      default:
        return BillingDraftType.expense;
    }
  }
}

/// A locally editable transaction proposed by the Android accessibility layer.
///
/// Recognition fields are accepted from an Android map. BeeCount-only choices
/// remain on this object while the confirmation sheet is open; they are never
/// persisted as automatic-bookkeeping defaults.
class BillingDraft {
  static const _notProvided = Object();

  final double? amount;
  final BillingDraftType type;
  final String? merchant;
  final String? note;
  final String? paymentMethod;
  final DateTime happenedAt;
  final String? sourcePackage;
  final String? ruleId;
  final double confidence;
  final String? fingerprint;

  final int? ledgerId;
  final int? categoryId;
  final int? accountId;
  final int? toAccountId;
  final List<int> tagIds;
  final bool excludeFromStats;
  final bool excludeFromBudget;

  const BillingDraft({
    required this.amount,
    this.type = BillingDraftType.expense,
    this.merchant,
    this.note,
    this.paymentMethod,
    required this.happenedAt,
    this.sourcePackage,
    this.ruleId,
    this.confidence = 0,
    this.fingerprint,
    this.ledgerId,
    this.categoryId,
    this.accountId,
    this.toAccountId,
    this.tagIds = const [],
    this.excludeFromStats = false,
    this.excludeFromBudget = false,
  });

  factory BillingDraft.fromAndroidMap(
    Map<Object?, Object?> map, {
    DateTime Function()? now,
  }) {
    final clock = now ?? DateTime.now;
    return BillingDraft(
      amount: _parseAmount(map['amount']),
      type: BillingDraftType.fromValue(
        map['type'] ?? map['transactionType'] ?? map['transaction_type'],
      ),
      merchant: _cleanString(map['merchant']),
      note: _cleanString(map['note']),
      paymentMethod: _cleanString(
        map['paymentMethod'] ?? map['payment_method'],
      ),
      happenedAt: _parseDateTime(
            map['happenedAt'] ??
                map['happened_at'] ??
                map['time'] ??
                map['transactionTime'] ??
                map['transaction_time'] ??
                map['detectedAt'],
          ) ??
          clock(),
      sourcePackage: _cleanString(
        map['sourcePackage'] ?? map['source_package'],
      ),
      ruleId: _cleanString(map['ruleId'] ?? map['rule_id']),
      confidence: _parseConfidence(map['confidence']),
      fingerprint: _cleanString(
        map['fingerprint'] ??
            map['orderFingerprint'] ??
            map['order_fingerprint'] ??
            map['pageFingerprint'],
      ),
    );
  }

  String? get effectiveNote {
    return _cleanString(note);
  }

  bool get hasValidAmount => amount != null && amount!.isFinite && amount! > 0;

  BillingDraft copyWith({
    Object? amount = _notProvided,
    BillingDraftType? type,
    Object? merchant = _notProvided,
    Object? note = _notProvided,
    Object? paymentMethod = _notProvided,
    DateTime? happenedAt,
    Object? sourcePackage = _notProvided,
    Object? ruleId = _notProvided,
    double? confidence,
    Object? fingerprint = _notProvided,
    Object? ledgerId = _notProvided,
    Object? categoryId = _notProvided,
    Object? accountId = _notProvided,
    Object? toAccountId = _notProvided,
    List<int>? tagIds,
    bool? excludeFromStats,
    bool? excludeFromBudget,
  }) {
    return BillingDraft(
      amount: identical(amount, _notProvided) ? this.amount : amount as double?,
      type: type ?? this.type,
      merchant: identical(merchant, _notProvided)
          ? this.merchant
          : merchant as String?,
      note: identical(note, _notProvided) ? this.note : note as String?,
      paymentMethod: identical(paymentMethod, _notProvided)
          ? this.paymentMethod
          : paymentMethod as String?,
      happenedAt: happenedAt ?? this.happenedAt,
      sourcePackage: identical(sourcePackage, _notProvided)
          ? this.sourcePackage
          : sourcePackage as String?,
      ruleId: identical(ruleId, _notProvided) ? this.ruleId : ruleId as String?,
      confidence: confidence ?? this.confidence,
      fingerprint: identical(fingerprint, _notProvided)
          ? this.fingerprint
          : fingerprint as String?,
      ledgerId:
          identical(ledgerId, _notProvided) ? this.ledgerId : ledgerId as int?,
      categoryId: identical(categoryId, _notProvided)
          ? this.categoryId
          : categoryId as int?,
      accountId: identical(accountId, _notProvided)
          ? this.accountId
          : accountId as int?,
      toAccountId: identical(toAccountId, _notProvided)
          ? this.toAccountId
          : toAccountId as int?,
      tagIds: List.unmodifiable(tagIds ?? this.tagIds),
      excludeFromStats: excludeFromStats ?? this.excludeFromStats,
      excludeFromBudget: excludeFromBudget ?? this.excludeFromBudget,
    );
  }

  Map<String, Object?> toAndroidMap() => {
        'amount': amount,
        'type': type.name,
        'merchant': merchant,
        'note': note,
        'paymentMethod': paymentMethod,
        'happenedAt': happenedAt.toIso8601String(),
        'sourcePackage': sourcePackage,
        'ruleId': ruleId,
        'confidence': confidence,
        'fingerprint': fingerprint,
      };

  static double? _parseAmount(Object? value) {
    if (value is num) return value.abs().toDouble();
    final raw = _cleanString(value);
    if (raw == null) return null;
    final normalized = raw
        .replaceAll(',', '')
        .replaceAll('，', '')
        .replaceAll(RegExp(r'[^0-9.\-]'), '');
    return double.tryParse(normalized)?.abs();
  }

  static double? _parseDouble(Object? value) {
    if (value is num) return value.toDouble();
    return double.tryParse(value?.toString() ?? '');
  }

  static double _parseConfidence(Object? value) {
    final parsed = _parseDouble(value);
    if (parsed == null || !parsed.isFinite) return 0;
    return parsed.clamp(0, 1).toDouble();
  }

  static DateTime? _parseDateTime(Object? value) {
    if (value is DateTime) return value;
    if (value is int) {
      // Android may send epoch seconds or epoch milliseconds.
      final milliseconds = value.abs() < 100000000000 ? value * 1000 : value;
      return DateTime.fromMillisecondsSinceEpoch(milliseconds);
    }
    final raw = _cleanString(value);
    return raw == null ? null : DateTime.tryParse(raw);
  }

  static String? _cleanString(Object? value) {
    if (value == null) return null;
    final result = value.toString().trim();
    return result.isEmpty ? null : result;
  }
}

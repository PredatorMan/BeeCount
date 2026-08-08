import 'package:beecount/features/accessibility_billing/domain/billing_draft.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('BillingDraft.fromAndroidMap', () {
    test('parses the accessibility recognition contract', () {
      final draft = BillingDraft.fromAndroidMap({
        'amount': '¥ 1,234.50',
        'type': 'expense',
        'merchant': '示例商户',
        'note': '晚餐',
        'paymentMethod': '零钱',
        'happenedAt': '2026-08-07T19:20:30+08:00',
        'sourcePackage': 'com.tencent.mm',
        'ruleId': 'wechat.pay_success.v1',
        'confidence': 0.94,
        'fingerprint': 'hashed-order',
      });

      expect(draft.amount, 1234.5);
      expect(draft.type, BillingDraftType.expense);
      expect(draft.merchant, '示例商户');
      expect(draft.note, '晚餐');
      expect(draft.paymentMethod, '零钱');
      expect(draft.happenedAt.toUtc(), DateTime.utc(2026, 8, 7, 11, 20, 30));
      expect(draft.sourcePackage, 'com.tencent.mm');
      expect(draft.ruleId, 'wechat.pay_success.v1');
      expect(draft.confidence, 0.94);
      expect(draft.fingerprint, 'hashed-order');
      expect(draft.ledgerId, isNull);
    });

    test('accepts aliases, epoch milliseconds, and clamps confidence', () {
      final draft = BillingDraft.fromAndroidMap({
        'amount': -28,
        'type': '收入',
        'payment_method': '银行卡',
        'happened_at': 1786094400000,
        'source_package': 'com.example.pay',
        'rule_id': 'income.v1',
        'confidence': '1.4',
      });

      expect(draft.amount, 28);
      expect(draft.type, BillingDraftType.income);
      expect(draft.paymentMethod, '银行卡');
      expect(draft.happenedAt.millisecondsSinceEpoch, 1786094400000);
      expect(draft.sourcePackage, 'com.example.pay');
      expect(draft.ruleId, 'income.v1');
      expect(draft.confidence, 1);
    });

    test('parses the native payment recognition field names', () {
      final draft = BillingDraft.fromAndroidMap({
        'amount': '28.00',
        'transactionType': 'expense',
        'transactionTime': '2026-08-07T20:30:00+08:00',
        'orderFingerprint': 'hashed-order',
      });

      expect(draft.type, BillingDraftType.expense);
      expect(draft.happenedAt.toUtc(), DateTime.utc(2026, 8, 7, 12, 30));
      expect(draft.fingerprint, 'hashed-order');
    });

    test('uses supplied clock when happenedAt is missing', () {
      final fallback = DateTime(2026, 8, 7, 21, 30);
      final draft = BillingDraft.fromAndroidMap({
        'amount': '9.9',
      }, now: () => fallback);

      expect(draft.happenedAt, same(fallback));
      expect(draft.type, BillingDraftType.expense);
    });

    test('rejects non-finite confidence', () {
      final draft = BillingDraft.fromAndroidMap({
        'amount': 1,
        'confidence': 'NaN',
      });

      expect(draft.confidence, 0);
    });
  });

  test('effectiveNote stays empty when automatic note extraction is disabled',
      () {
    final draft = BillingDraft(
      amount: 12,
      merchant: '便利店',
      note: '  ',
      happenedAt: DateTime(2026, 8, 7),
    );

    expect(draft.effectiveNote, isNull);
  });

  test('copyWith can explicitly clear local selections', () {
    final original = BillingDraft(
      amount: 12,
      happenedAt: DateTime(2026, 8, 7),
      ledgerId: 3,
      categoryId: 4,
      accountId: 5,
      toAccountId: 6,
      tagIds: const [7, 8],
    );

    final changed = original.copyWith(
      ledgerId: 9,
      accountId: null,
      toAccountId: null,
      tagIds: const [8],
    );

    expect(changed.ledgerId, 9);
    expect(changed.categoryId, 4);
    expect(changed.accountId, isNull);
    expect(changed.toAccountId, isNull);
    expect(changed.tagIds, [8]);
    expect(original.accountId, 5);
  });

  test('toAndroidMap excludes BeeCount-only editing choices', () {
    final draft = BillingDraft(
      amount: 12,
      happenedAt: DateTime(2026, 8, 7),
      sourcePackage: 'com.tencent.mm',
      ledgerId: 99,
      accountId: 3,
    );

    final map = draft.toAndroidMap();
    expect(map['sourcePackage'], 'com.tencent.mm');
    expect(map.containsKey('ledgerId'), isFalse);
    expect(map.containsKey('accountId'), isFalse);
  });
}

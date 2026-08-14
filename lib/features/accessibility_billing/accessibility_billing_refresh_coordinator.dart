import 'dart:async';

import 'package:drift/drift.dart' show TableUpdate;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../providers/budget_providers.dart';
import '../../providers/calendar_providers.dart';
import '../../providers/database_providers.dart';
import '../../providers/statistics_providers.dart';
import '../../providers/sync_providers.dart';
import '../../providers/tag_providers.dart';
import '../../providers/ui_state_providers.dart';
import 'accessibility_billing_platform_service.dart';

typedef AccessibilityBillingRefreshAction = void Function(int ledgerId);

final accessibilityBillingRefreshActionProvider =
    Provider<AccessibilityBillingRefreshAction>((ref) {
  return (ledgerId) {
    ref.read(cachedTransactionsProvider.notifier).state = null;

    // The overlay owns another Drift connection. Its committed write is visible
    // to SQLite, but it cannot wake queries watched by the main connection.
    final database = ref.read(databaseProvider);
    database.notifyUpdates({
      TableUpdate.onTable(database.transactions),
      TableUpdate.onTable(database.transactionTags),
    });

    ref.invalidate(countsForLedgerProvider(ledgerId));
    ref.read(statsRefreshProvider.notifier).state++;
    ref.read(budgetRefreshProvider.notifier).state++;
    ref.read(calendarRefreshProvider.notifier).state++;
    ref.read(ledgerListRefreshProvider.notifier).state++;
    ref.read(tagListRefreshProvider.notifier).state++;
    ref.read(homeTransactionRefreshProvider.notifier).state++;
  };
});

StreamSubscription<AccessibilityBillingSavedTransaction>
    listenForAccessibilityBillingTransactionSaves(
  ProviderContainer container, {
  AccessibilityBillingPlatformService platformService =
      const AccessibilityBillingPlatformService(),
}) {
  return platformService.savedTransactions.listen(
    (transaction) => refreshAfterAccessibilityBillingSave(
      container,
      transaction,
    ),
  );
}

void refreshAfterAccessibilityBillingSave(
  ProviderContainer container,
  AccessibilityBillingSavedTransaction transaction,
) {
  container.read(accessibilityBillingRefreshActionProvider)(
    transaction.ledgerId,
  );
}

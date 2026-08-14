import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../providers/budget_providers.dart';
import '../../providers/calendar_providers.dart';
import '../../providers/statistics_providers.dart';
import '../../providers/sync_providers.dart';
import '../../providers/ui_state_providers.dart';
import 'accessibility_billing_platform_service.dart';

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
  container.invalidate(countsForLedgerProvider(transaction.ledgerId));
  container.read(statsRefreshProvider.notifier).state++;
  container.read(budgetRefreshProvider.notifier).state++;
  container.read(calendarRefreshProvider.notifier).state++;
  container.read(ledgerListRefreshProvider.notifier).state++;

  // The overlay uses another Drift connection. Recreate Home's query stream so
  // the main connection reads the newly committed row immediately.
  container.read(cachedTransactionsProvider.notifier).state = null;
  container.read(homeTransactionRefreshProvider.notifier).state++;
}

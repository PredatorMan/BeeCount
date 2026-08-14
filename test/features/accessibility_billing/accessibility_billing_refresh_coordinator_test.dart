import 'package:beecount/features/accessibility_billing/accessibility_billing_platform_service.dart';
import 'package:beecount/features/accessibility_billing/accessibility_billing_refresh_coordinator.dart';
import 'package:beecount/providers/budget_providers.dart';
import 'package:beecount/providers/calendar_providers.dart';
import 'package:beecount/providers/statistics_providers.dart';
import 'package:beecount/providers/sync_providers.dart';
import 'package:beecount/providers/ui_state_providers.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('overlay save invalidates every main-engine refresh signal', () {
    final container = ProviderContainer();
    addTearDown(container.dispose);
    container.read(cachedTransactionsProvider.notifier).state = [];

    refreshAfterAccessibilityBillingSave(
      container,
      const AccessibilityBillingSavedTransaction(
        ledgerId: 3,
        transactionId: 27,
      ),
    );

    expect(container.read(statsRefreshProvider), 1);
    expect(container.read(budgetRefreshProvider), 1);
    expect(container.read(calendarRefreshProvider), 1);
    expect(container.read(ledgerListRefreshProvider), 1);
    expect(container.read(homeTransactionRefreshProvider), 1);
    expect(container.read(cachedTransactionsProvider), isNull);
  });
}

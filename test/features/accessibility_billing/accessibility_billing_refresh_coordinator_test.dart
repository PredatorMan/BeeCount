import 'dart:async';
import 'dart:io';

import 'package:beecount/data/db.dart';
import 'package:beecount/features/accessibility_billing/accessibility_billing_platform_service.dart';
import 'package:beecount/features/accessibility_billing/accessibility_billing_refresh_coordinator.dart';
import 'package:beecount/providers/budget_providers.dart';
import 'package:beecount/providers/calendar_providers.dart';
import 'package:beecount/providers/database_providers.dart';
import 'package:beecount/providers/statistics_providers.dart';
import 'package:beecount/providers/sync_providers.dart';
import 'package:beecount/providers/tag_providers.dart';
import 'package:beecount/providers/ui_state_providers.dart';
import 'package:drift/drift.dart' show TableUpdateQuery, driftRuntimeOptions;
import 'package:drift/native.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('overlay save wakes Drift and every main-engine refresh signal',
      () async {
    final database = BeeDatabase.forTesting(NativeDatabase.memory());
    final container = ProviderContainer(
      overrides: [databaseProvider.overrideWithValue(database)],
    );
    addTearDown(() async {
      container.dispose();
      await database.close();
    });
    container.read(cachedTransactionsProvider.notifier).state = [];

    final transactionUpdate = database
        .tableUpdates(TableUpdateQuery.onTable(database.transactions))
        .first;
    final transactionTagUpdate = database
        .tableUpdates(TableUpdateQuery.onTable(database.transactionTags))
        .first;

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
    expect(container.read(tagListRefreshProvider), 1);
    expect(container.read(homeTransactionRefreshProvider), 1);
    expect(container.read(cachedTransactionsProvider), isNull);
    expect(
      (await transactionUpdate).map((update) => update.table),
      contains(database.transactions.actualTableName),
    );
    expect(
      (await transactionTagUpdate).map((update) => update.table),
      contains(database.transactionTags.actualTableName),
    );
  });

  test('main Drift watcher reloads a transaction written by overlay connection',
      () async {
    final directory = await Directory.systemTemp.createTemp(
      'beecount_accessibility_refresh_',
    );
    final file = File(
      '${directory.path}${Platform.pathSeparator}beecount.sqlite',
    );
    final previousMultipleDatabaseWarning =
        driftRuntimeOptions.dontWarnAboutMultipleDatabases;
    driftRuntimeOptions.dontWarnAboutMultipleDatabases = true;
    final mainDatabase = BeeDatabase.forTesting(NativeDatabase(file));
    final overlayDatabase = BeeDatabase.forTesting(NativeDatabase(file));
    final container = ProviderContainer(
      overrides: [databaseProvider.overrideWithValue(mainDatabase)],
    );
    final watcher = StreamIterator(
      mainDatabase.select(mainDatabase.transactions).watch(),
    );

    addTearDown(() async {
      await watcher.cancel();
      container.dispose();
      await overlayDatabase.close();
      await mainDatabase.close();
      await directory.delete(recursive: true);
      driftRuntimeOptions.dontWarnAboutMultipleDatabases =
          previousMultipleDatabaseWarning;
    });

    expect(await watcher.moveNext(), isTrue);
    expect(watcher.current, isEmpty);

    await overlayDatabase.into(overlayDatabase.transactions).insert(
          TransactionsCompanion.insert(
            ledgerId: 1,
            type: 'expense',
            amount: 39.5,
          ),
        );

    container.read(accessibilityBillingRefreshActionProvider)(1);

    expect(
      await watcher.moveNext().timeout(const Duration(seconds: 2)),
      isTrue,
    );
    expect(watcher.current, hasLength(1));
    expect(watcher.current.single.amount, 39.5);
  });
}

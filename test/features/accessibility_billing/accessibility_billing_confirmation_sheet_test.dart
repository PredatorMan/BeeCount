import 'package:beecount/data/db.dart';
import 'package:beecount/data/repositories/local/local_repository.dart';
import 'package:beecount/features/accessibility_billing/domain/billing_draft.dart';
import 'package:beecount/features/accessibility_billing/presentation/accessibility_billing_confirmation_sheet.dart';
import 'package:beecount/providers.dart';
import 'package:drift/native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late BeeDatabase database;
  late LocalRepository repository;
  late int ledgerId;
  late int accountId;
  late int diningCategoryId;

  setUp(() async {
    SharedPreferences.setMockInitialValues(<String, Object>{});
    database = BeeDatabase.forTesting(NativeDatabase.memory());
    repository = LocalRepository(database);
    ledgerId = await repository.createLedger(
      name: '家庭日常消费与旅行共同账本',
      currency: 'CNY',
    );
    accountId = await repository.createAccount(
      ledgerId: ledgerId,
      name: '中国银行储蓄卡尾号5912超长资产',
    );
    diningCategoryId = await repository.createCategory(
      name: '餐饮',
      kind: 'expense',
      sortOrder: 0,
    );
    for (final (index, name) in ['早餐', '午餐', '晚餐', '外卖'].indexed) {
      await repository.createSubCategory(
        parentId: diningCategoryId,
        name: name,
        kind: 'expense',
        sortOrder: index,
      );
    }
    for (var index = 1; index <= 20; index++) {
      await repository.createCategory(
        name: '分类${index.toString().padLeft(2, '0')}',
        kind: 'expense',
        sortOrder: index,
      );
    }
    final preferences = await SharedPreferences.getInstance();
    await preferences.setInt('default_expense_account_id', accountId);
  });

  tearDown(() async {
    await database.close();
  });

  testWidgets('常见屏幕首屏显示三排分类，且滚动不移动固定编辑区', (tester) async {
    await _setTestViewport(tester, const Size(393, 852));
    await _pumpConfirmationSheet(
      tester,
      repository: repository,
      ledgerId: ledgerId,
    );

    final categoryList = find.byKey(
      const ValueKey('accessibility-category-list'),
    );
    expect(categoryList, findsOneWidget);
    expect(find.byType(Divider), findsNothing);
    expect(find.textContaining('识别到支付方式'), findsNothing);
    expect(
      find.byWidgetPredicate(
        (widget) =>
            widget is Container &&
            widget.constraints ==
                const BoxConstraints.tightFor(width: 36, height: 4),
        description: '顶部拖动条',
      ),
      findsNothing,
    );
    expect(
      find.byWidgetPredicate(
        (widget) => widget is ColoredBox && widget.color == Colors.white,
        description: '白色面板背景',
      ),
      findsOneWidget,
    );
    expect(find.byIcon(Icons.account_balance_wallet_outlined), findsNothing);
    expect(find.byIcon(Icons.menu_book_outlined), findsNothing);
    expect(find.byIcon(Icons.schedule), findsNothing);
    final fields =
        tester.widgetList<TextField>(find.byType(TextField)).toList();
    final primary = Theme.of(tester.element(categoryList)).colorScheme.primary;
    expect(fields.first.style?.color, primary);
    expect(fields[1].style?.color, Colors.red.shade600);
    final listRect = tester.getRect(categoryList);
    expect(find.text('餐饮'), findsOneWidget);
    expect(tester.getRect(find.text('餐饮')).top,
        greaterThanOrEqualTo(listRect.top));

    final fixedBefore = _fixedControlTops(tester);
    await tester.drag(categoryList, const Offset(0, -120));
    await tester.pump();
    expect(_fixedControlTops(tester), fixedBefore);
    expect(tester.takeException(), isNull);
  });

  testWidgets('点击一级分类后在当前行下方展开四列二级分类', (tester) async {
    await _setTestViewport(tester, const Size(393, 852));
    await _pumpConfirmationSheet(
      tester,
      repository: repository,
      ledgerId: ledgerId,
    );

    expect(find.text('早餐'), findsNothing);
    await tester.tap(find.text('餐饮'));
    await tester.pumpAndSettle();

    for (final name in ['早餐', '午餐', '晚餐', '外卖']) {
      expect(find.text(name), findsOneWidget);
    }
    expect(find.byType(GridView), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('只点击有二级选项的一级分类时仍可按一级分类保存', (tester) async {
    await _setTestViewport(tester, const Size(393, 852));
    await _pumpConfirmationSheet(
      tester,
      repository: repository,
      ledgerId: ledgerId,
    );

    await tester.tap(find.text('餐饮'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, '保存'));
    await tester.pumpAndSettle();

    final transactions = await repository.getTransactionsByLedger(ledgerId);
    expect(transactions, hasLength(1));
    expect(transactions.single.categoryId, diningCategoryId);
    expect(tester.takeException(), isNull);
  });

  testWidgets('小屏和大字下超长资产、账本名不造成布局溢出', (tester) async {
    await _setTestViewport(tester, const Size(360, 720));
    await _pumpConfirmationSheet(
      tester,
      repository: repository,
      ledgerId: ledgerId,
      textScaler: const TextScaler.linear(1.3),
    );

    expect(find.text('中国银行储蓄卡尾号5912超长资产'), findsOneWidget);
    expect(find.text('家庭日常消费与旅行共同账本'), findsOneWidget);
    expect(find.text('取消'), findsOneWidget);
    expect(find.text('保存'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}

Future<void> _setTestViewport(WidgetTester tester, Size size) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = size;
  addTearDown(tester.view.resetDevicePixelRatio);
  addTearDown(tester.view.resetPhysicalSize);
}

Future<void> _pumpConfirmationSheet(
  WidgetTester tester, {
  required LocalRepository repository,
  required int ledgerId,
  TextScaler textScaler = TextScaler.noScaling,
}) async {
  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        repositoryProvider.overrideWithValue(repository),
        currentLedgerIdProvider.overrideWith((ref) => ledgerId),
      ],
      child: MaterialApp(
        theme: ThemeData.light(),
        home: MediaQuery(
          data: MediaQueryData(
            size: tester.view.physicalSize,
            padding: const EdgeInsets.only(bottom: 24),
            textScaler: textScaler,
          ),
          child: Scaffold(
            body: Align(
              alignment: Alignment.bottomCenter,
              child: AccessibilityBillingConfirmationSheet(
                initialDraft: BillingDraft(
                  amount: 92,
                  merchant: '扫二维码付款',
                  note: '扫二维码付款',
                  happenedAt: DateTime(2026, 8, 8, 12),
                  paymentMethod: '中国银行储蓄卡(5912)',
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  );
  await tester.pumpAndSettle();
}

List<double> _fixedControlTops(WidgetTester tester) {
  final fields = find.byType(TextField);
  return <double>[
    tester.getTopLeft(fields.at(0)).dy,
    tester.getTopLeft(fields.at(1)).dy,
    tester.getTopLeft(find.widgetWithText(OutlinedButton, '取消')).dy,
    tester.getTopLeft(find.widgetWithText(FilledButton, '保存')).dy,
  ];
}

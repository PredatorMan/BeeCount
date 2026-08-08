import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../ai/core/bill_info.dart';
import '../../../data/category_node.dart';
import '../../../data/db.dart';
import '../../../providers.dart';
import '../../../providers/budget_providers.dart';
import '../../../services/billing/bill_creation_service.dart';
import '../../../services/billing/category_matcher.dart';
import '../../../services/billing/post_processor.dart';
import '../../../styles/tokens.dart';
import '../../../widgets/biz/ledger_selector_dialog.dart';
import '../../../widgets/category_icon.dart';
import '../domain/billing_draft.dart';

class AccessibilityBillingConfirmationResult {
  final int transactionId;
  final BillingDraft draft;

  const AccessibilityBillingConfirmationResult({
    required this.transactionId,
    required this.draft,
  });
}

Future<AccessibilityBillingConfirmationResult?>
    showAccessibilityBillingConfirmation(
  BuildContext context, {
  required BillingDraft draft,
}) {
  return showModalBottomSheet<AccessibilityBillingConfirmationResult>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    backgroundColor: Theme.of(context).colorScheme.surface,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
    ),
    builder: (_) => AccessibilityBillingConfirmationSheet(initialDraft: draft),
  );
}

/// Confirmation UI for an accessibility-recognized transaction.
///
/// The current ledger seeds [_draft] when this sheet opens. Later selections
/// remain local to the draft, so confirming a recognized bill cannot switch
/// BeeCount's global current ledger or establish a per-app default.
class AccessibilityBillingConfirmationSheet extends ConsumerStatefulWidget {
  final BillingDraft initialDraft;

  const AccessibilityBillingConfirmationSheet({
    super.key,
    required this.initialDraft,
  });

  @override
  ConsumerState<AccessibilityBillingConfirmationSheet> createState() =>
      _AccessibilityBillingConfirmationSheetState();
}

class _AccessibilityBillingConfirmationSheetState
    extends ConsumerState<AccessibilityBillingConfirmationSheet> {
  late BillingDraft _draft;
  late final TextEditingController _amountController;
  late final TextEditingController _noteController;

  List<Category> _categories = const [];
  List<Account> _accounts = const [];
  List<Tag> _tags = const [];
  Ledger? _ledger;
  bool _loadingChoices = true;
  bool _saving = false;
  String? _error;
  int _loadGeneration = 0;

  @override
  void initState() {
    super.initState();
    _draft = widget.initialDraft.copyWith(
      ledgerId: ref.read(currentLedgerIdProvider),
    );
    _amountController = TextEditingController(
      text: _draft.amount?.toStringAsFixed(2) ?? '',
    );
    _noteController = TextEditingController(text: _draft.effectiveNote ?? '');
    Future.microtask(_reloadChoices);
  }

  @override
  void dispose() {
    _amountController.dispose();
    _noteController.dispose();
    super.dispose();
  }

  Future<void> _reloadChoices() async {
    final generation = ++_loadGeneration;
    if (mounted) setState(() => _loadingChoices = true);
    final repo = ref.read(repositoryProvider);
    final service = BillCreationService(repo);
    final ledgerId = _draft.ledgerId;
    final type = _draft.type;
    try {
      final results = await Future.wait<Object?>([
        service.getCategoriesByType(type.name),
        repo.getAllTags(),
        ledgerId == null
            ? Future<List<Account>>.value(const [])
            : repo.getAvailableAccountsForLedger(ledgerId),
        ledgerId == null
            ? Future<Ledger?>.value(null)
            : repo.getLedgerById(ledgerId),
        _defaultAccountIdFor(type),
      ]);
      if (!mounted || generation != _loadGeneration) return;
      final categories = CategoryHierarchy.getUsableCategories(
        results[0] as List<Category>,
      );
      final accounts = (results[2] as List<Account>)
          .where((account) => !account.hidden)
          .toList();
      var nextDraft = _draft;
      if (nextDraft.categoryId == null &&
          nextDraft.type != BillingDraftType.transfer) {
        final categoryId = CategoryMatcher.smartMatch(
          merchant: nextDraft.merchant,
          fullText: nextDraft.effectiveNote ?? '',
          categories: categories,
        );
        if (categoryId != null) {
          nextDraft = nextDraft.copyWith(categoryId: categoryId);
        }
      }
      if (nextDraft.accountId == null) {
        final defaultAccountId = _validAccountId(
          results[4] as int?,
          accounts,
        );
        if (defaultAccountId != null) {
          nextDraft = nextDraft.copyWith(accountId: defaultAccountId);
        }
      }
      setState(() {
        _draft = nextDraft;
        _categories = categories;
        _tags = results[1] as List<Tag>;
        _accounts = accounts;
        _ledger = results[3] as Ledger?;
        _loadingChoices = false;
      });
    } catch (error) {
      if (!mounted || generation != _loadGeneration) return;
      setState(() {
        _loadingChoices = false;
        _error = '加载记账选项失败：$error';
      });
    }
  }

  Future<int?> _defaultAccountIdFor(BillingDraftType type) async {
    try {
      return switch (type) {
        BillingDraftType.expense =>
          await ref.read(defaultExpenseAccountIdProvider.future),
        BillingDraftType.income =>
          await ref.read(defaultIncomeAccountIdProvider.future),
        BillingDraftType.transfer => null,
      };
    } catch (_) {
      return null;
    }
  }

  Future<void> _selectLedger() async {
    final selected = await showLedgerSelector(
      context,
      currentLedgerId: _draft.ledgerId,
    );
    if (!mounted || selected == null || selected == _draft.ledgerId) return;
    setState(() {
      _draft = _draft.copyWith(
        ledgerId: selected,
        accountId: null,
        toAccountId: null,
      );
      _error = null;
    });
    await _reloadChoices();
  }

  Future<void> _save() async {
    if (_saving) return;
    final amount = double.tryParse(_amountController.text.trim());
    final ledgerId = _draft.ledgerId;
    if (amount == null || amount <= 0) {
      setState(() => _error = '请输入有效金额');
      return;
    }
    if (ledgerId == null) {
      setState(() => _error = '请选择本次记账使用的账本');
      return;
    }
    if (_draft.type != BillingDraftType.transfer && _draft.categoryId == null) {
      setState(() => _error = '请选择分类');
      return;
    }
    if (_draft.type == BillingDraftType.transfer &&
        (_draft.accountId == null || _draft.toAccountId == null)) {
      setState(() => _error = '请选择转出和转入账户');
      return;
    }
    if (_draft.type == BillingDraftType.transfer &&
        _draft.accountId == _draft.toAccountId) {
      setState(() => _error = '转出和转入账户不能相同');
      return;
    }

    final savedAt = DateTime.now();
    setState(() {
      _saving = true;
      _error = null;
      _draft = _draft.copyWith(
        amount: amount,
        note: _noteController.text.trim(),
        happenedAt: savedAt,
      );
    });

    int? savedTransactionId;
    try {
      final repo = ref.read(repositoryProvider);
      final category = _findById(_categories, _draft.categoryId);
      final account = _findById(_accounts, _draft.accountId);
      final toAccount = _findById(_accounts, _draft.toAccountId);
      final selectedTags = _tags
          .where((tag) => _draft.tagIds.contains(tag.id))
          .map((tag) => tag.name)
          .toList();
      final transactionId = await BillCreationService(repo).createFromBill(
        bill: BillInfo(
          amount: amount,
          time: savedAt,
          note: _draft.effectiveNote,
          category: category?.name,
          type: _billType(_draft.type),
          account: account?.name,
          fromAccount: account?.name,
          toAccount: toAccount?.name,
          ledgerId: ledgerId,
          confidence: _draft.confidence,
        ),
        ledgerId: ledgerId,
        autoAddTags: false,
      );
      if (transactionId == null) {
        throw StateError('账单保存失败');
      }
      savedTransactionId = transactionId;
      if (_draft.tagIds.isNotEmpty) {
        await repo.updateTransactionTags(
          transactionId: transactionId,
          tagIds: _draft.tagIds.toSet().toList(),
        );
      }
      if (_draft.excludeFromStats || _draft.excludeFromBudget) {
        final created = await repo.getTransactionById(transactionId);
        if (created == null) throw StateError('无法读取已保存账单');
        await repo.updateTransaction(
          id: created.id,
          type: created.type,
          amount: created.amount,
          categoryId: created.categoryId,
          note: created.note,
          happenedAt: created.happenedAt,
          accountId: created.accountId,
          categorySyncIdOverride: created.categorySyncIdOverride,
          accountSyncIdOverride: created.accountSyncIdOverride,
          toAccountSyncIdOverride: created.toAccountSyncIdOverride,
          excludeFromStats: _draft.excludeFromStats,
          excludeFromBudget: _draft.excludeFromBudget,
        );
      }
      await PostProcessor.run(
        ref,
        ledgerId: ledgerId,
        tags: selectedTags.isNotEmpty,
      );
      ref.invalidate(countsForLedgerProvider(ledgerId));
      ref.read(budgetRefreshProvider.notifier).state++;
      if (!mounted) return;
      Navigator.of(context).pop(
        AccessibilityBillingConfirmationResult(
          transactionId: transactionId,
          draft: _draft,
        ),
      );
    } catch (error) {
      if (!mounted) return;
      if (savedTransactionId != null) {
        ref.invalidate(countsForLedgerProvider(ledgerId));
        ref.read(statsRefreshProvider.notifier).state++;
        ref.read(budgetRefreshProvider.notifier).state++;
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('账单已保存，但部分附加选项处理失败')),
        );
        Navigator.of(context).pop(
          AccessibilityBillingConfirmationResult(
            transactionId: savedTransactionId,
            draft: _draft,
          ),
        );
        return;
      }
      setState(() {
        _saving = false;
        _error = error.toString().replaceFirst('Bad state: ', '');
      });
    }
  }

  Future<void> _changeType(BillingDraftType type) async {
    setState(() {
      _draft = _draft.copyWith(
        type: type,
        categoryId: null,
        accountId: null,
        toAccountId: null,
      );
    });
    await _reloadChoices();
  }

  Future<void> _selectAccount({required bool target}) async {
    final selected = await showModalBottomSheet<int?>(
      context: context,
      useSafeArea: true,
      backgroundColor: BeeTokens.surfaceSheet(context),
      builder: (sheetContext) => ListView(
        shrinkWrap: true,
        padding: const EdgeInsets.symmetric(vertical: 8),
        children: [
          ListTile(
            title: Text(target ? '选择转入资产' : '选择资产'),
            trailing: IconButton(
              tooltip: '关闭',
              icon: const Icon(Icons.close),
              onPressed: () => Navigator.pop(sheetContext),
            ),
          ),
          if (!target && _draft.type != BillingDraftType.transfer)
            ListTile(
              leading: const Icon(Icons.remove_circle_outline),
              title: const Text('不选择资产'),
              onTap: () => Navigator.pop(sheetContext, -1),
            ),
          ..._accounts.map(
            (account) => ListTile(
              leading: Icon(
                (target ? _draft.toAccountId : _draft.accountId) == account.id
                    ? Icons.check_circle
                    : Icons.account_balance_wallet_outlined,
              ),
              title: Text(account.name),
              onTap: () => Navigator.pop(sheetContext, account.id),
            ),
          ),
        ],
      ),
    );
    if (!mounted || selected == null) return;
    setState(() {
      _draft = target
          ? _draft.copyWith(toAccountId: selected)
          : _draft.copyWith(accountId: selected == -1 ? null : selected);
    });
  }

  void _toggleReimbursement() {
    final tag = _tags.cast<Tag?>().firstWhere(
          (item) => item?.name.contains('报销') == true,
          orElse: () => null,
        );
    if (tag == null) return;
    final ids = [..._draft.tagIds];
    ids.contains(tag.id) ? ids.remove(tag.id) : ids.add(tag.id);
    setState(() => _draft = _draft.copyWith(tagIds: ids));
  }

  Future<void> _showMoreOptions() async {
    await showModalBottomSheet<void>(
      context: context,
      useSafeArea: true,
      backgroundColor: BeeTokens.surfaceSheet(context),
      builder: (sheetContext) => StatefulBuilder(
        builder: (context, setSheetState) {
          void update(VoidCallback action) {
            setState(action);
            setSheetState(() {});
          }

          return SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(20, 8, 20, 24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Row(
                  children: [
                    const Expanded(
                      child: Text('更多选项', style: TextStyle(fontSize: 18)),
                    ),
                    IconButton(
                      tooltip: '关闭',
                      onPressed: () => Navigator.pop(sheetContext),
                      icon: const Icon(Icons.close),
                    ),
                  ],
                ),
                if (_tags.isNotEmpty) ...[
                  const Text('标签'),
                  const SizedBox(height: 8),
                  Wrap(
                    spacing: 8,
                    runSpacing: 4,
                    children: _tags.map((tag) {
                      final selected = _draft.tagIds.contains(tag.id);
                      return FilterChip(
                        label: Text(tag.name),
                        selected: selected,
                        onSelected: (_) => update(() {
                          final ids = [..._draft.tagIds];
                          selected ? ids.remove(tag.id) : ids.add(tag.id);
                          _draft = _draft.copyWith(tagIds: ids);
                        }),
                      );
                    }).toList(),
                  ),
                  const SizedBox(height: 8),
                ],
                SwitchListTile.adaptive(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('不计入收支统计'),
                  value: _draft.excludeFromStats,
                  onChanged: (value) => update(
                    () => _draft = _draft.copyWith(excludeFromStats: value),
                  ),
                ),
                SwitchListTile.adaptive(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('不计入预算'),
                  value: _draft.excludeFromBudget,
                  onChanged: (value) => update(
                    () => _draft = _draft.copyWith(excludeFromBudget: value),
                  ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final size = MediaQuery.sizeOf(context);
    final bottomInset = MediaQuery.viewInsetsOf(context).bottom;
    final safePadding = MediaQuery.paddingOf(context).vertical;
    final availableHeight = math.max(
      0.0,
      size.height - bottomInset - safePadding,
    );
    final panelHeight = math.min(
      620.0,
      math.min(size.height * 0.54, availableHeight),
    );
    final redForIncome = ref.watch(incomeExpenseColorSchemeProvider);
    final amountColor = switch (_draft.type) {
      BillingDraftType.income =>
        redForIncome ? Colors.red.shade600 : Colors.green.shade700,
      BillingDraftType.expense =>
        redForIncome ? Colors.green.shade700 : Colors.red.shade600,
      BillingDraftType.transfer => theme.colorScheme.primary,
    };
    final account = _findById(_accounts, _draft.accountId);
    final targetAccount = _findById(_accounts, _draft.toAccountId);
    final reimbursement = _tags.cast<Tag?>().firstWhere(
          (item) => item?.name.contains('报销') == true,
          orElse: () => null,
        );
    final reimbursementSelected =
        reimbursement != null && _draft.tagIds.contains(reimbursement.id);

    return AnimatedPadding(
      duration: const Duration(milliseconds: 120),
      padding: EdgeInsets.only(bottom: bottomInset),
      child: SizedBox(
        height: panelHeight,
        child: Column(
          children: [
            const SizedBox(height: 8),
            Container(
              width: 36,
              height: 4,
              decoration: BoxDecoration(
                color: theme.colorScheme.outlineVariant,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 6),
              child: Row(
                children: [
                  Tooltip(
                    message: _draft.sourcePackage == null
                        ? '账单来源'
                        : _sourceLabel(_draft.sourcePackage!),
                    child: CircleAvatar(
                      radius: 18,
                      backgroundColor:
                          theme.colorScheme.primary.withValues(alpha: 0.12),
                      child: Icon(
                        _draft.sourcePackage == 'com.tencent.mm'
                            ? Icons.chat_bubble
                            : Icons.account_balance_wallet,
                        color: theme.colorScheme.primary,
                        size: 20,
                      ),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: SegmentedButton<BillingDraftType>(
                      showSelectedIcon: false,
                      segments: const [
                        ButtonSegment(
                          value: BillingDraftType.expense,
                          label: Text('支出'),
                        ),
                        ButtonSegment(
                          value: BillingDraftType.income,
                          label: Text('收入'),
                        ),
                        ButtonSegment(
                          value: BillingDraftType.transfer,
                          label: Text('转账'),
                        ),
                      ],
                      selected: {_draft.type},
                      onSelectionChanged:
                          _saving ? null : (value) => _changeType(value.single),
                    ),
                  ),
                  const SizedBox(width: 6),
                  IconButton(
                    tooltip: '反馈识别问题',
                    onPressed: _saving
                        ? null
                        : () => ScaffoldMessenger.of(context).showSnackBar(
                              const SnackBar(content: Text('可在无障碍记账设置中采集诊断快照')),
                            ),
                    icon: const Icon(Icons.feedback_outlined),
                  ),
                ],
              ),
            ),
            const Divider(height: 1),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    if (_loadingChoices)
                      const Expanded(
                        child: Align(
                          alignment: Alignment.topCenter,
                          child: LinearProgressIndicator(),
                        ),
                      )
                    else if (_draft.type != BillingDraftType.transfer)
                      Expanded(
                        child: GridView.builder(
                          padding: EdgeInsets.zero,
                          gridDelegate:
                              const SliverGridDelegateWithFixedCrossAxisCount(
                            crossAxisCount: 5,
                            mainAxisSpacing: 10,
                            crossAxisSpacing: 6,
                            childAspectRatio: 0.82,
                          ),
                          itemCount: _categories.length,
                          itemBuilder: (context, index) {
                            final category = _categories[index];
                            final selected = _draft.categoryId == category.id;
                            return InkWell(
                              borderRadius: BorderRadius.circular(8),
                              onTap: _saving
                                  ? null
                                  : () => setState(() {
                                        _draft = _draft.copyWith(
                                          categoryId: category.id,
                                        );
                                      }),
                              child: Column(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Container(
                                    width: 42,
                                    height: 42,
                                    decoration: BoxDecoration(
                                      color: selected
                                          ? theme.colorScheme.primary
                                              .withValues(alpha: 0.14)
                                          : BeeTokens.surfaceCategoryIcon(
                                              context),
                                      shape: BoxShape.circle,
                                    ),
                                    alignment: Alignment.center,
                                    child: CategoryIconWidget(
                                      category: category,
                                      size: 22,
                                      color: selected
                                          ? theme.colorScheme.primary
                                          : BeeTokens.iconCategory(context),
                                      circular: true,
                                    ),
                                  ),
                                  const SizedBox(height: 4),
                                  Text(
                                    category.name,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: theme.textTheme.bodySmall?.copyWith(
                                      color: selected
                                          ? theme.colorScheme.primary
                                          : BeeTokens.textPrimary(context),
                                    ),
                                  ),
                                ],
                              ),
                            );
                          },
                        ),
                      )
                    else
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            Text('转账资产', style: theme.textTheme.titleSmall),
                            const SizedBox(height: 8),
                            Row(
                              children: [
                                Expanded(
                                  child: _QuickOption(
                                    icon: Icons.upload_outlined,
                                    label: account?.name ?? '转出资产',
                                    selected: account != null,
                                    onTap: () => _selectAccount(target: false),
                                  ),
                                ),
                                const Padding(
                                  padding: EdgeInsets.symmetric(horizontal: 8),
                                  child: Icon(Icons.arrow_forward, size: 18),
                                ),
                                Expanded(
                                  child: _QuickOption(
                                    icon: Icons.download_outlined,
                                    label: targetAccount?.name ?? '转入资产',
                                    selected: targetAccount != null,
                                    onTap: () => _selectAccount(target: true),
                                  ),
                                ),
                              ],
                            ),
                            const SizedBox(height: 12),
                          ],
                        ),
                      ),
                    const Divider(height: 20),
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        Expanded(
                          child: TextField(
                            controller: _noteController,
                            enabled: !_saving,
                            maxLength: 100,
                            decoration: InputDecoration(
                              counterText: '',
                              hintText: _draft.merchant ?? '备注',
                              border: InputBorder.none,
                              isDense: true,
                            ),
                          ),
                        ),
                        const SizedBox(width: 12),
                        SizedBox(
                          width: 128,
                          child: TextField(
                            controller: _amountController,
                            enabled: !_saving,
                            textAlign: TextAlign.end,
                            keyboardType: const TextInputType.numberWithOptions(
                              decimal: true,
                            ),
                            style: theme.textTheme.headlineSmall?.copyWith(
                              color: amountColor,
                              fontWeight: FontWeight.w600,
                            ),
                            decoration: InputDecoration(
                              prefixText: '¥ ',
                              border: InputBorder.none,
                              isDense: true,
                            ),
                          ),
                        ),
                      ],
                    ),
                    const Divider(height: 20),
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: [
                        if (_draft.type != BillingDraftType.transfer)
                          _QuickOption(
                            icon: Icons.account_balance_wallet_outlined,
                            label: account?.name ?? '资产',
                            selected: account != null,
                            onTap: () => _selectAccount(target: false),
                          ),
                        _QuickOption(
                          icon: Icons.menu_book_outlined,
                          label: _ledger?.name ?? '选择账本',
                          selected: _ledger != null,
                          onTap: _selectLedger,
                        ),
                        const _QuickOption(
                          icon: Icons.schedule,
                          label: '现在',
                        ),
                        if (reimbursement != null)
                          _QuickOption(
                            icon: Icons.receipt_long_outlined,
                            label: '报销',
                            selected: reimbursementSelected,
                            onTap: _toggleReimbursement,
                          ),
                        _QuickOption(
                          icon: Icons.flag_outlined,
                          label: '更多',
                          selected: _draft.excludeFromStats ||
                              _draft.excludeFromBudget ||
                              _draft.tagIds.isNotEmpty,
                          onTap: _showMoreOptions,
                        ),
                      ],
                    ),
                    if (_draft.paymentMethod != null) ...[
                      const SizedBox(height: 10),
                      Text(
                        '识别到支付方式：${_draft.paymentMethod}',
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: BeeTokens.textTertiary(context),
                        ),
                      ),
                    ],
                    if (_error != null) ...[
                      const SizedBox(height: 10),
                      Text(
                        _error!,
                        style: TextStyle(color: theme.colorScheme.error),
                      ),
                    ],
                  ],
                ),
              ),
            ),
            const Divider(height: 1),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
              child: Row(
                children: [
                  Expanded(
                    child: OutlinedButton(
                      onPressed: _saving ? null : () => Navigator.pop(context),
                      child: const Text('取消'),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: FilledButton(
                      onPressed: _saving ? null : _save,
                      child: Text(_saving ? '保存中' : '保存'),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  static T? _findById<T>(List<T> values, int? id) {
    if (id == null) return null;
    for (final value in values) {
      final valueId = switch (value) {
        Category category => category.id,
        Account account => account.id,
        _ => null,
      };
      if (valueId == id) return value;
    }
    return null;
  }

  static BillType _billType(BillingDraftType type) => switch (type) {
        BillingDraftType.expense => BillType.expense,
        BillingDraftType.income => BillType.income,
        BillingDraftType.transfer => BillType.transfer,
      };

  static int? _validAccountId(int? accountId, List<Account> accounts) {
    if (accountId == null) return null;
    return accounts.any((account) => account.id == accountId)
        ? accountId
        : null;
  }

  static String _sourceLabel(String packageName) => switch (packageName) {
        'com.tencent.mm' => '微信',
        'com.eg.android.AlipayGphone' => '支付宝',
        _ => packageName,
      };
}

class _QuickOption extends StatelessWidget {
  const _QuickOption({
    required this.icon,
    required this.label,
    this.selected = false,
    this.onTap,
  });

  final IconData icon;
  final String label;
  final bool selected;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final foreground =
        selected ? theme.colorScheme.primary : BeeTokens.textSecondary(context);
    return Material(
      color: selected
          ? theme.colorScheme.primary.withValues(alpha: 0.12)
          : BeeTokens.surfaceInput(context),
      borderRadius: BorderRadius.circular(8),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(8),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, size: 17, color: foreground),
              const SizedBox(width: 6),
              ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 112),
                child: Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(color: foreground),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

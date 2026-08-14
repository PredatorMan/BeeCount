import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../providers/theme_providers.dart';
import '../../styles/tokens.dart';
import '../../widgets/biz/biz.dart';
import '../../widgets/ui/ui.dart';
import 'accessibility_billing_providers.dart';

class AccessibilityBillingSettingsPage extends ConsumerStatefulWidget {
  const AccessibilityBillingSettingsPage({super.key});

  @override
  ConsumerState<AccessibilityBillingSettingsPage> createState() =>
      _AccessibilityBillingSettingsPageState();
}

class _AccessibilityBillingSettingsPageState
    extends ConsumerState<AccessibilityBillingSettingsPage>
    with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      ref.read(accessibilityBillingProvider.notifier).refreshServiceStatus();
    }
  }

  @override
  Widget build(BuildContext context) {
    ref.listen<String?>(
      accessibilityBillingProvider.select((state) => state.errorMessage),
      (previous, next) {
        if (next == null || next == previous) return;
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(next)));
        ref.read(accessibilityBillingProvider.notifier).clearError();
      },
    );
    ref.listen<String?>(
      accessibilityBillingProvider.select(
        (state) => state.ruleUpdateMessage,
      ),
      (previous, next) {
        if (next == null || next == previous) return;
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(next)));
        ref
            .read(accessibilityBillingProvider.notifier)
            .clearRuleUpdateMessage();
      },
    );

    final state = ref.watch(accessibilityBillingProvider);
    final settings = state.settings;
    final primaryColor = ref.watch(primaryColorProvider);

    return Scaffold(
      backgroundColor: BeeTokens.scaffoldBackground(context),
      body: Column(
        children: [
          const PrimaryHeader(
            title: '无障碍记账',
            subtitle: '识别支付结果并弹出确认面板',
            showBack: true,
          ),
          Expanded(
            child: RefreshIndicator(
              onRefresh: () =>
                  ref.read(accessibilityBillingProvider.notifier).load(),
              child: ListView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.all(16),
                children: [
                  _ConfirmationNotice(primaryColor: primaryColor),
                  const SizedBox(height: 16),
                  SectionCard(
                    margin: EdgeInsets.zero,
                    child: AppListTile(
                      leading: Icons.auto_awesome_motion_outlined,
                      title: '启用无障碍记账',
                      subtitle: settings.masterEnabled ? '识别功能已开启' : '识别功能已关闭',
                      trailing: state.isLoading
                          ? const SizedBox(
                              width: 24,
                              height: 24,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : Switch.adaptive(
                              value: settings.masterEnabled,
                              activeColor: primaryColor,
                              onChanged: state.isSaving
                                  ? null
                                  : ref
                                      .read(
                                        accessibilityBillingProvider.notifier,
                                      )
                                      .setMasterEnabled,
                            ),
                    ),
                  ),
                  const SizedBox(height: 16),
                  _SectionLabel(text: '系统权限'),
                  const SizedBox(height: 8),
                  SectionCard(
                    margin: EdgeInsets.zero,
                    child: Column(
                      children: [
                        AppListTile(
                          leading: Icons.picture_in_picture_alt_outlined,
                          title: '悬浮窗权限',
                          subtitle: state.overlayPermissionGranted
                              ? '已允许在支付页面上显示记账面板'
                              : '必须开启，否则无法直接显示记账面板',
                          trailing: _permissionTrailing(
                            state.overlayPermissionGranted,
                          ),
                          onTap: ref
                              .read(accessibilityBillingProvider.notifier)
                              .openOverlaySettings,
                        ),
                        BeeTokens.cardDivider(context),
                        AppListTile(
                          leading: state.serviceEnabled
                              ? Icons.accessibility_new
                              : Icons.accessibility_new_outlined,
                          title: '无障碍服务',
                          subtitle: state.serviceEnabled
                              ? '系统服务已启用'
                              : '必须开启，用于识别当前账单页面',
                          trailing: _permissionTrailing(state.serviceEnabled),
                          onTap: ref
                              .read(accessibilityBillingProvider.notifier)
                              .openAccessibilitySettings,
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 16),
                  _SectionLabel(text: '运行稳定性'),
                  const SizedBox(height: 8),
                  SectionCard(
                    margin: EdgeInsets.zero,
                    child: Column(
                      children: [
                        AppListTile(
                          leading: Icons.restart_alt,
                          title: '自启动',
                          subtitle: state.autoStartSettingsSupported
                              ? '请在系统中允许橙汁记账自启动'
                              : '当前系统无法读取状态，请前往应用设置确认',
                          trailing: const Icon(Icons.chevron_right),
                          onTap: ref
                              .read(accessibilityBillingProvider.notifier)
                              .openAutoStartSettings,
                        ),
                        BeeTokens.cardDivider(context),
                        AppListTile(
                          leading: Icons.battery_saver_outlined,
                          title: '忽略电池优化',
                          subtitle: state.batteryOptimizationIgnored
                              ? '已设为不受限制'
                              : '需要设为不受限制，否则切换 App 或息屏后可能停止识别',
                          trailing: _permissionTrailing(
                            state.batteryOptimizationIgnored,
                          ),
                          onTap: ref
                              .read(accessibilityBillingProvider.notifier)
                              .openBatteryOptimizationSettings,
                        ),
                        BeeTokens.cardDivider(context),
                        AppListTile(
                          leading: Icons.notifications_outlined,
                          title: '通知权限',
                          subtitle: state.notificationsEnabled
                              ? '已允许橙汁记账发送通知'
                              : '可选，仅影响现有提醒；自动记账当前依赖悬浮窗',
                          trailing: _permissionTrailing(
                            state.notificationsEnabled,
                          ),
                          onTap: ref
                              .read(accessibilityBillingProvider.notifier)
                              .openNotificationSettings,
                        ),
                        BeeTokens.cardDivider(context),
                        AppListTile(
                          leading: Icons.security_outlined,
                          title: '支付保护问题',
                          subtitle: '浮窗无法显示或返回桌面后才显示时查看',
                          trailing: const Icon(Icons.chevron_right),
                          onTap: () => _showPaymentProtectionHelp(context),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 16),
                  _SectionLabel(text: '识别设置'),
                  const SizedBox(height: 8),
                  SectionCard(
                    margin: EdgeInsets.zero,
                    child: Column(
                      children: [
                        AppListTile(
                          leading: Icons.notes_outlined,
                          title: '自动提取备注',
                          subtitle: '从账单商户、商品说明和收款方备注中提取',
                          trailing: Switch.adaptive(
                            value: settings.autoExtractNote,
                            activeColor: primaryColor,
                            onChanged: state.isSaving
                                ? null
                                : ref
                                    .read(
                                      accessibilityBillingProvider.notifier,
                                    )
                                    .setAutoExtractNote,
                          ),
                        ),
                        BeeTokens.cardDivider(context),
                        AppListTile(
                          leading: Icons.system_update_alt_outlined,
                          title: '更新适配规则',
                          subtitle: _ruleStatusSubtitle(state),
                          trailing: state.isUpdatingRules
                              ? const SizedBox(
                                  width: 22,
                                  height: 22,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                )
                              : Icon(
                                  state.ruleUpdateSupported
                                      ? Icons.refresh
                                      : Icons.lock_outline,
                                ),
                          enabled: state.ruleUpdateSupported &&
                              !state.isUpdatingRules,
                          onTap: state.ruleUpdateSupported &&
                                  !state.isUpdatingRules
                              ? ref
                                  .read(
                                    accessibilityBillingProvider.notifier,
                                  )
                                  .updateRecognitionRules
                              : null,
                        ),
                        BeeTokens.cardDivider(context),
                        AppListTile(
                          leading: Icons.apps_outlined,
                          title: '已适配的 App',
                          subtitle: _adaptedAppsSummary(state),
                          trailing: const Icon(Icons.chevron_right),
                          onTap: () => _showAdaptedApps(context),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 12),
                  Text(
                    '识别数据仅在本机处理。无障碍服务只读取支付结果页面，不执行点击、输入或自动付款。',
                    style: TextStyle(
                      fontSize: 12,
                      color: BeeTokens.textTertiary(context),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _permissionTrailing(bool granted) {
    return granted
        ? Icon(Icons.check_circle, color: Colors.green.shade600)
        : const Icon(Icons.chevron_right);
  }

  Future<void> _showAdaptedApps(BuildContext context) async {
    await showModalBottomSheet<void>(
      context: context,
      useSafeArea: true,
      builder: (sheetContext) => Consumer(
        builder: (context, ref, _) {
          final state = ref.watch(accessibilityBillingProvider);
          final notifier = ref.read(accessibilityBillingProvider.notifier);
          return SafeArea(
            child: ConstrainedBox(
              constraints: BoxConstraints(
                maxHeight: MediaQuery.sizeOf(context).height * 0.72,
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  ListTile(
                    title: const Text('已适配的 App'),
                    subtitle: Text('${state.adaptedApps.length} 个应用'),
                    trailing: IconButton(
                      tooltip: '关闭',
                      icon: const Icon(Icons.close),
                      onPressed: () => Navigator.pop(sheetContext),
                    ),
                  ),
                  const Divider(height: 1),
                  if (state.adaptedApps.isEmpty)
                    const Padding(
                      padding: EdgeInsets.symmetric(
                        horizontal: 24,
                        vertical: 32,
                      ),
                      child: Text('当前规则没有可用的适配应用'),
                    )
                  else
                    Flexible(
                      child: ListView.builder(
                        shrinkWrap: true,
                        itemCount: state.adaptedApps.length,
                        itemBuilder: (context, index) {
                          final app = state.adaptedApps[index];
                          final ruleDescription = app.ruleCount > 0
                              ? '${app.ruleCount} 条识别规则 · ${app.packageName}'
                              : app.packageName;
                          return SwitchListTile.adaptive(
                            secondary: const Icon(Icons.apps_outlined),
                            title: Text(app.displayName),
                            subtitle: Text(ruleDescription),
                            value: app.enabled,
                            onChanged: state.isSaving
                                ? null
                                : (enabled) => notifier.setAdaptedAppEnabled(
                                      app.packageName,
                                      enabled,
                                    ),
                          );
                        },
                      ),
                    ),
                  const SizedBox(height: 8),
                ],
              ),
            ),
          );
        },
      ),
    );
  }

  String _adaptedAppsSummary(AccessibilityBillingState state) {
    if (state.adaptedApps.isEmpty) return '暂无适配应用';
    return state.adaptedApps.map((app) => app.displayName).join('、');
  }

  String _ruleStatusSubtitle(AccessibilityBillingState state) {
    if (state.isUpdatingRules) return '正在检查并更新适配规则';
    final source = state.ruleSource.toLowerCase() == 'builtin' ? '内置' : '远程';
    final version = state.ruleVersion.isEmpty ? '规则' : state.ruleVersion;
    final updatedAt = state.ruleUpdatedAt;
    final updatedText = updatedAt == null
        ? '尚未手动更新'
        : '更新于 ${updatedAt.year}-${_twoDigits(updatedAt.month)}-${_twoDigits(updatedAt.day)} '
            '${_twoDigits(updatedAt.hour)}:${_twoDigits(updatedAt.minute)}';
    final supportText = state.ruleUpdateSupported ? '' : ' · 当前版本不支持在线更新';
    return '$source $version · $updatedText$supportText';
  }

  String _twoDigits(int value) => value.toString().padLeft(2, '0');

  Future<void> _showPaymentProtectionHelp(BuildContext context) async {
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('支付保护问题'),
        content: const Text(
          '部分手机会阻止其他应用显示在微信或支付宝上方。若记账面板只能在返回桌面后显示，请先确认悬浮窗权限；仍无效时，在系统安全中心检查“支付保护”或“支付安全检测”。\n\n关闭支付保护会降低系统对仿冒支付界面的拦截能力，请仅在理解风险后操作。',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('关闭'),
          ),
          TextButton(
            onPressed: () {
              Navigator.pop(dialogContext);
              ref
                  .read(accessibilityBillingProvider.notifier)
                  .openPaymentProtectionSettings();
            },
            child: const Text('打开系统设置'),
          ),
        ],
      ),
    );
  }
}

class _ConfirmationNotice extends StatelessWidget {
  const _ConfirmationNotice({required this.primaryColor});

  final Color primaryColor;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: primaryColor.withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.fact_check_outlined, color: primaryColor, size: 22),
          const SizedBox(width: 10),
          const Expanded(
            child: Text(
              '检测到支付完成后，橙汁记账会弹出记账面板。金额、分类、账本和账户都可以在面板中确认或修改，确认前不会写入账单。',
              style: TextStyle(fontSize: 13, height: 1.45),
            ),
          ),
        ],
      ),
    );
  }
}

class _SectionLabel extends StatelessWidget {
  const _SectionLabel({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Text(
      text,
      style: TextStyle(
        fontSize: 13,
        fontWeight: FontWeight.w600,
        color: BeeTokens.textSecondary(context),
      ),
    );
  }
}

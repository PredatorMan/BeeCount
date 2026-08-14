import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../l10n/app_localizations.dart';
import '../../../providers/theme_providers.dart';
import '../../../theme.dart';
import '../accessibility_billing_platform_service.dart';
import '../domain/billing_draft.dart';
import 'accessibility_billing_confirmation_sheet.dart';

/// Root widget hosted by Android's translucent accessibility billing activity.
class AccessibilityBillingOverlayApp extends ConsumerWidget {
  const AccessibilityBillingOverlayApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final primary = ref.watch(primaryColorProvider);
    final base = BeeTheme.lightTheme();
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: base.copyWith(
        colorScheme: base.colorScheme.copyWith(
          primary: primary,
          surface: Colors.white,
        ),
        primaryColor: primary,
        scaffoldBackgroundColor: Colors.white,
      ),
      localizationsDelegates: const [
        AppLocalizations.delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      supportedLocales: AppLocalizations.supportedLocales,
      home: const _AccessibilityBillingOverlayHost(),
    );
  }
}

class _AccessibilityBillingOverlayHost extends StatefulWidget {
  const _AccessibilityBillingOverlayHost();

  @override
  State<_AccessibilityBillingOverlayHost> createState() =>
      _AccessibilityBillingOverlayHostState();
}

class _AccessibilityBillingOverlayHostState
    extends State<_AccessibilityBillingOverlayHost> {
  static const _platformService = AccessibilityBillingPlatformService();

  StreamSubscription<Map<Object?, Object?>>? _recognitionSubscription;
  Map<Object?, Object?>? _queuedRecognition;
  String? _activeRecognitionId;
  String? _error;
  bool _presenting = false;

  @override
  void initState() {
    super.initState();
    _recognitionSubscription =
        _platformService.detectedTransactions.listen(_enqueueRecognition);
    WidgetsBinding.instance.addPostFrameCallback((_) => _loadInitialPending());
  }

  Future<void> _loadInitialPending() async {
    try {
      final pending = await _platformService.getPendingTransactions();
      if (!mounted) return;
      if (pending.isNotEmpty) {
        _enqueueRecognition(pending.first);
      } else if (!_presenting && _queuedRecognition == null) {
        await _platformService.dismissOverlay();
      }
    } catch (error) {
      if (!mounted) return;
      setState(() => _error = error.toString());
    }
  }

  void _enqueueRecognition(Map<Object?, Object?> recognition) {
    if (!mounted) return;
    final id = _recognitionId(recognition);
    if (id != null &&
        (id == _activeRecognitionId ||
            id == _recognitionId(_queuedRecognition))) {
      return;
    }

    // Accessibility events can arrive in bursts. Keep one follow-up draft and
    // prefer the latest recognized page instead of stacking modal sheets.
    _queuedRecognition = recognition;
    if (!_presenting) {
      unawaited(_presentNextRecognition());
    }
  }

  Future<void> _presentNextRecognition() async {
    if (!mounted || _presenting) return;
    final recognition = _queuedRecognition;
    if (recognition == null) return;

    _queuedRecognition = null;
    _presenting = true;
    _activeRecognitionId = _recognitionId(recognition);
    if (_error != null) setState(() => _error = null);

    var completed = false;
    try {
      final result = await showAccessibilityBillingConfirmation(
        context,
        draft: BillingDraft.fromAndroidMap(recognition),
      );
      completed = true;
      if (result != null) {
        try {
          await _platformService.notifyTransactionSaved(
            ledgerId: result.draft.ledgerId!,
            transactionId: result.transactionId,
          );
        } catch (error) {
          // The transaction is already committed. A refresh notification must
          // never turn a successful save into a user-visible save failure.
          debugPrint(
              'Unable to notify the main app about the saved bill: $error');
        }
      }
    } catch (error) {
      if (!mounted) return;
      setState(() => _error = error.toString());
    } finally {
      if (completed && _activeRecognitionId != null) {
        try {
          await _platformService.acknowledgeTransaction(
            _activeRecognitionId!,
          );
        } catch (error) {
          if (mounted) setState(() => _error = error.toString());
        }
      }
      _activeRecognitionId = null;
      _presenting = false;
    }

    if (!mounted) return;
    if (_queuedRecognition != null) {
      unawaited(_presentNextRecognition());
    } else if (completed) {
      await _platformService.dismissOverlay();
    }
  }

  Future<void> _dismissError() async {
    if (mounted) setState(() => _error = null);
    await _platformService.dismissOverlay();
  }

  static String? _recognitionId(Map<Object?, Object?>? recognition) {
    final value = recognition?['id']?.toString();
    return value == null || value.isEmpty ? null : value;
  }

  @override
  void dispose() {
    _recognitionSubscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.transparent,
      body: _error == null
          ? const SizedBox.expand()
          : SafeArea(
              child: Align(
                alignment: Alignment.bottomCenter,
                child: Material(
                  color: Theme.of(context).colorScheme.surface,
                  borderRadius: const BorderRadius.vertical(
                    top: Radius.circular(16),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(20),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text('无法打开记账面板：$_error'),
                        const SizedBox(height: 12),
                        FilledButton(
                          onPressed: _dismissError,
                          child: const Text('关闭'),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
    );
  }
}

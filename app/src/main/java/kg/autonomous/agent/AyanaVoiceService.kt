AYANA AI — AyanaVoiceService v12.3
P0 SIDE-EFFECT RECONCILIATION INTEGRATION
TYPE: SOURCE PATCH — NOT A FULL FILE
BASE: AyanaVoiceService v12.2 CANCELLATION TERMINAL COMPLETION BUILD CANDIDATE

This patch contains only the three verified integration regions needed for the
P0 terminal-truth repair. Do not replace unrelated VoiceService logic.

======================================================================
PATCH 1 — GATEWAY: WIRE ACCESSIBILITY DISPATCH/RECONCILIATION TO KERNEL
======================================================================

Inside the appLifecycleExecutor Gateway override removeRecentTaskByLabel(...),
replace only the final accessibility.removeRecentTaskByLabel(...) call with:

                        return accessibility
                            .removeRecentTaskByLabel(
                                targetLabel = targetLabel,
                                sourcePackage = sourcePackage,
                                shouldCancel = shouldCancel,
                                tryBeginIrreversibleDispatch = { detail ->
                                    executionKernel
                                        .tryBeginIrreversibleDispatch(
                                            kind = "recents_task_removal",
                                            detail = detail
                                        )
                                },
                                onIrreversibleDispatchAccepted = { detail ->
                                    executionKernel
                                        .markIrreversibleDispatchAccepted(
                                            detail
                                        )
                                },
                                onReconciliationStarted = { detail ->
                                    executionKernel
                                        .markSideEffectReconciliationStarted(
                                            detail
                                        )
                                },
                                onReconciled = { committed, detail ->
                                    executionKernel
                                        .markSideEffectReconciled(
                                            committed = committed,
                                            detail = detail
                                        )
                                }
                            )

======================================================================
PATCH 2 — cancelCurrentCommand: KERNEL OWNS INTERRUPT FOR APP TASK REMOVAL
======================================================================

In cancelCurrentCommand(source), keep the existing calculation of:

        val deferTerminalToLifecycleExecutor =
            executionSnapshot?.terminalStatus ==
                AyanaExecutionKernel.TerminalStatus.RUNNING &&
                executionSnapshot.executor ==
                "app_task_removal_executor"

Keep cancelRequested/pendingCancelSource and requestCancel/cancel as v12.2.

Then REPLACE the block beginning with the current unconditional
currentAgentConnection?.disconnect() / currentAgentThread?.interrupt() through
the existing deferTerminalToLifecycleExecutor return with this block:

        stopCancelListenerWatchdog()
        stopCurrentAudio()
        stopSherpaListening()

        if (deferTerminalToLifecycleExecutor) {
            // P0 terminal-truth rule:
            // App task removal is a side-effect transaction. The Kernel now owns
            // whether the bound worker may be interrupted. VoiceService must not
            // independently kill the lifecycle thread after requestCancel(),
            // because accepted destructive dispatch still needs reconciliation.
            commandHistoryStore.addEvent(
                activeCommandHistoryId,
                state = "side_effect_reconciliation_wait",
                message = "STOP принят; ожидаю фактический результат Android-действия",
                details =
                    "execution_id=${cancelledExecution?.id.orEmpty()}; " +
                        "side_effect=${cancelledExecution?.sideEffectState?.name.orEmpty()}"
            )

            broadcastStatus(
                "Останавливаю выполнение…",
                STATE_EXECUTING
            )

            updateNotification(
                "Останавливаю Android-действие • проверяю фактический результат"
            )

            return
        }

        // Non-side-effect lanes preserve the old immediate resource cancellation.
        try {
            currentAgentConnection
                ?.disconnect()
        } catch (_: Exception) {
        }

        try {
            currentAgentThread
                ?.interrupt()
        } catch (_: Exception) {
        }

IMPORTANT:
- The manual disconnect()/interrupt() must be AFTER the lifecycle defer return.
- Do not add a second interrupt elsewhere in the deferred lifecycle path.

======================================================================
PATCH 3 — CLOSE TERMINAL OWNERSHIP: PRESERVE ERROR AFTER ACCEPTED DISPATCH
======================================================================

Inside handleLocalAppLifecycleRequest(...), after closeResult and technical are
available in mainHandler.post, replace the current when { ... } close-result
terminal block with:

                        val actionDispatched =
                            closeResult.optBoolean(
                                "action_dispatched",
                                false
                            )

                        val reconciliationComplete =
                            closeResult.optBoolean(
                                "reconciliation_complete",
                                !actionDispatched
                            )

                        when {
                            verified &&
                                cancelRequested -> {
                                commandHistoryStore.addEvent(
                                    activeCommandHistoryId,
                                    state = "cancel_after_commit",
                                    message = "STOP получен после подтверждённого удаления задачи",
                                    details =
                                        "semantic_terminal=SUCCESS; " +
                                            "action_dispatched=$actionDispatched; " +
                                            "reconciliation_complete=$reconciliationComplete; " +
                                            "source=${pendingCancelSource.ifBlank { "voice" }}"
                                )

                                finishActiveCommandHistory(
                                    success = true,
                                    result = "$label закрыт.",
                                    technical = technical
                                )

                                broadcastStatus(
                                    "$label закрыт.",
                                    STATE_SUCCESS
                                )

                                updateNotification(
                                    "$label закрыт • STOP получен после фактического commit"
                                )

                                resumeAfterCancellation(
                                    attempt = 0
                                )
                            }

                            terminalStatus ==
                                "CANCELLED" &&
                                cancelRequested -> {
                                finishDeferredCancellationFromExecutor(
                                    source =
                                        pendingCancelSource
                                            .ifBlank {
                                                "voice"
                                            }
                                )
                            }

                            // Critical v12.3 case:
                            // Android accepted a destructive dispatch, but the
                            // physical outcome could not be proven. A pending STOP
                            // must NOT suppress this terminal and must NOT convert
                            // it to CANCELLED.
                            terminalStatus ==
                                "ERROR" &&
                                actionDispatched &&
                                cancelRequested -> {
                                val factualMessage =
                                    closeResult.optString(
                                        "message",
                                        "Android-действие было запущено, но фактический результат не удалось подтвердить."
                                    )

                                commandHistoryStore.addEvent(
                                    activeCommandHistoryId,
                                    state = "cancel_after_dispatch_unverified",
                                    message = "STOP получен после destructive dispatch; сохраняю factual ERROR",
                                    details =
                                        "semantic_terminal=ERROR; " +
                                            "action_dispatched=true; " +
                                            "reconciliation_complete=$reconciliationComplete; " +
                                            "source=${pendingCancelSource.ifBlank { "voice" }}"
                                )

                                finishActiveCommandHistory(
                                    success = false,
                                    result = factualMessage,
                                    technical = technical
                                )

                                broadcastStatus(
                                    factualMessage,
                                    STATE_ERROR
                                )

                                updateNotification(
                                    "Результат Android-действия не подтверждён"
                                )

                                resumeAfterCancellation(
                                    attempt = 0
                                )
                            }

                            verified ->
                                respondAndResume(
                                    "$label закрыт.",
                                    silent,
                                    success = true,
                                    technical = technical
                                )

                            terminalStatus ==
                                "BLOCKED" ->
                                respondBlockedAndResume(
                                    text = closeResult.optString(
                                        "message",
                                        "Закрытие заблокировано текущим оконным режимом."
                                    ),
                                    silent = silent,
                                    technical = technical
                                )

                            terminalStatus ==
                                "UNSUPPORTED" ->
                                respondUnsupportedAndResume(
                                    text = closeResult.optString(
                                        "message",
                                        "Надёжный исполнитель закрытия сейчас недоступен."
                                    ),
                                    silent = silent,
                                    technical = technical
                                )

                            terminalStatus !=
                                "CANCELLED" ->
                                respondAndResume(
                                    text = closeResult.optString(
                                        "message",
                                        "Закрытие $label не удалось надёжно подтвердить."
                                    ),
                                    silent = silent,
                                    success = false,
                                    technical = technical
                                )
                        }

======================================================================
CROSS-FILE REQUIREMENTS
======================================================================

This patch requires:
- AyanaExecutionKernel v1.2 side-effect reconciliation API.
- AyanaAppLifecycleExecutor v1.4 side-effect reconciliation result fields.
- AgentAccessibilityService v5.9 reconciliation callbacks/fields.

Do not apply this VoiceService patch alone.

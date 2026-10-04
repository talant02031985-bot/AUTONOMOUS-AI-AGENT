package kg.autonomous.agent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock

/**
 * Android event adapter/executor for R10.27.6 Controlled Proactivity 2.0.
 *
 * It only posts local notifications for allowed decisions or a notification that
 * asks the user to confirm a next action. It never performs device mutation.
 */
class AyanaControlledProactivityRuntime(
    context: Context,
    private val eventSink: (RuntimeEvent) -> Unit = {}
) : AyanaProactivityCommandRouter.RuntimeApi {
    data class RuntimeEvent(
        val state: String,
        val ruleId: String = "",
        val details: String = ""
    )

    private val appContext = context.applicationContext
    private val store = AyanaProactivityRuleStore(appContext)
    private val engine = AyanaControlledProactivityEngine(store)
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Volatile
    private var started = false

    @Volatile
    private var batteryReceiverRegistered = false

    @Volatile
    private var networkCallbackRegistered = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            batteryEvent(intent)?.let { evaluateAndExecute(it, "battery_broadcast") }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            evaluateCurrentNetwork("network_available")
        }

        override fun onLost(network: Network) {
            evaluateCurrentNetwork("network_lost")
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            evaluateCurrentNetwork("network_capabilities_changed")
        }
    }

    fun start(): Boolean {
        if (started) return true
        createNotificationChannel()

        val batteryOk = registerBatteryReceiver()
        val networkOk = registerNetworkCallback()
        started = batteryOk || networkOk

        eventSink(
            RuntimeEvent(
                state = "proactivity_runtime_started",
                details =
                    "version=$VERSION; engine=${AyanaControlledProactivityEngine.VERSION}; " +
                        "battery_receiver=$batteryOk; network_callback=$networkOk; " +
                        "global_enabled=${store.isGloballyEnabled()}; mutation_authority=false"
            )
        )

        // Establish baseline only. Transition rules will not fire from first observation.
        currentBatteryIntent()?.let { batteryEvent(it) }?.let {
            evaluateAndExecute(it, "runtime_start_baseline")
        }
        evaluateCurrentNetwork("runtime_start_baseline")

        return started
    }

    fun stop() {
        if (batteryReceiverRegistered) {
            try {
                appContext.unregisterReceiver(batteryReceiver)
            } catch (_: Exception) {
            }
            batteryReceiverRegistered = false
        }

        if (networkCallbackRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (_: Exception) {
            }
            networkCallbackRegistered = false
        }

        started = false
        eventSink(RuntimeEvent(state = "proactivity_runtime_stopped"))
    }

    override fun setGlobalEnabled(enabled: Boolean): Boolean {
        store.setGloballyEnabled(enabled)
        eventSink(
            RuntimeEvent(
                state = if (enabled) "proactivity_global_enabled" else "proactivity_global_disabled",
                details = "explicit_user_opt_in=$enabled; mutation_authority=false"
            )
        )
        return store.isGloballyEnabled() == enabled
    }

    override fun isGlobalEnabled(): Boolean = store.isGloballyEnabled()

    override fun listRules(): List<AyanaControlledProactivityEngine.Rule> = store.list()

    override fun configureBatteryBelow(
        thresholdPercent: Int,
        cooldownMs: Long
    ): AyanaControlledProactivityEngine.Rule? =
        engine.createRule(
            ruleId = "battery_below_$thresholdPercent",
            triggerType = AyanaControlledProactivityEngine.TriggerType.BATTERY_BELOW_PERCENT,
            threshold = thresholdPercent,
            title = "AYANA • Низкий заряд",
            message = "Заряд достиг $thresholdPercent% или ниже. Подключите зарядное устройство.",
            cooldownMs = cooldownMs,
            explicitUserOptIn = true
        )

    override fun configureBatteryAbove(
        thresholdPercent: Int,
        cooldownMs: Long
    ): AyanaControlledProactivityEngine.Rule? =
        engine.createRule(
            ruleId = "battery_above_$thresholdPercent",
            triggerType = AyanaControlledProactivityEngine.TriggerType.BATTERY_ABOVE_PERCENT,
            threshold = thresholdPercent,
            title = "AYANA • Заряд достиг порога",
            message = "Заряд достиг $thresholdPercent% или выше.",
            cooldownMs = cooldownMs,
            explicitUserOptIn = true
        )

    override fun configureChargingStarted(): AyanaControlledProactivityEngine.Rule? =
        engine.createRule(
            ruleId = "charging_started",
            triggerType = AyanaControlledProactivityEngine.TriggerType.CHARGING_STARTED,
            title = "AYANA • Зарядка",
            message = "Зарядное устройство подключено.",
            explicitUserOptIn = true
        )

    override fun configureChargingStopped(): AyanaControlledProactivityEngine.Rule? =
        engine.createRule(
            ruleId = "charging_stopped",
            triggerType = AyanaControlledProactivityEngine.TriggerType.CHARGING_STOPPED,
            title = "AYANA • Зарядка",
            message = "Зарядное устройство отключено.",
            explicitUserOptIn = true
        )

    override fun configureNetworkLost(): AyanaControlledProactivityEngine.Rule? =
        engine.createRule(
            ruleId = "network_lost",
            triggerType = AyanaControlledProactivityEngine.TriggerType.NETWORK_LOST,
            title = "AYANA • Интернет",
            message = "Интернет-соединение потеряно.",
            explicitUserOptIn = true
        )

    override fun configureNetworkRestored(): AyanaControlledProactivityEngine.Rule? =
        engine.createRule(
            ruleId = "network_restored",
            triggerType = AyanaControlledProactivityEngine.TriggerType.NETWORK_RESTORED,
            title = "AYANA • Интернет",
            message = "Интернет-соединение восстановлено.",
            explicitUserOptIn = true
        )

    override fun configureWifiConnected(): AyanaControlledProactivityEngine.Rule? =
        engine.createRule(
            ruleId = "wifi_connected",
            triggerType = AyanaControlledProactivityEngine.TriggerType.WIFI_CONNECTED,
            title = "AYANA • Wi-Fi",
            message = "Подтверждено подключение к Wi-Fi с доступом в интернет.",
            explicitUserOptIn = true
        )

    override fun configureCellularActive(): AyanaControlledProactivityEngine.Rule? =
        engine.createRule(
            ruleId = "cellular_active",
            triggerType = AyanaControlledProactivityEngine.TriggerType.CELLULAR_ACTIVE,
            title = "AYANA • Мобильная сеть",
            message = "Активна мобильная сеть с подтверждённым доступом в интернет.",
            explicitUserOptIn = true
        )

    fun setRuleEnabled(ruleId: String, enabled: Boolean): Boolean =
        engine.setRuleEnabled(ruleId, enabled)?.enabled == enabled

    fun deleteRule(ruleId: String): Boolean = store.delete(ruleId)

    fun simulateRule(ruleId: String): AyanaControlledProactivityEngine.Decision? {
        val rule = store.get(ruleId) ?: return null
        val now = System.currentTimeMillis()
        val event = when (rule.triggerType) {
            AyanaControlledProactivityEngine.TriggerType.BATTERY_BELOW_PERCENT ->
                AyanaControlledProactivityEngine.Event.Battery(
                    percent = (rule.threshold ?: 20).coerceIn(1, 99),
                    charging = false,
                    observedAtMs = now
                )

            AyanaControlledProactivityEngine.TriggerType.BATTERY_ABOVE_PERCENT ->
                AyanaControlledProactivityEngine.Event.Battery(
                    percent = (rule.threshold ?: 80).coerceIn(1, 99),
                    charging = true,
                    observedAtMs = now
                )

            AyanaControlledProactivityEngine.TriggerType.CHARGING_STARTED ->
                AyanaControlledProactivityEngine.Event.Battery(50, true, now)

            AyanaControlledProactivityEngine.TriggerType.CHARGING_STOPPED ->
                AyanaControlledProactivityEngine.Event.Battery(50, false, now)

            AyanaControlledProactivityEngine.TriggerType.NETWORK_LOST ->
                AyanaControlledProactivityEngine.Event.Network(false, false, "none", now)

            AyanaControlledProactivityEngine.TriggerType.NETWORK_RESTORED ->
                AyanaControlledProactivityEngine.Event.Network(true, true, "wifi", now)

            AyanaControlledProactivityEngine.TriggerType.WIFI_CONNECTED ->
                AyanaControlledProactivityEngine.Event.Network(true, true, "wifi", now)

            AyanaControlledProactivityEngine.TriggerType.CELLULAR_ACTIVE ->
                AyanaControlledProactivityEngine.Event.Network(true, true, "cellular", now)

            AyanaControlledProactivityEngine.TriggerType.REMINDER_DUE ->
                AyanaControlledProactivityEngine.Event.Reminder(
                    ruleId = rule.ruleId,
                    dueAtMs = now,
                    occurrenceKey = "simulation-$now",
                    observedAtMs = now
                )
        }

        return engine.simulate(ruleId, event)
    }

    fun testRuleNotification(ruleId: String): Boolean {
        val decision = simulateRule(ruleId) ?: return false
        if (!decision.shouldExecute || decision.deviceMutationAuthority) return false
        val id = notificationId("test_" + decision.ruleId)
        val posted = postDecisionNotification(decision, notificationIdOverride = id)
        if (!posted) return false

        SystemClock.sleep(120L)
        val verified = isNotificationActive(id)
        cancelNotification(id)
        return verified
    }

    override fun selfTest(): Boolean {
        if (!engine.contractSelfTest()) return false
        if (AyanaControlledProactivityEngine.MAX_RULES < 8) return false
        if (store.list().size > AyanaControlledProactivityEngine.MAX_RULES) return false
        return true
    }

    override fun compactStatus(): String =
        "runtime_version=$VERSION; started=$started; battery_receiver=$batteryReceiverRegistered; " +
            "network_callback=$networkCallbackRegistered; ${store.snapshotSummary()}; mutation_authority=false"

    private fun evaluateAndExecute(
        event: AyanaControlledProactivityEngine.Event,
        source: String
    ) {
        val decisions = try {
            engine.evaluate(event)
        } catch (error: Exception) {
            eventSink(
                RuntimeEvent(
                    state = "proactivity_rule_evaluation_error",
                    details = "source=$source; error=${error.message.orEmpty().take(160)}"
                )
            )
            return
        }

        decisions.forEach { decision ->
            eventSink(
                RuntimeEvent(
                    state = "proactive_rule_evaluated",
                    ruleId = decision.ruleId,
                    details = decisionDetails(decision, source)
                )
            )

            if (!decision.shouldExecute) {
                eventSink(
                    RuntimeEvent(
                        state = "proactive_action_blocked",
                        ruleId = decision.ruleId,
                        details = decisionDetails(decision, source)
                    )
                )
                return@forEach
            }

            eventSink(
                RuntimeEvent(
                    state = "proactive_condition_verified",
                    ruleId = decision.ruleId,
                    details = decisionDetails(decision, source)
                )
            )

            eventSink(
                RuntimeEvent(
                    state = "proactive_action_requested",
                    ruleId = decision.ruleId,
                    details = decisionDetails(decision, source)
                )
            )

            val notificationId = notificationId(decision.ruleId)
            val posted =
                postDecisionNotification(
                    decision = decision,
                    notificationIdOverride = notificationId
                )
            val verified =
                posted &&
                    Build.VERSION.SDK_INT >= 23 &&
                    isNotificationActive(notificationId)

            eventSink(
                RuntimeEvent(
                    state = if (verified) "proactive_action_verified" else "proactive_action_error",
                    ruleId = decision.ruleId,
                    details =
                        decisionDetails(decision, source) +
                            "; notification_posted=$posted; notification_verified=$verified"
                )
            )
        }
    }

    private fun registerBatteryReceiver(): Boolean {
        if (batteryReceiverRegistered) return true
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        batteryReceiverRegistered = try {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(
                    batteryReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(batteryReceiver, filter)
            }
            true
        } catch (_: Exception) {
            false
        }
        return batteryReceiverRegistered
    }

    private fun registerNetworkCallback(): Boolean {
        if (networkCallbackRegistered) return true
        networkCallbackRegistered = try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
        return networkCallbackRegistered
    }

    private fun currentBatteryIntent(): Intent? =
        try {
            appContext.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
        } catch (_: Exception) {
            null
        }

    private fun batteryEvent(
        intent: Intent
    ): AyanaControlledProactivityEngine.Event.Battery? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val percent = ((level * 100f) / scale).toInt().coerceIn(0, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging =
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugged != 0

        return AyanaControlledProactivityEngine.Event.Battery(
            percent = percent,
            charging = charging,
            observedAtMs = System.currentTimeMillis()
        )
    }

    private fun evaluateCurrentNetwork(source: String) {
        val event = currentNetworkEvent() ?: return
        evaluateAndExecute(event, source)
    }

    private fun currentNetworkEvent(): AyanaControlledProactivityEngine.Event.Network? {
        return try {
            val network = connectivityManager.activeNetwork
            if (network == null) {
                AyanaControlledProactivityEngine.Event.Network(
                    connected = false,
                    validated = false,
                    transport = "none",
                    observedAtMs = System.currentTimeMillis()
                )
            } else {
                val caps = connectivityManager.getNetworkCapabilities(network)
                if (caps == null) {
                    AyanaControlledProactivityEngine.Event.Network(
                        connected = false,
                        validated = false,
                        transport = "unknown",
                        observedAtMs = System.currentTimeMillis()
                    )
                } else {
                    val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    AyanaControlledProactivityEngine.Event.Network(
                        connected = internet,
                        validated = validated,
                        transport = transportName(caps),
                        observedAtMs = System.currentTimeMillis()
                    )
                }
            }
        } catch (_: SecurityException) {
            eventSink(
                RuntimeEvent(
                    state = "proactivity_network_access_blocked",
                    details = "reason=access_network_state_permission_missing"
                )
            )
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun transportName(caps: NetworkCapabilities): String =
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }

    private fun postDecisionNotification(
        decision: AyanaControlledProactivityEngine.Decision,
        notificationIdOverride: Int? = null
    ): Boolean {
        if (!canPostNotifications()) return false

        val launchIntent = appContext.packageManager
            .getLaunchIntentForPackage(appContext.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                appContext,
                decision.ruleId.hashCode(),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
            )
        }

        val text =
            if (decision.actionType == AyanaControlledProactivityEngine.ActionType.REQUIRE_CONFIRMATION) {
                decision.message + " Откройте AYANA для подтверждения действия."
            } else {
                decision.message
            }

        val builder =
            if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(appContext, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(appContext)
            }

        val notification = builder
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(decision.title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .apply {
                if (contentIntent != null) setContentIntent(contentIntent)
            }
            .build()

        return try {
            val manager = appContext.getSystemService(NotificationManager::class.java)
            manager.notify(notificationIdOverride ?: notificationId(decision.ruleId), notification)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isNotificationActive(notificationId: Int): Boolean {
        if (Build.VERSION.SDK_INT < 23) return false
        return try {
            val manager = appContext.getSystemService(NotificationManager::class.java)
            manager.activeNotifications.any { it.id == notificationId }
        } catch (_: Exception) {
            false
        }
    }

    private fun cancelNotification(notificationId: Int) {
        try {
            val manager = appContext.getSystemService(NotificationManager::class.java)
            manager.cancel(notificationId)
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val manager = appContext.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AYANA • Проактивность",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Явно включённые пользователем проактивные уведомления AYANA"
            }
            manager.createNotificationChannel(channel)
        } catch (_: Exception) {
        }
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    private fun notificationId(ruleId: String): Int =
        NOTIFICATION_ID_BASE + (ruleId.hashCode() and 0x7FFF)

    private fun decisionDetails(
        decision: AyanaControlledProactivityEngine.Decision,
        source: String
    ): String =
        "runtime_version=$VERSION; engine_version=${AyanaControlledProactivityEngine.VERSION}; " +
            "source=$source; rule_id=${decision.ruleId}; trigger_type=${decision.triggerType}; " +
            "matched_value=${decision.matchedValue.take(180)}; action_type=${decision.actionType}; " +
            "authority_level=${decision.authorityLevel}; explicit_opt_in=${decision.explicitUserOptIn}; " +
            "terminal_state=${decision.terminalState}; reason=${decision.reason}; " +
            "cooldown_remaining_ms=${decision.cooldownRemainingMs}; mutation_authority=false"

    companion object {
        const val VERSION = "1.1"
        const val CHANNEL_ID = "ayana_controlled_proactivity_v2"
        private const val NOTIFICATION_ID_BASE = 19000
    }
}

package kg.autonomous.agent

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/**
 * AYANA Notification Listener v1.0 — LOCAL NOTIFICATION READ TRUTH.
 *
 * Read-only device capability. It never opens Settings as a substitute for
 * reading notifications and never returns SUCCESS merely because notification
 * access settings exist. Recent notification text is stored locally in AYANA's
 * private SharedPreferences so "последние уведомления" can include notifications
 * that disappeared from the active shade after AYANA observed them.
 */
class AyanaNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        try {
            activeNotifications
                ?.forEach { remember(it) }
        } catch (_: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        if (instance === this) {
            instance = null
        }
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) {
            remember(sbn)
        }
    }

    private fun remember(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) {
            return
        }

        val item = snapshotNotification(this, sbn)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        synchronized(historyLock) {
            val existing = try {
                JSONArray(prefs.getString(KEY_HISTORY, "[]") ?: "[]")
            } catch (_: Exception) {
                JSONArray()
            }

            val merged = ArrayList<JSONObject>()
            merged.add(item)

            for (index in 0 until existing.length()) {
                val old = existing.optJSONObject(index) ?: continue
                if (old.optString("key") == item.optString("key")) {
                    continue
                }
                merged.add(old)
                if (merged.size >= MAX_STORED) {
                    break
                }
            }

            val output = JSONArray()
            merged
                .sortedByDescending { it.optLong("post_time", 0L) }
                .take(MAX_STORED)
                .forEach { output.put(it) }

            prefs.edit()
                .putString(KEY_HISTORY, output.toString())
                .apply()
        }
    }

    companion object {

        @Volatile
        private var instance: AyanaNotificationListenerService? = null

        private val historyLock = Any()

        private const val PREFS_NAME = "ayana_notification_history_v1"
        private const val KEY_HISTORY = "recent_notifications"
        private const val MAX_STORED = 40

        fun isAccessGranted(context: Context): Boolean {
            return try {
                val component =
                    ComponentName(
                        context,
                        AyanaNotificationListenerService::class.java
                    )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    val manager =
                        context.getSystemService(Context.NOTIFICATION_SERVICE)
                            as? NotificationManager

                    manager
                        ?.isNotificationListenerAccessGranted(component)
                        ?: false
                } else {
                    // API 26 fallback. NotificationManager gained the direct
                    // access-check API in API 27.
                    val enabled =
                        Settings.Secure.getString(
                            context.contentResolver,
                            "enabled_notification_listeners"
                        ).orEmpty()

                    enabled
                        .split(':')
                        .any { flattened ->
                            ComponentName
                                .unflattenFromString(flattened) == component
                        }
                }
            } catch (_: Exception) {
                false
            }
        }

        fun isConnected(): Boolean = instance != null

        fun requestRebindIfNeeded(context: Context) {
            if (!isAccessGranted(context) || instance != null) {
                return
            }
            try {
                NotificationListenerService.requestRebind(
                    ComponentName(
                        context,
                        AyanaNotificationListenerService::class.java
                    )
                )
            } catch (_: Exception) {
            }
        }

        fun readRecent(
            context: Context,
            limit: Int = 8,
            appFilter: String? = null
        ): JSONObject {
            if (!isAccessGranted(context)) {
                return JSONObject()
                    .put("success", false)
                    .put("terminal_status", "BLOCKED")
                    .put("reason", "notification_listener_access_required")
                    .put(
                        "message",
                        "У AYANA нет доступа к чтению уведомлений. Разрешите доступ к уведомлениям для AYANA AI, затем повторите команду."
                    )
            }

            requestRebindIfNeeded(context)

            var live = instance

            // A newly granted listener can need a short system rebind. Wait only
            // inside this explicit read command and keep the wait bounded; never
            // claim an empty notification list while there is no factual listener.
            if (live == null) {
                val deadline =
                    System.currentTimeMillis() +
                        900L

                while (
                    live == null &&
                    System.currentTimeMillis() < deadline
                ) {
                    try {
                        Thread.sleep(60L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                    live = instance
                }
            }

            val collected = LinkedHashMap<String, JSONObject>()

            if (live != null) {
                try {
                    live.activeNotifications
                        ?.sortedByDescending { it.postTime }
                        ?.forEach { sbn ->
                            if (sbn.packageName != context.packageName) {
                                val item = snapshotNotification(context, sbn)
                                collected[item.optString("key")] = item
                            }
                        }
                } catch (_: Exception) {
                }
            }

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val stored = try {
                JSONArray(prefs.getString(KEY_HISTORY, "[]") ?: "[]")
            } catch (_: Exception) {
                JSONArray()
            }

            for (index in 0 until stored.length()) {
                val item = stored.optJSONObject(index) ?: continue
                val key = item.optString("key")
                if (key.isNotBlank() && !collected.containsKey(key)) {
                    collected[key] = item
                }
            }

            val normalizedFilter =
                appFilter
                    ?.trim()
                    ?.lowercase()
                    ?.replace('ё', 'е')
                    ?.takeIf { it.isNotBlank() }

            val selected = collected.values
                .asSequence()
                .filter { item ->
                    val title = item.optString("title").trim()
                    val text = item.optString("text").trim()
                    val subText = item.optString("sub_text").trim()

                    // Do not surface icon-only/system bookkeeping entries as an
                    // empty numbered line. They remain in private history and can
                    // become useful if Android later posts text for the same key.
                    if (title.isBlank() && text.isBlank() && subText.isBlank()) {
                        return@filter false
                    }

                    if (normalizedFilter == null) {
                        return@filter true
                    }

                    val haystack =
                        listOf(
                            item.optString("app"),
                            item.optString("package"),
                            title
                        )
                            .joinToString(" ")
                            .lowercase()
                            .replace('ё', 'е')

                    haystack.contains(normalizedFilter)
                }
                .sortedByDescending { it.optLong("post_time", 0L) }
                .take(limit.coerceIn(1, 20))
                .map { source ->
                    // Data-minimised result for command/history transport. The
                    // listener's private cache may hold more text, but ordinary
                    // "show notifications" never injects a whole email body into
                    // command history.
                    JSONObject(source.toString()).apply {
                        put("title", optString("title").take(180))
                        put("text", optString("text").replace(Regex("\\s+"), " ").trim().take(280))
                        put("sub_text", optString("sub_text").take(120))
                    }
                }
                .toList()

            if (live == null && selected.isEmpty()) {
                return JSONObject()
                    .put("success", false)
                    .put("terminal_status", "BLOCKED")
                    .put("reason", "notification_listener_rebinding")
                    .put(
                        "message",
                        "Доступ к уведомлениям разрешён, но системный listener AYANA ещё не подключён. Повторите команду после переподключения сервиса."
                    )
            }

            val array = JSONArray()
            selected.forEach { array.put(it) }

            return JSONObject()
                .put("success", true)
                .put("listener_connected", live != null)
                .put("count", selected.size)
                .put("notifications", array)
                .put(
                    "message",
                    if (selected.isEmpty()) {
                        "Последних уведомлений, доступных AYANA, сейчас нет."
                    } else {
                        "Получены последние уведомления: ${selected.size}."
                    }
                )
        }

        private fun snapshotNotification(
            context: Context,
            sbn: StatusBarNotification
        ): JSONObject {
            val notification = sbn.notification
            val extras = notification.extras

            fun extraText(key: String): String =
                extras?.getCharSequence(key)?.toString()?.trim().orEmpty()

            val title = extraText(Notification.EXTRA_TITLE)
            val bigText = extraText(Notification.EXTRA_BIG_TEXT)
            val text = bigText.ifBlank { extraText(Notification.EXTRA_TEXT) }
            val subText = extraText(Notification.EXTRA_SUB_TEXT)

            val appLabel = try {
                val pm = context.packageManager
                val info = pm.getApplicationInfo(sbn.packageName, 0)
                pm.getApplicationLabel(info).toString()
            } catch (_: Exception) {
                sbn.packageName
            }

            return JSONObject()
                .put("key", sbn.key.orEmpty())
                .put("package", sbn.packageName.orEmpty())
                .put("app", appLabel)
                .put("title", title.take(300))
                .put("text", text.take(1200))
                .put("sub_text", subText.take(300))
                .put("post_time", sbn.postTime)
                .put("ongoing", sbn.isOngoing)
        }
    }
}

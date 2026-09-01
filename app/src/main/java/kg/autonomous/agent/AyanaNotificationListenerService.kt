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
 * AYANA Notification Listener v1.2 — PRIVACY PROJECTION BEFORE TRANSPORT.
 *
 * Preserves the local read-only notification capability and private recent cache.
 * A caller must choose one of three transport projections:
 * - app_names_only: app/package metadata only; notification extras are not read for live items;
 * - titles: app + title only; body/sub-text are never transported;
 * - full: ordinary data-minimised title/body snippets.
 *
 * Projection happens before the JSONObject result leaves this service helper, so
 * restricted requests cannot leak notification bodies into VoiceService rendering,
 * execution events, command History, or later presentation layers.
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

        const val PROJECTION_APP_NAMES_ONLY = "app_names_only"
        const val PROJECTION_TITLES = "titles"
        const val PROJECTION_FULL = "full"

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
            appFilter: String? = null,
            projection: String = PROJECTION_FULL
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

            val normalizedProjection =
                normalizeProjection(projection)

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
                                val item =
                                    snapshotNotificationForProjection(
                                        context = context,
                                        sbn = sbn,
                                        projection = normalizedProjection
                                    )
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
                val source = stored.optJSONObject(index) ?: continue
                val projected =
                    projectStoredNotification(
                        source = source,
                        projection = normalizedProjection
                    )
                val key = projected.optString("key")
                if (key.isNotBlank() && !collected.containsKey(key)) {
                    collected[key] = projected
                }
            }

            val normalizedFilter =
                appFilter
                    ?.trim()
                    ?.lowercase()
                    ?.replace('ё', 'е')
                    ?.takeIf { it.isNotBlank() }

            val filtered =
                collected.values
                    .asSequence()
                    .filter { item ->
                        val app = item.optString("app").trim()
                        val packageName = item.optString("package").trim()

                        if (app.isBlank() && packageName.isBlank()) {
                            return@filter false
                        }

                        if (
                            normalizedProjection == PROJECTION_FULL
                        ) {
                            val title = item.optString("title").trim()
                            val text = item.optString("text").trim()
                            val subText = item.optString("sub_text").trim()

                            // Preserve v1.1 behavior for full reads: suppress
                            // icon-only/system bookkeeping entries.
                            if (
                                title.isBlank() &&
                                text.isBlank() &&
                                subText.isBlank()
                            ) {
                                return@filter false
                            }
                        }

                        if (normalizedFilter == null) {
                            return@filter true
                        }

                        // An app/source filter is resolved only against app identity.
                        // Never inspect notification title/body merely to decide
                        // whether an app-filtered query matches.
                        val haystack =
                            listOf(
                                app,
                                packageName
                            )
                                .joinToString(" ")
                                .lowercase()
                                .replace('ё', 'е')

                        haystack.contains(normalizedFilter)
                    }
                    .sortedByDescending { it.optLong("post_time", 0L) }

            val boundedLimit =
                limit.coerceIn(1, 20)

            val selected =
                if (
                    normalizedProjection == PROJECTION_APP_NAMES_ONLY
                ) {
                    // Projection semantics are app-centric, not notification-centric.
                    // Return each source application at most once, most recent first.
                    filtered
                        .distinctBy { item ->
                            item.optString("package")
                                .trim()
                                .lowercase()
                                .ifBlank {
                                    item.optString("app")
                                        .trim()
                                        .lowercase()
                                }
                        }
                        .take(boundedLimit)
                        .map { source ->
                            JSONObject()
                                .put("key", source.optString("key"))
                                .put("package", source.optString("package"))
                                .put("app", source.optString("app"))
                                .put("post_time", source.optLong("post_time", 0L))
                                .put("ongoing", source.optBoolean("ongoing", false))
                        }
                        .toList()
                } else {
                    filtered
                        .take(boundedLimit)
                        .map { source ->
                            projectStoredNotification(
                                source = source,
                                projection = normalizedProjection
                            )
                        }
                        .toList()
                }

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
                .put("projection", normalizedProjection)
                .put("count", selected.size)
                .put("notifications", array)
                .put(
                    "message",
                    if (selected.isEmpty()) {
                        if (normalizedProjection == PROJECTION_APP_NAMES_ONLY) {
                            "Приложений с последними уведомлениями, доступными AYANA, сейчас нет."
                        } else {
                            "Последних уведомлений, доступных AYANA, сейчас нет."
                        }
                    } else {
                        if (normalizedProjection == PROJECTION_APP_NAMES_ONLY) {
                            "Получены приложения с последними уведомлениями: ${selected.size}."
                        } else {
                            "Получены последние уведомления: ${selected.size}."
                        }
                    }
                )
        }

        private fun normalizeProjection(
            value: String
        ): String =
            when (
                value
                    .trim()
                    .lowercase()
            ) {
                PROJECTION_APP_NAMES_ONLY ->
                    PROJECTION_APP_NAMES_ONLY

                PROJECTION_TITLES ->
                    PROJECTION_TITLES

                else ->
                    PROJECTION_FULL
            }

        private fun snapshotNotificationForProjection(
            context: Context,
            sbn: StatusBarNotification,
            projection: String
        ): JSONObject {
            return when (projection) {
                PROJECTION_APP_NAMES_ONLY ->
                    notificationMetadata(
                        context = context,
                        sbn = sbn
                    )

                PROJECTION_TITLES -> {
                    val metadata =
                        notificationMetadata(
                            context = context,
                            sbn = sbn
                        )
                    val title =
                        sbn.notification
                            .extras
                            ?.getCharSequence(Notification.EXTRA_TITLE)
                            ?.toString()
                            ?.trim()
                            .orEmpty()

                    metadata.put(
                        "title",
                        title.take(180)
                    )
                }

                else ->
                    projectStoredNotification(
                        source = snapshotNotification(context, sbn),
                        projection = PROJECTION_FULL
                    )
            }
        }

        private fun projectStoredNotification(
            source: JSONObject,
            projection: String
        ): JSONObject {
            val output =
                JSONObject()
                    .put("key", source.optString("key"))
                    .put("package", source.optString("package"))
                    .put("app", source.optString("app"))
                    .put("post_time", source.optLong("post_time", 0L))
                    .put("ongoing", source.optBoolean("ongoing", false))

            when (projection) {
                PROJECTION_APP_NAMES_ONLY ->
                    Unit

                PROJECTION_TITLES ->
                    output.put(
                        "title",
                        source.optString("title")
                            .replace(Regex("\\s+"), " ")
                            .trim()
                            .take(180)
                    )

                else -> {
                    output.put(
                        "title",
                        source.optString("title")
                            .replace(Regex("\\s+"), " ")
                            .trim()
                            .take(180)
                    )
                    output.put(
                        "text",
                        source.optString("text")
                            .replace(Regex("\\s+"), " ")
                            .trim()
                            .take(280)
                    )
                    output.put(
                        "sub_text",
                        source.optString("sub_text")
                            .replace(Regex("\\s+"), " ")
                            .trim()
                            .take(120)
                    )
                }
            }

            return output
        }

        private fun notificationMetadata(
            context: Context,
            sbn: StatusBarNotification
        ): JSONObject {
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
                .put("post_time", sbn.postTime)
                .put("ongoing", sbn.isOngoing)
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

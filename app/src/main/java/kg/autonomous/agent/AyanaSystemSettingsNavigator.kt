package kg.autonomous.agent

import android.content.Context
import android.content.Intent
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * AYANA System Settings Navigator v1.0 — verified section truth + OEM recovery.
 *
 * One component owns system-Settings navigation truth:
 * 1) canonicalize the requested section;
 * 2) dispatch the strongest Android Settings intent available;
 * 3) verify com.android.settings ownership AND section-specific semantic evidence;
 * 4) if the OEM intent lands on the wrong/sparse page, reopen Settings root and
 *    perform a bounded semantic recovery path through Screen Intelligence;
 * 5) never report SUCCESS for mere Intent dispatch.
 *
 * The battery route deliberately does NOT use ACTION_BATTERY_SAVER_SETTINGS for
 * the generic "battery" section because that action means Power saver, not the
 * Battery overview. Samsung/other OEMs may expose android.settings.BATTERY_SETTINGS;
 * when they do not, the semantic recovery path is used instead.
 */
class AyanaSystemSettingsNavigator(
    context: Context,
    private val screenIntelligence: AyanaScreenIntelligence,
    private val shouldCancel: () -> Boolean = { false }
) {

    private val appContext = context.applicationContext

    data class Route(
        val section: String,
        val primaryAction: String,
        val markers: List<String>,
        val directTargets: List<String>,
        val exactTitleMarkers: List<String> = emptyList(),
        val parentTargets: List<String> = emptyList(),
        val childTargets: List<String> = emptyList(),
        val allowOwnerOnly: Boolean = false
    )

    fun open(requestedSection: String): JSONObject {
        val canonical = canonicalSection(requestedSection)
            ?: return unsupported(requestedSection)

        val route = routeFor(canonical)
            ?: return unsupported(requestedSection)

        if (isCancelled()) {
            return cancelled(canonical)
        }

        val before = safeScreen()
        val beforeFingerprint = screenFingerprint(before)

        val directDispatch = dispatch(route.primaryAction)
        if (directDispatch) {
            val directVerification = awaitVerifiedSection(
                route = route,
                beforeFingerprint = beforeFingerprint,
                timeoutMs = DIRECT_VERIFY_TIMEOUT_MS
            )

            if (directVerification.optBoolean("verified", false)) {
                return successResult(
                    route = route,
                    requestedSection = requestedSection,
                    verification = directVerification,
                    dispatchAction = route.primaryAction,
                    mode = "direct_intent_verified"
                )
            }
        }

        if (isCancelled()) {
            return cancelled(canonical)
        }

        // Exact direct action either does not exist on this OEM or did not land
        // on the requested section. Recover from the Settings root instead of
        // claiming success for the nearest page.
        val rootBefore = safeScreen()
        val rootBeforeFingerprint = screenFingerprint(rootBefore)

        if (!dispatch(Settings.ACTION_SETTINGS)) {
            return failure(
                route = route,
                requestedSection = requestedSection,
                reason = "settings_root_dispatch_failed",
                screen = safeScreen(),
                actionAccepted = directDispatch
            )
        }

        val root = awaitSettingsOwner(
            beforeFingerprint = rootBeforeFingerprint,
            timeoutMs = ROOT_READY_TIMEOUT_MS
        )

        if (!root.optBoolean("settings_owner_verified", false)) {
            return failure(
                route = route,
                requestedSection = requestedSection,
                reason = "settings_owner_not_verified",
                screen = root.optJSONObject("screen") ?: safeScreen(),
                actionAccepted = true
            )
        }

        // Some requested sections are the Settings root itself.
        if (route.allowOwnerOnly && route.markers.isEmpty()) {
            val rootScreen = root.optJSONObject("screen") ?: safeScreen()
            return JSONObject()
                .put("success", true)
                .put("verified", true)
                .put("action_accepted", true)
                .put("terminal_status", "SUCCESS")
                .put("status", "settings_section_verified")
                .put("reason", "settings_root_owner_verified")
                .put("section", route.section)
                .put("requested_section", requestedSection)
                .put("canonical_section", route.section)
                .put("dispatch_action", Settings.ACTION_SETTINGS)
                .put("verification_mode", "settings_root_owner_verified")
                .put("proof_level", "settings_foreground_owner")
                .put("settings_owner_verified", true)
                .put("screen_changed", screenFingerprint(rootScreen) != beforeFingerprint)
                .put("screen", rootScreen)
                .put("message", "Открыт раздел настроек: ${displayName(route.section)}")
        }

        val recovery = recoverFromRoot(route)
        if (recovery.optBoolean("verified", false)) {
            return successResult(
                route = route,
                requestedSection = requestedSection,
                verification = recovery,
                dispatchAction = Settings.ACTION_SETTINGS,
                mode = recovery.optString("verification_mode", "semantic_recovery_verified")
            )
        }

        return failure(
            route = route,
            requestedSection = requestedSection,
            reason = recovery.optString("reason", "settings_section_not_verified"),
            screen = recovery.optJSONObject("screen") ?: safeScreen(),
            actionAccepted = true,
            details = recovery
        )
    }

    private fun recoverFromRoot(route: Route): JSONObject {
        if (isCancelled()) return cancelled(route.section)

        // First try the final section directly from the root. This covers OEMs
        // that expose Battery/Date & time directly and avoids unnecessary parent clicks.
        if (route.directTargets.isNotEmpty()) {
            val directClick = clickAny(route.directTargets)
            if (directClick.optBoolean("action_accepted", false)) {
                val verification = awaitVerifiedSection(
                    route = route,
                    beforeFingerprint = directClick.optString("before_fingerprint"),
                    timeoutMs = RECOVERY_VERIFY_TIMEOUT_MS
                )
                if (verification.optBoolean("verified", false)) {
                    return verification
                        .put("verification_mode", "semantic_root_target_verified")
                        .put("recovery_target", directClick.optString("clicked_target"))
                }
            }
        }

        if (isCancelled()) return cancelled(route.section)

        // A fuzzy OEM target can legitimately land on a parent page (for example
        // "Battery" -> "Battery and device care"). Before rebuilding the whole
        // route, try the final child label once on the factual current page.
        if (route.childTargets.isNotEmpty()) {
            val childDirect = clickAny(route.childTargets)
            if (childDirect.optBoolean("action_accepted", false)) {
                val verification = awaitVerifiedSection(
                    route = route,
                    beforeFingerprint = childDirect.optString("before_fingerprint"),
                    timeoutMs = RECOVERY_VERIFY_TIMEOUT_MS
                )
                if (verification.optBoolean("verified", false)) {
                    return verification
                        .put("verification_mode", "semantic_current_parent_child_verified")
                        .put("recovery_target", childDirect.optString("clicked_target"))
                }
            }
        }

        if (isCancelled()) return cancelled(route.section)

        // Optional two-level OEM path. Reset to the Settings root first so a
        // previous approximate click cannot strand recovery inside the wrong pane.
        if (route.parentTargets.isNotEmpty() && route.childTargets.isNotEmpty()) {
            dispatch(Settings.ACTION_SETTINGS)
            awaitSettingsOwner(
                beforeFingerprint = screenFingerprint(safeScreen()),
                timeoutMs = ROOT_READY_TIMEOUT_MS
            )

            val parentClick = clickAny(route.parentTargets)
            if (parentClick.optBoolean("action_accepted", false)) {
                val childClick = clickAny(route.childTargets)
                if (childClick.optBoolean("action_accepted", false)) {
                    val verification = awaitVerifiedSection(
                        route = route,
                        beforeFingerprint = childClick.optString("before_fingerprint"),
                        timeoutMs = RECOVERY_VERIFY_TIMEOUT_MS
                    )
                    if (verification.optBoolean("verified", false)) {
                        return verification
                            .put("verification_mode", "semantic_parent_child_verified")
                            .put("recovery_parent", parentClick.optString("clicked_target"))
                            .put("recovery_target", childClick.optString("clicked_target"))
                    }
                }
            }
        }

        return JSONObject()
            .put("success", false)
            .put("verified", false)
            .put("reason", "semantic_recovery_not_verified")
            .put("terminal_status", "ERROR")
            .put("screen", safeScreen())
    }

    private fun clickAny(targets: List<String>): JSONObject {
        val before = safeScreen()
        val beforeFingerprint = screenFingerprint(before)
        var last = JSONObject()
            .put("success", false)
            .put("action_accepted", false)

        for (target in targets.distinct()) {
            if (isCancelled()) return cancelled("")

            val result = try {
                screenIntelligence.click(
                    target = target,
                    confirmed = false
                )
            } catch (error: Exception) {
                JSONObject()
                    .put("success", false)
                    .put("verified", false)
                    .put("action_accepted", false)
                    .put("reason", error.javaClass.simpleName)
            }

            last = result

            if (
                result.optBoolean("success", false) ||
                result.optBoolean("action_accepted", false) ||
                result.optBoolean("screen_changed", false)
            ) {
                return result
                    .put("clicked_target", target)
                    .put("before_fingerprint", beforeFingerprint)
            }
        }

        return last
            .put("before_fingerprint", beforeFingerprint)
    }

    private fun awaitVerifiedSection(
        route: Route,
        beforeFingerprint: String,
        timeoutMs: Long
    ): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
        var latest = safeScreen()

        do {
            if (isCancelled()) return cancelled(route.section)

            val verification = verifySection(route, latest)
            if (verification.optBoolean("verified", false)) {
                return verification
                    .put(
                        "screen_changed",
                        beforeFingerprint.isNotBlank() &&
                            screenFingerprint(latest).isNotBlank() &&
                            beforeFingerprint != screenFingerprint(latest)
                    )
                    .put("screen", latest)
            }

            if (System.currentTimeMillis() >= deadline) break

            sleep(POLL_MS)
            latest = safeScreen()
        } while (true)

        val final = verifySection(route, latest)
        return final
            .put("screen_changed", beforeFingerprint != screenFingerprint(latest))
            .put("screen", latest)
    }

    private fun awaitSettingsOwner(
        beforeFingerprint: String,
        timeoutMs: Long
    ): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
        var latest = safeScreen()

        do {
            if (isCancelled()) return cancelled("")

            val owner = settingsOwnerProof(latest)
            if (owner.first) {
                return JSONObject()
                    .put("success", true)
                    .put("verified", true)
                    .put("settings_owner_verified", true)
                    .put("settings_owner_source", owner.second)
                    .put("screen_changed", beforeFingerprint != screenFingerprint(latest))
                    .put("screen", latest)
            }

            if (System.currentTimeMillis() >= deadline) break
            sleep(POLL_MS)
            latest = safeScreen()
        } while (true)

        return JSONObject()
            .put("success", false)
            .put("verified", false)
            .put("settings_owner_verified", false)
            .put("screen_changed", beforeFingerprint != screenFingerprint(latest))
            .put("screen", latest)
    }

    private fun verifySection(
        route: Route,
        screen: JSONObject
    ): JSONObject {
        if (!screen.optBoolean("snapshot_success", screen.optBoolean("success", false))) {
            return JSONObject()
                .put("verified", false)
                .put("reason", "screen_snapshot_unavailable")
                .put("settings_owner_verified", false)
        }

        val owner = settingsOwnerProof(screen)
        if (!owner.first) {
            return JSONObject()
                .put("verified", false)
                .put("reason", "settings_owner_not_verified")
                .put("settings_owner_verified", false)
        }

        if (route.allowOwnerOnly && route.markers.isEmpty()) {
            return JSONObject()
                .put("verified", true)
                .put("reason", "settings_owner_verified")
                .put("settings_owner_verified", true)
                .put("settings_owner_source", owner.second)
                .put("matched_marker", "")
                .put("proof_level", "settings_foreground_owner")
        }

        val corpus = settingsVerificationCorpus(screen)
        val normalizedCorpus = normalize(corpus)
        val titles = settingsFactualTitles(screen)

        val exactTitleMatch = route.exactTitleMarkers.firstOrNull { marker ->
            val normalizedMarker = normalize(marker)
            normalizedMarker.isNotBlank() &&
                titles.any { title ->
                    normalize(title) == normalizedMarker
                }
        }

        val corpusMatch = route.markers.firstOrNull { marker ->
            val normalizedMarker = normalize(marker)
            normalizedMarker.isNotBlank() && normalizedCorpus.contains(normalizedMarker)
        }

        val matched = exactTitleMatch ?: corpusMatch
        val verified = matched != null

        return JSONObject()
            .put("verified", verified)
            .put(
                "reason",
                when {
                    exactTitleMatch != null -> "settings_section_exact_title_verified"
                    corpusMatch != null -> "settings_section_marker_verified"
                    else -> "settings_section_marker_missing"
                }
            )
            .put("settings_owner_verified", true)
            .put("settings_owner_source", owner.second)
            .put("matched_marker", matched.orEmpty())
            .put(
                "proof_level",
                when {
                    exactTitleMatch != null -> "same_settings_context_exact_title"
                    corpusMatch != null -> "same_settings_context_section_marker"
                    else -> "settings_owner_only"
                }
            )
            .put("verification_corpus", corpus.take(1400))
    }

    private fun settingsOwnerProof(screen: JSONObject): Pair<Boolean, String> {
        if (screen.optString("interaction_package").trim() == SETTINGS_PACKAGE) {
            return true to "interaction_package"
        }
        if (screen.optString("package").trim() == SETTINGS_PACKAGE) {
            return true to "primary_package"
        }

        val ownerPackage = screen.optString("foreground_owner_package").trim()
        val ownerAge = screen.optLong("foreground_owner_age_ms", -1L)
        if (ownerPackage == SETTINGS_PACKAGE && ownerAge in 0L..OWNER_FRESH_MS) {
            return true to "fresh_foreground_owner"
        }

        val windows = screen.optJSONArray("windows") ?: return false to "settings_window_missing"
        for (index in 0 until windows.length()) {
            val window = windows.optJSONObject(index) ?: continue
            if (window.optString("package").trim() != SETTINGS_PACKAGE) continue
            if (
                window.optBoolean("interaction_context", false) ||
                window.optBoolean("focused", false) ||
                window.optBoolean("active", false)
            ) {
                return true to "settings_interaction_window"
            }
        }

        return false to "settings_owner_not_proven"
    }

    private fun settingsVerificationCorpus(screen: JSONObject): String {
        val values = linkedSetOf<String>()
        val windows = screen.optJSONArray("windows")

        if (windows != null) {
            var hadSettingsContext = false
            for (index in 0 until windows.length()) {
                val window = windows.optJSONObject(index) ?: continue
                if (window.optString("package").trim() != SETTINGS_PACKAGE) continue

                val factual =
                    window.optBoolean("interaction_context", false) ||
                        window.optBoolean("focused", false) ||
                        window.optBoolean("active", false)

                if (!factual) continue
                hadSettingsContext = true

                addIfPresent(values, window.optString("title"))
                addIfPresent(values, window.optString("verification_text"))
                appendStrings(values, window.optJSONArray("visible_text"))

                val surface = window.optString("semantic_surface").trim()
                if (surface.isNotBlank()) values.add(surface)
            }

            if (hadSettingsContext) {
                return values.joinToString(" | ")
            }
        }

        if (
            screen.optString("interaction_package").trim() == SETTINGS_PACKAGE ||
            screen.optString("package").trim() == SETTINGS_PACKAGE
        ) {
            addIfPresent(values, screen.optString("primary_window_title"))
            addIfPresent(values, screen.optString("verification_text"))
            appendStrings(values, screen.optJSONArray("visible_text"))
        }

        return values.joinToString(" | ")
    }

    private fun settingsFactualTitles(screen: JSONObject): List<String> {
        val result = linkedSetOf<String>()
        val windows = screen.optJSONArray("windows")

        if (windows != null) {
            for (index in 0 until windows.length()) {
                val window = windows.optJSONObject(index) ?: continue
                if (window.optString("package").trim() != SETTINGS_PACKAGE) continue
                if (
                    !window.optBoolean("interaction_context", false) &&
                    !window.optBoolean("focused", false) &&
                    !window.optBoolean("active", false)
                ) {
                    continue
                }

                val title = window.optString("title").replace(Regex("\\s+"), " ").trim()
                if (title.isNotBlank()) result.add(title)
            }
        }

        // Never combine an unrelated overlay/app title with Settings ownership.
        // The primary title is factual Settings evidence only when the primary
        // or interaction package itself is com.android.settings.
        if (
            screen.optString("interaction_package").trim() == SETTINGS_PACKAGE ||
            screen.optString("package").trim() == SETTINGS_PACKAGE
        ) {
            val primaryTitle =
                screen.optString("primary_window_title")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            if (primaryTitle.isNotBlank()) result.add(primaryTitle)
        }

        return result.toList()
    }

    private fun screenFingerprint(screen: JSONObject): String {
        if (screen.length() == 0) return ""

        val windows = screen.optJSONArray("windows")
        val windowSummary = buildString {
            if (windows != null) {
                val limit = minOf(windows.length(), 6)
                for (index in 0 until limit) {
                    val window = windows.optJSONObject(index) ?: continue
                    if (isNotEmpty()) append("||")
                    append(window.optInt("window_id", -1))
                    append(':')
                    append(window.optString("package"))
                    append(':')
                    append(window.optString("title"))
                    append(':')
                    append(window.optString("semantic_surface"))
                    append(':')
                    append(window.optString("verification_text").take(500))
                }
            }
        }

        return buildString {
            append(screen.optString("interaction_package"))
            append('|')
            append(screen.optString("package"))
            append('|')
            append(screen.optString("primary_context_id"))
            append('|')
            append(screen.optString("foreground_owner_package"))
            append('|')
            append(screen.optString("verification_text").take(800))
            append('|')
            append(windowSummary)
        }
    }

    private fun routeFor(section: String): Route? = when (section) {
        "general" -> Route(
            section = section,
            primaryAction = Settings.ACTION_SETTINGS,
            markers = emptyList(),
            directTargets = emptyList(),
            allowOwnerOnly = true
        )

        "connections" -> Route(
            section = section,
            primaryAction = Settings.ACTION_WIRELESS_SETTINGS,
            markers = listOf("Сеть и Интернет", "Network & internet", "Network and internet"),
            directTargets = listOf("Подключения", "Connections", "Сеть и Интернет", "Network & internet"),
            exactTitleMarkers = listOf("Подключения", "Connections", "Сеть и Интернет", "Network & internet", "Network and internet")
        )

        "wifi" -> Route(
            section, Settings.ACTION_WIFI_SETTINGS,
            markers = listOf("Wi-Fi", "Wi‑Fi", "WiFi", "WLAN"),
            directTargets = listOf("Wi-Fi", "Wi‑Fi", "WiFi")
        )

        "bluetooth" -> Route(
            section, Settings.ACTION_BLUETOOTH_SETTINGS,
            markers = listOf("Bluetooth", "Блютуз"),
            directTargets = listOf("Bluetooth", "Блютуз")
        )

        "sound" -> Route(
            section, Settings.ACTION_SOUND_SETTINGS,
            markers = listOf("Звуки и вибрация", "Звук", "Sounds and vibration", "Sound"),
            directTargets = listOf("Звуки и вибрация", "Звук", "Sounds and vibration", "Sound")
        )

        "display" -> Route(
            section, Settings.ACTION_DISPLAY_SETTINGS,
            markers = listOf("Дисплей", "Экран", "Display"),
            directTargets = listOf("Дисплей", "Экран", "Display")
        )

        "apps" -> Route(
            section, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS,
            markers = listOf("Приложения", "Apps"),
            directTargets = listOf("Приложения", "Apps")
        )

        "accessibility" -> Route(
            section, Settings.ACTION_ACCESSIBILITY_SETTINGS,
            markers = listOf("Специальные возможности", "Accessibility"),
            directTargets = listOf("Специальные возможности", "Accessibility")
        )

        "location" -> Route(
            section, Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            markers = listOf("Местоположение", "Геолокация", "Location"),
            directTargets = listOf("Местоположение", "Геолокация", "Location")
        )

        "security" -> Route(
            section, Settings.ACTION_SECURITY_SETTINGS,
            markers = listOf("Безопасность", "Security"),
            directTargets = listOf("Безопасность", "Security")
        )

        "date_time" -> Route(
            section, Settings.ACTION_DATE_SETTINGS,
            markers = listOf("Дата и время", "Date and time"),
            directTargets = listOf("Дата и время", "Date and time"),
            exactTitleMarkers = listOf("Дата и время", "Date and time"),
            parentTargets = listOf("Общие настройки", "General management", "Система", "System"),
            childTargets = listOf("Дата и время", "Date and time")
        )

        "battery" -> Route(
            section, BATTERY_OVERVIEW_ACTION,
            markers = listOf("Использование батареи", "Battery usage"),
            directTargets = listOf("Батарея", "Battery"),
            exactTitleMarkers = listOf("Батарея", "Battery"),
            parentTargets = listOf("Батарея и обслуживание устройства", "Обслуживание устройства", "Battery and device care", "Device care"),
            childTargets = listOf("Батарея", "Battery")
        )

        "storage" -> Route(
            section, Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            markers = listOf("Хранилище", "Память", "Storage"),
            directTargets = listOf("Хранилище", "Память", "Storage")
        )

        "notifications" -> Route(
            section, Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS,
            markers = listOf("Уведомления", "Notifications"),
            directTargets = listOf("Уведомления", "Notifications")
        )

        "data_usage" -> Route(
            section, Settings.ACTION_DATA_USAGE_SETTINGS,
            markers = listOf("Использование данных", "Мобильные данные", "Data usage", "Mobile data"),
            directTargets = listOf("Использование данных", "Мобильные данные", "Data usage")
        )

        "vpn" -> Route(
            section, Settings.ACTION_VPN_SETTINGS,
            markers = listOf("VPN", "ВПН"),
            directTargets = listOf("VPN", "ВПН")
        )

        "nfc" -> Route(
            section, Settings.ACTION_NFC_SETTINGS,
            markers = listOf("NFC", "НФС"),
            directTargets = listOf("NFC", "НФС")
        )

        "language" -> Route(
            section, Settings.ACTION_LOCALE_SETTINGS,
            markers = listOf("Язык", "Languages", "Language"),
            directTargets = listOf("Язык", "Languages", "Language"),
            parentTargets = listOf("Общие настройки", "General management", "Система", "System"),
            childTargets = listOf("Язык", "Languages", "Language")
        )

        "keyboard" -> Route(
            section, Settings.ACTION_INPUT_METHOD_SETTINGS,
            markers = listOf("Клавиатура", "Keyboard", "Экранная клавиатура", "On-screen keyboard"),
            directTargets = listOf("Клавиатура", "Keyboard", "Экранная клавиатура")
        )

        "default_apps" -> Route(
            section, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
            markers = listOf("Приложения по умолчанию", "Default apps"),
            directTargets = listOf("Приложения по умолчанию", "Default apps")
        )

        "developer_options" -> Route(
            section, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
            markers = listOf("Параметры разработчика", "Для разработчиков", "Developer options"),
            directTargets = listOf("Параметры разработчика", "Для разработчиков", "Developer options")
        )

        "device_info" -> Route(
            section, Settings.ACTION_DEVICE_INFO_SETTINGS,
            markers = listOf("Сведения о планшете", "Об устройстве", "About tablet", "About device"),
            directTargets = listOf("Сведения о планшете", "Об устройстве", "About tablet", "About device")
        )

        "privacy" -> Route(
            section,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                Settings.ACTION_PRIVACY_SETTINGS
            } else {
                Settings.ACTION_SECURITY_SETTINGS
            },
            markers = listOf("Конфиденциальность", "Privacy"),
            directTargets = listOf("Конфиденциальность", "Privacy")
        )

        "battery_optimization" -> Route(
            section, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            markers = listOf("Оптимизация батареи", "Battery optimization", "Оптимизация энергопотребления"),
            directTargets = listOf("Оптимизация батареи", "Battery optimization", "Оптимизация энергопотребления")
        )

        else -> null
    }

    private fun canonicalSection(raw: String): String? {
        val value = normalize(raw)
        if (value.isBlank()) return "general"

        return when {
            value in setOf("general", "settings", "настройки", "общие настройки") -> "general"
            value.contains("подключен") || value == "connections" || value.contains("network internet") || value.contains("сеть интернет") -> "connections"
            value in setOf("wifi", "wi fi", "вай фай", "вайфай", "wlan") -> "wifi"
            value.contains("bluetooth") || value.contains("блютуз") -> "bluetooth"
            value.contains("звук") || value.contains("sound") -> "sound"
            value.contains("диспле") || value.contains("экран") || value == "display" -> "display"
            value.contains("приложен") || value == "apps" -> "apps"
            value.contains("специальн") || value.contains("accessibility") -> "accessibility"
            value.contains("местополож") || value.contains("геолокац") || value == "location" -> "location"
            value.contains("безопасност") || value == "security" -> "security"
            (value.contains("дат") && value.contains("врем")) || value == "date time" -> "date_time"
            value.contains("оптимизац") && value.contains("батар") -> "battery_optimization"
            value.contains("батар") || value.contains("аккумуля") || value == "battery" -> "battery"
            value.contains("хранилищ") || value.contains("памят") || value == "storage" -> "storage"
            value.contains("уведомлен") || value == "notifications" -> "notifications"
            value.contains("использован") && value.contains("дан") || value == "data usage" -> "data_usage"
            value == "vpn" || value == "впн" -> "vpn"
            value == "nfc" || value == "нфс" -> "nfc"
            value.contains("язык") || value == "language" || value == "languages" -> "language"
            value.contains("клавиатур") || value.contains("метод ввода") || value == "keyboard" -> "keyboard"
            value.contains("по умолчани") || value == "default apps" -> "default_apps"
            value.contains("разработчик") || value == "developer options" -> "developer_options"
            value.contains("сведения") || value.contains("об устройстве") || value.contains("о планшете") || value == "device info" -> "device_info"
            value.contains("конфиденциаль") || value.contains("приват") || value == "privacy" -> "privacy"
            else -> null
        }
    }

    private fun successResult(
        route: Route,
        requestedSection: String,
        verification: JSONObject,
        dispatchAction: String,
        mode: String
    ): JSONObject {
        val screen = verification.optJSONObject("screen") ?: safeScreen()
        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("action_accepted", true)
            .put("terminal_status", "SUCCESS")
            .put("status", "settings_section_verified")
            .put("reason", verification.optString("reason", "settings_section_verified"))
            .put("section", route.section)
            .put("requested_section", requestedSection)
            .put("canonical_section", route.section)
            .put("dispatch_action", dispatchAction)
            .put("verification_mode", mode)
            .put("proof_level", verification.optString("proof_level", "same_settings_context_section_marker"))
            .put("settings_owner_verified", verification.optBoolean("settings_owner_verified", true))
            .put("settings_owner_source", verification.optString("settings_owner_source"))
            .put("matched_marker", verification.optString("matched_marker"))
            .put("screen_changed", verification.optBoolean("screen_changed", false))
            .put("screen", screen)
            .put("message", "Открыт и подтверждён раздел настроек: ${displayName(route.section)}")
    }

    private fun failure(
        route: Route,
        requestedSection: String,
        reason: String,
        screen: JSONObject,
        actionAccepted: Boolean,
        details: JSONObject? = null
    ): JSONObject = JSONObject()
        .put("success", false)
        .put("verified", false)
        .put("action_accepted", actionAccepted)
        .put("terminal_status", "ERROR")
        .put("status", "settings_section_not_verified")
        .put("reason", reason)
        .put("section", route.section)
        .put("requested_section", requestedSection)
        .put("canonical_section", route.section)
        .put("settings_owner_verified", settingsOwnerProof(screen).first)
        .put("screen", screen)
        .put("details", details ?: JSONObject())
        .put("message", "Android открыл Settings, но раздел «${displayName(route.section)}» не удалось надёжно подтвердить")

    private fun unsupported(requestedSection: String): JSONObject = JSONObject()
        .put("success", false)
        .put("verified", false)
        .put("action_accepted", false)
        .put("terminal_status", "UNSUPPORTED")
        .put("status", "unsupported_settings_section")
        .put("reason", "unsupported_settings_section")
        .put("requested_section", requestedSection)
        .put("message", "Неизвестный системный раздел настроек: $requestedSection")

    private fun cancelled(section: String): JSONObject = JSONObject()
        .put("success", false)
        .put("verified", false)
        .put("action_accepted", false)
        .put("terminal_status", "CANCELLED")
        .put("status", "cancelled")
        .put("reason", "cancelled")
        .put("section", section)
        .put("message", "Переход в настройки отменён")

    private fun safeScreen(): JSONObject = try {
        screenIntelligence.getScreenState()
    } catch (error: Exception) {
        JSONObject()
            .put("success", false)
            .put("snapshot_success", false)
            .put("message", error.message ?: "screen_snapshot_exception")
    }

    private fun dispatch(action: String): Boolean {
        if (action.isBlank()) return false
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val resolvable = try {
            intent.resolveActivity(appContext.packageManager) != null
        } catch (_: Exception) {
            true
        }

        if (!resolvable) return false

        return try {
            appContext.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isCancelled(): Boolean = try {
        shouldCancel()
    } catch (_: Exception) {
        false
    }

    private fun appendStrings(target: MutableSet<String>, array: JSONArray?) {
        if (array == null) return
        for (index in 0 until array.length()) {
            addIfPresent(target, array.optString(index))
        }
    }

    private fun addIfPresent(target: MutableSet<String>, value: String?) {
        val clean = value.orEmpty().replace(Regex("\\s+"), " ").trim()
        if (clean.isNotBlank()) target.add(clean.take(1800))
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun displayName(section: String): String = when (section) {
        "general" -> "Общие настройки"
        "connections" -> "Подключения"
        "wifi" -> "Wi‑Fi"
        "bluetooth" -> "Bluetooth"
        "sound" -> "Звук"
        "display" -> "Экран"
        "apps" -> "Приложения"
        "accessibility" -> "Специальные возможности"
        "location" -> "Местоположение"
        "security" -> "Безопасность"
        "date_time" -> "Дата и время"
        "battery" -> "Батарея"
        "storage" -> "Хранилище"
        "notifications" -> "Уведомления"
        "data_usage" -> "Использование данных"
        "vpn" -> "VPN"
        "nfc" -> "NFC"
        "language" -> "Язык"
        "keyboard" -> "Клавиатура"
        "default_apps" -> "Приложения по умолчанию"
        "developer_options" -> "Параметры разработчика"
        "device_info" -> "Сведения об устройстве"
        "privacy" -> "Конфиденциальность"
        "battery_optimization" -> "Оптимизация батареи"
        else -> section
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val BATTERY_OVERVIEW_ACTION = "android.settings.BATTERY_SETTINGS"
        private const val OWNER_FRESH_MS = 3500L
        private const val DIRECT_VERIFY_TIMEOUT_MS = 1900L
        private const val ROOT_READY_TIMEOUT_MS = 1500L
        private const val RECOVERY_VERIFY_TIMEOUT_MS = 1600L
        private const val POLL_MS = 120L
    }
}

package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * AYANA Android Goal Compiler v2.1 — deterministic goal integrity + strict screen expectations.
 *
 * Agent Core classifies a natural-language request into ONE structured goal.
 * This compiler deterministically turns that goal into a short local plan for
 * AyanaAndroidTaskEngine. It does not parse free-form commands and contains no
 * app-specific branches (YouTube/Chrome/Telegram/AYANA etc.).
 */
class AyanaAndroidGoalCompiler {

    fun compile(goal: JSONObject): JSONObject {

        val rawGoalType = normalize(goal.optString("goal_type"))
        val rawApp = goal.optString("app").trim()
        val rawSection = normalize(goal.optString("section"))
        val rawSettingsSection = normalize(goal.optString("settings_section"))
        val rawCategory = normalize(goal.optString("category"))
        val rawTarget = goal.optString("target").trim()
        val stopIfMissing = goal.optBoolean("stop_if_missing", false)

        // v2.1 GOAL INTEGRITY: Agent Core is a classifier, not a source of truth.
        // If its enum conflicts with populated structured fields, promote the
        // request to the more specific deterministic goal instead of silently
        // discarding the user's final target.
        val settingsSection =
            canonicalSettingsSection(rawSettingsSection)
                ?: canonicalSettingsSection(rawSection)
                ?: rawSettingsSection

        val goalType = when {
            rawApp.isNotBlank() &&
                rawTarget.isNotBlank() &&
                rawGoalType in setOf("app_info", "app_settings_item") ->
                "app_settings_item"

            rawTarget.isNotBlank() &&
                canonicalSettingsSection(settingsSection) != null &&
                rawGoalType in setOf("open_settings_section", "settings_item") ->
                "settings_item"

            else -> rawGoalType
        }

        val app =
            if (goalType in setOf("open_app", "app_info", "app_detail_section", "app_settings_item", "accessibility_service_page")) {
                rawApp
            } else {
                ""
            }

        val section =
            if (goalType == "app_detail_section") {
                canonicalSectionKey(rawSection)
            } else {
                ""
            }

        val category =
            if (goalType == "default_app_category") {
                canonicalCategory(rawCategory)
            } else {
                ""
            }

        val target =
            if (goalType in setOf("settings_item", "app_settings_item")) {
                rawTarget
            } else {
                ""
            }

        val normalizedSettingsSection =
            if (goalType in setOf("open_settings_section", "settings_item")) {
                canonicalSettingsSection(settingsSection).orEmpty()
            } else {
                ""
            }

        val normalizedGoal =
            JSONObject()
                .put("goal_type", goalType)
                .put("app", app)
                .put("section", section)
                .put("settings_section", normalizedSettingsSection)
                .put("category", category)
                .put("target", target)
                .put("stop_if_missing", stopIfMissing)

        val plan = when (goalType) {
            "open_app" -> compileOpenApp(app)
            "open_settings_section" -> compileOpenSettingsSection(normalizedSettingsSection)
            "app_info" -> compileAppInfo(app)
            "app_detail_section" -> compileAppDetailSection(app, section)
            "app_settings_item" -> compileAppSettingsItem(app, target)
            "accessibility_service_page" -> compileAccessibilityServicePage(app)
            "default_app_category" -> compileDefaultAppCategory(category)
            "settings_item" -> compileSettingsItem(normalizedSettingsSection, target)
            else -> null
        }

        if (plan == null) {
            return failure("Неподдерживаемая или неполная Android-цель: $goalType")
                .put("normalized_goal", normalizedGoal)
        }

        return JSONObject()
            .put("success", true)
            .put("status", "compiled")
            .put("goal_type", goalType)
            .put("goal_repaired", goalType != rawGoalType)
            .put("original_goal_type", rawGoalType)
            .put("stop_if_missing", stopIfMissing)
            .put("target", target)
            .put("normalized_goal", normalizedGoal)
            .put("plan", plan)
    }

    private fun compileOpenApp(app: String): JSONObject? {
        if (app.isBlank()) return null

        return plan(
            goal = "Открыть приложение $app",
            maxActions = 2,
            steps = listOf(
                step(
                    id = "open_app",
                    action = "open_app",
                    name = app,
                    terminal = true,
                    requireScreenChange = true
                )
            )
        )
    }

    private fun compileOpenSettingsSection(section: String): JSONObject? {
        val canonical = canonicalSettingsSection(section) ?: return null

        return plan(
            goal = "Открыть системный раздел $canonical",
            maxActions = 2,
            steps = listOf(
                step(
                    id = "open_settings",
                    action = "open_settings",
                    section = canonical,
                    terminal = true,
                    requireScreenChange = true,
                    expectAny = settingsSectionMarkers(canonical)
                )
            )
        )
    }

    private fun compileAppInfo(app: String): JSONObject? {
        if (app.isBlank()) return null

        return plan(
            goal = "Открыть информацию о приложении $app",
            maxActions = 2,
            steps = listOf(
                step(
                    id = "open_app_info",
                    action = "open_app_info",
                    name = app,
                    terminal = true,
                    requireScreenChange = true,
                    expectAny = listOf("Информация о приложении", "App info"),
                    expectAll = listOf(app)
                )
            )
        )
    }

    private fun compileAppDetailSection(
        app: String,
        section: String
    ): JSONObject? {
        if (app.isBlank() || section.isBlank()) return null

        val key = canonicalSectionKey(section)
        val direct = canonicalDirectAppSettings(key)

        if (direct != null) {
            return plan(
                goal = "Открыть раздел $key приложения $app",
                maxActions = 3,
                steps = listOf(
                    step(
                        id = "open_app_settings",
                        action = "open_app_settings",
                        name = app,
                        section = direct,
                        terminal = true,
                        requireScreenChange = true,
                        expectAny = terminalMarkersForAppDetail(key),
                        expectAll = listOf(app)
                    )
                )
            )
        }

        val targets = appDetailTargets(key)
        if (targets.isEmpty()) return null

        return plan(
            goal = "Открыть раздел $key приложения $app",
            maxActions = 5,
            steps = listOf(
                step(
                    id = "open_app_info",
                    action = "open_app_info",
                    name = app,
                    requireScreenChange = true,
                    expectAny = listOf("Информация о приложении", "App info"),
                    expectAll = listOf(app)
                ),
                step(
                    id = "open_app_detail",
                    action = "click_any",
                    targets = targets,
                    scrollIfMissing = true,
                    maxScrolls = 2,
                    terminal = true,
                    requireScreenChange = true,
                    expectAny = terminalMarkersForAppDetail(key)
                )
            )
        )
    }

    private fun compileAppSettingsItem(
        app: String,
        target: String
    ): JSONObject? {
        if (app.isBlank() || target.isBlank()) return null

        return plan(
            goal = "Открыть пункт $target в настройках приложения $app",
            maxActions = 5,
            steps = listOf(
                step(
                    id = "open_app_info",
                    action = "open_app_info",
                    name = app,
                    requireScreenChange = true,
                    expectAny = listOf("Информация о приложении", "App info"),
                    expectAll = listOf(app)
                ),
                step(
                    id = "open_app_item",
                    action = "click_any",
                    targets = listOf(target),
                    scrollIfMissing = true,
                    maxScrolls = 2,
                    terminal = true,
                    requireScreenChange = true,
                    expectAny = listOf(target)
                )
            )
        )
    }

    private fun compileAccessibilityServicePage(app: String): JSONObject? {
        if (app.isBlank()) return null

        return plan(
            goal = "Открыть страницу службы специальных возможностей приложения $app",
            maxActions = 6,
            steps = listOf(
                step(
                    id = "open_accessibility",
                    action = "open_settings",
                    section = "accessibility",
                    requireScreenChange = true,
                    expectAny = listOf("Специальные возможности", "Accessibility")
                ),
                step(
                    id = "open_installed_services",
                    action = "click_any",
                    targets = listOf(
                        "Установленные приложения",
                        "Установленные службы",
                        "Установленные сервисы",
                        "Installed apps",
                        "Installed services"
                    ),
                    scrollIfMissing = true,
                    maxScrolls = 1,
                    requireScreenChange = true,
                    expectAny = listOf(
                        "Установленные приложения",
                        "Установленные службы",
                        "Установленные сервисы",
                        "Installed apps",
                        "Installed services"
                    )
                ),
                step(
                    id = "open_accessibility_service",
                    action = "click_any",
                    targets = listOf(app),
                    scrollIfMissing = true,
                    maxScrolls = 2,
                    terminal = true,
                    requireScreenChange = true,
                    expectAll = listOf(app)
                )
            )
        )
    }

    private fun compileDefaultAppCategory(category: String): JSONObject? {
        val key = canonicalCategory(category)
        val targets = defaultAppCategoryTargets(key)
        if (targets.isEmpty()) return null

        return plan(
            goal = "Открыть категорию приложения по умолчанию: $key",
            maxActions = 4,
            steps = listOf(
                step(
                    id = "open_default_apps",
                    action = "open_settings",
                    section = "default_apps",
                    requireScreenChange = true,
                    expectAny = listOf("Приложения по умолчанию", "Default apps")
                ),
                step(
                    id = "open_default_category",
                    action = "click_any",
                    targets = targets,
                    scrollIfMissing = true,
                    maxScrolls = 1,
                    terminal = true,
                    requireScreenChange = true,
                    expectAny = targets
                )
            )
        )
    }

    private fun compileSettingsItem(
        settingsSection: String,
        target: String
    ): JSONObject? {
        val canonical = canonicalSettingsSection(settingsSection) ?: return null
        if (target.isBlank()) return null

        return plan(
            goal = "Открыть пункт $target в системном разделе $canonical",
            maxActions = 5,
            steps = listOf(
                step(
                    id = "open_parent_settings",
                    action = "open_settings",
                    section = canonical,
                    requireScreenChange = true,
                    expectAny = settingsSectionMarkers(canonical)
                ),
                step(
                    id = "open_target_item",
                    action = "click_any",
                    targets = listOf(target),
                    scrollIfMissing = true,
                    maxScrolls = 2,
                    terminal = true,
                    requireScreenChange = true,
                    expectAny = listOf(target)
                )
            )
        )
    }


    private fun settingsSectionMarkers(key: String): List<String> =
        when (key) {
            "accessibility" -> listOf("Специальные возможности", "Accessibility")
            "apps" -> listOf("Приложения", "Apps")
            "wifi" -> listOf("Wi-Fi", "WiFi", "Вайфай")
            "bluetooth" -> listOf("Bluetooth", "Блютуз")
            "sound" -> listOf("Звуки", "Звук", "Sound")
            "display" -> listOf("Дисплей", "Экран", "Display")
            "location" -> listOf("Местоположение", "Геолокация", "Location")
            "security" -> listOf("Безопасность", "Security")
            "date_time" -> listOf("Дата и время", "Date and time")
            "battery" -> listOf("Батарея", "Аккумулятор", "Battery")
            "storage" -> listOf("Хранилище", "Память", "Storage")
            "notifications" -> listOf("Уведомления", "Notifications")
            "data_usage" -> listOf("Использование данных", "Data usage")
            "vpn" -> listOf("VPN")
            "nfc" -> listOf("NFC")
            "language" -> listOf("Язык", "Languages", "Language")
            "keyboard" -> listOf("Клавиатура", "Keyboard")
            "default_apps" -> listOf("Приложения по умолчанию", "Default apps")
            "developer_options" -> listOf("Параметры разработчика", "Для разработчиков", "Developer options")
            "device_info" -> listOf("Сведения о планшете", "Об устройстве", "About tablet", "About device")
            "privacy" -> listOf("Конфиденциальность", "Privacy")
            "battery_optimization" -> listOf("Оптимизация батареи", "Battery optimization")
            // Some sections have OEM-specific titles. Returning an empty list
            // keeps legacy screen-change verification rather than inventing a
            // marker that could produce false negatives.
            else -> emptyList()
        }

    private fun appDetailTargets(key: String): List<String> =
        when (key) {
            "permissions" -> listOf("Разрешения", "Permissions")
            "battery" -> listOf("Батарея", "Использование батареи", "Аккумулятор", "Battery")
            "storage" -> listOf("Хранилище", "Память", "Storage")
            "mobile_data" -> listOf(
                "Мобильные данные",
                "Использование мобильных данных",
                "Использование данных",
                "Мобильный трафик",
                "Mobile data",
                "Data usage"
            )
            else -> emptyList()
        }

    private fun terminalMarkersForAppDetail(key: String): List<String> =
        when (key) {
            "permissions" -> listOf("Разрешения", "Permissions")
            "battery" -> listOf("Батарея", "Battery")
            "storage" -> listOf("Хранилище", "Storage", "Память")
            "mobile_data" -> listOf("Мобильные данные", "Mobile data", "Использование данных", "Data usage")
            "notifications" -> listOf("Уведомления", "Notifications")
            "open_by_default" -> listOf("По умолчанию", "Открытие ссылок", "Open by default", "Opening links")
            "language" -> listOf("Язык", "Language")
            "info" -> listOf("Информация о приложении", "App info")
            else -> emptyList()
        }

    private fun canonicalDirectAppSettings(key: String): String? =
        when (key) {
            "notifications" -> "notifications"
            "open_by_default" -> "open_by_default"
            "language" -> "language"
            "info" -> "info"
            else -> null
        }

    private fun defaultAppCategoryTargets(key: String): List<String> =
        when (key) {
            "browser" -> listOf("Браузер", "Приложение браузера", "Browser app", "Browser")
            "home" -> listOf("Главный экран", "Домашний экран", "Home app")
            "phone" -> listOf("Звонки", "Телефон", "Phone app")
            "sms" -> listOf("SMS", "Сообщения", "SMS app")
            "assistant" -> listOf("Цифровой помощник", "Помощник", "Digital assistant app", "Assistant app")
            "links" -> listOf("Открытие ссылок", "Opening links")
            else -> emptyList()
        }

    private fun canonicalCategory(raw: String): String =
        when (normalize(raw)) {
            "browser", "браузер", "приложение браузера" -> "browser"
            "home", "главный экран", "домашний экран" -> "home"
            "phone", "телефон", "звонки" -> "phone"
            "sms", "сообщения" -> "sms"
            "assistant", "помощник", "цифровой помощник" -> "assistant"
            "links", "ссылки", "открытие ссылок" -> "links"
            else -> normalize(raw)
        }

    private fun canonicalSettingsSection(raw: String): String? {
        val key = canonicalSectionKey(raw)
        return if (key in SETTINGS_SECTIONS) key else null
    }

    private fun canonicalSectionKey(raw: String): String {
        val value = normalize(raw)

        return when {
            value in setOf("permissions", "permission", "разрешения", "разрешение") -> "permissions"
            value.contains("батар") || value.contains("аккумуля") || value == "battery" -> "battery"
            value.contains("хранилищ") || value == "storage" || value == "память" -> "storage"
            ((value.contains("мобильн") && value.contains("данн")) || value.contains("трафик") || value == "mobile_data") -> "mobile_data"
            value.contains("уведом") || value == "notifications" -> "notifications"
            ((value.contains("по умолч") && value.contains("откры")) || value == "open_by_default") -> "open_by_default"
            value.contains("язык") || value == "language" -> "language"
            value in setOf("info", "app_info", "информация", "информация о приложении") -> "info"
            value in setOf("general", "общие", "общие настройки") -> "general"
            value in setOf("apps", "приложения") -> "apps"
            value in setOf("wifi", "wi-fi", "вайфай", "вай фай") -> "wifi"
            value.contains("bluetooth") || value.contains("блютуз") -> "bluetooth"
            value.contains("звук") || value == "sound" -> "sound"
            value.contains("диспле") || value == "display" -> "display"
            value.contains("специальн") || value.contains("accessibility") -> "accessibility"
            value.contains("местополож") || value.contains("геолока") || value == "location" -> "location"
            value.contains("безопас") || value == "security" -> "security"
            ((value.contains("дата") && value.contains("время")) || value == "date_time") -> "date_time"
            value == "data_usage" || value == "использование данных" -> "data_usage"
            value == "vpn" || value == "впн" -> "vpn"
            value == "nfc" || value == "нфс" -> "nfc"
            value.contains("клавиатур") || value == "keyboard" -> "keyboard"
            value.contains("приложения по умолчанию") || value == "default_apps" -> "default_apps"
            value.contains("разработчик") || value == "developer_options" -> "developer_options"
            value.contains("об устройстве") || value.contains("о планшете") || value == "device_info" -> "device_info"
            value.contains("конфиденц") || value.contains("приват") || value == "privacy" -> "privacy"
            ((value.contains("оптимизац") && value.contains("батар")) || value == "battery_optimization") -> "battery_optimization"
            else -> value
        }
    }

    private fun plan(
        goal: String,
        maxActions: Int,
        steps: List<JSONObject>
    ): JSONObject {
        val array = JSONArray()
        steps.forEach { array.put(it) }

        return JSONObject()
            .put("goal", goal)
            .put("max_actions", maxActions.coerceIn(1, HARD_MAX_ACTIONS))
            .put("steps", array)
    }

    private fun step(
        id: String,
        action: String,
        section: String = "",
        name: String = "",
        volumeAction: String = "",
        targets: List<String> = emptyList(),
        scrollIfMissing: Boolean = false,
        maxScrolls: Int = 0,
        scrollDirection: String = "down",
        direction: String = "down",
        target: String = "",
        text: String = "",
        sensitive: Boolean = false,
        allowOverflow: Boolean = false,
        optional: Boolean = false,
        terminal: Boolean = false,
        requireScreenChange: Boolean = false,
        expectAny: List<String> = emptyList(),
        expectAll: List<String> = emptyList(),
        expectNone: List<String> = emptyList()
    ): JSONObject =
        JSONObject()
            .put("id", id)
            .put("action", action)
            .put("section", section)
            .put("name", name)
            .put("volume_action", volumeAction)
            .put("targets", JSONArray(targets))
            .put("scroll_if_missing", scrollIfMissing)
            .put("max_scrolls", maxScrolls)
            .put("scroll_direction", scrollDirection)
            .put("direction", direction)
            .put("target", target)
            .put("text", text)
            .put("sensitive", sensitive)
            .put("allow_overflow", allowOverflow)
            .put("optional", optional)
            .put("terminal", terminal)
            .put("require_screen_change", requireScreenChange)
            .put("expect_any", JSONArray(expectAny))
            .put("expect_all", JSONArray(expectAll))
            .put("expect_none", JSONArray(expectNone))

    private fun failure(message: String): JSONObject =
        JSONObject()
            .put("success", false)
            .put("status", "invalid_goal")
            .put("message", message)

    private fun normalize(value: String): String =
        value
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("\\s+"), " ")
            .trim()

    companion object {
        private const val HARD_MAX_ACTIONS = 8

        private val SETTINGS_SECTIONS = setOf(
            "general",
            "apps",
            "wifi",
            "bluetooth",
            "sound",
            "display",
            "accessibility",
            "location",
            "security",
            "date_time",
            "battery",
            "storage",
            "notifications",
            "data_usage",
            "vpn",
            "nfc",
            "language",
            "keyboard",
            "default_apps",
            "developer_options",
            "device_info",
            "privacy",
            "battery_optimization"
        )
    }
}

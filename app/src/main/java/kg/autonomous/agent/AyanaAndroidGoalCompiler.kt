package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * AYANA Android Goal Compiler v2.0 — execution contract foundation
 *
 * Architecture:
 * 1) Agent Core understands the user's natural-language intent.
 * 2) Agent Core sends ONE structured Android goal.
 * 3) This compiler converts that goal into a deterministic local plan.
 * 4) Compiler attaches an executor/verification/cancellation contract.
 * 5) AyanaAndroidTaskEngine executes the deterministic plan locally.
 *
 * IMPORTANT:
 * - This class does NOT parse free-form user commands.
 * - It does NOT contain app-specific branches for YouTube, Chrome, Telegram,
 *   AYANA AI, etc.
 * - It compiles generic Android goal types into reusable capability plans.
 */
class AyanaAndroidGoalCompiler {

    fun compile(
        goal: JSONObject
    ): JSONObject {

        val type =
            normalize(
                goal.optString(
                    "type"
                )
            )

        val app =
            goal.optString(
                "app"
            ).trim()

        val section =
            normalize(
                goal.optString(
                    "section"
                )
            )

        val category =
            normalize(
                goal.optString(
                    "category"
                )
            )

        val settingsSection =
            normalize(
                goal.optString(
                    "settings_section"
                )
            )

        val changeState =
            goal.optBoolean(
                "change_state",
                false
            )

        val maxActions =
            goal.optInt(
                "max_actions",
                defaultBudgetFor(
                    type
                )
            ).coerceIn(
                1,
                HARD_MAX_ACTIONS
            )

        if (type.isBlank()) {
            return failure(
                "Не указан type Android-цели"
            )
        }

        if (changeState) {
            return failure(
                "Goal Compiler v2 предназначен для безопасной навигации и просмотра. Изменение состояния должно выполняться отдельным подтверждённым действием.",
                terminalStatus = "BLOCKED"
            )
        }

        val plan =
            when (type) {

                "open_app" ->
                    compileOpenApp(
                        app = app,
                        maxActions = maxActions
                    )

                "open_settings_section" ->
                    compileOpenSettingsSection(
                        settingsSection =
                            settingsSection
                                .ifBlank {
                                    section
                                },
                        maxActions = maxActions
                    )

                "app_info" ->
                    compileAppInfo(
                        app = app,
                        maxActions = maxActions
                    )

                "app_detail_section" ->
                    compileAppDetailSection(
                        app = app,
                        section = section,
                        maxActions = maxActions
                    )

                "accessibility_service_page" ->
                    compileAccessibilityServicePage(
                        app = app,
                        maxActions = maxActions
                    )

                "default_app_category" ->
                    compileDefaultAppCategory(
                        category = category
                            .ifBlank {
                                section
                            },
                        maxActions = maxActions
                    )

                else ->
                    null
            }

        if (plan == null) {
            return failure(
                "Неподдерживаемый type Android-цели: $type",
                terminalStatus = "UNSUPPORTED"
            )
        }

        return JSONObject()
            .put(
                "success",
                true
            )
            .put(
                "status",
                "compiled"
            )
            .put(
                "compiler_version",
                "2.0"
            )
            .put(
                "goal_type",
                type
            )
            .put(
                "execution_contract",
                executionContract(
                    goalType = type,
                    section = section
                )
            )
            .put(
                "plan",
                plan
            )
    }

    private fun compileOpenApp(
        app: String,
        maxActions: Int
    ): JSONObject? {

        if (app.isBlank()) {
            return null
        }

        return plan(
            goal =
                "Открыть приложение $app",
            maxActions =
                minOf(
                    maxActions,
                    2
                ),
            steps =
                listOf(
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

    private fun compileOpenSettingsSection(
        settingsSection: String,
        maxActions: Int
    ): JSONObject? {

        val canonical =
            canonicalSettingsSection(
                settingsSection
            )
                ?: return null

        return plan(
            goal =
                "Открыть системный раздел $canonical",
            maxActions =
                minOf(
                    maxActions,
                    2
                ),
            steps =
                listOf(
                    step(
                        id = "open_settings",
                        action = "open_settings",
                        section = canonical,
                        terminal = true,
                        requireScreenChange = true
                    )
                )
        )
    }

    private fun compileAppInfo(
        app: String,
        maxActions: Int
    ): JSONObject? {

        if (app.isBlank()) {
            return null
        }

        return plan(
            goal =
                "Открыть информацию о приложении $app",
            maxActions =
                minOf(
                    maxActions,
                    2
                ),
            steps =
                listOf(
                    step(
                        id = "open_app_info",
                        action = "open_app_info",
                        name = app,
                        terminal = true,
                        requireScreenChange = true,
                        expectAny =
                            listOf(
                                "Информация о приложении",
                                "App info"
                            ),
                        expectAll =
                            listOf(
                                app
                            )
                    )
                )
        )
    }

    private fun compileAppDetailSection(
        app: String,
        section: String,
        maxActions: Int
    ): JSONObject? {

        if (
            app.isBlank() ||
            section.isBlank()
        ) {
            return null
        }

        val directAppSettings =
            canonicalDirectAppSettings(
                section
            )

        if (directAppSettings != null) {
            return plan(
                goal =
                    "Открыть раздел $section приложения $app",
                maxActions =
                    minOf(
                        maxActions,
                        3
                    ),
                steps =
                    listOf(
                        step(
                            id = "open_app_settings",
                            action = "open_app_settings",
                            name = app,
                            section = directAppSettings,
                            terminal = true,
                            requireScreenChange = true
                        )
                    )
            )
        }

        val targets =
            appDetailTargets(
                section
            )

        if (targets.isEmpty()) {
            return null
        }

        return plan(
            goal =
                "Открыть раздел $section приложения $app",
            maxActions =
                minOf(
                    maxActions,
                    5
                ),
            steps =
                listOf(
                    step(
                        id = "open_app_info",
                        action = "open_app_info",
                        name = app,
                        requireScreenChange = true,
                        expectAny =
                            listOf(
                                "Информация о приложении",
                                "App info"
                            ),
                        expectAll =
                            listOf(
                                app
                            )
                    ),
                    step(
                        id = "open_app_detail",
                        action = "click_any",
                        targets = targets,
                        scrollIfMissing = true,
                        maxScrolls = 2,
                        terminal = true,
                        requireScreenChange = true
                    )
                )
        )
    }

    private fun compileAccessibilityServicePage(
        app: String,
        maxActions: Int
    ): JSONObject? {

        if (app.isBlank()) {
            return null
        }

        return plan(
            goal =
                "Открыть страницу службы специальных возможностей приложения $app",
            maxActions =
                minOf(
                    maxActions,
                    6
                ),
            steps =
                listOf(
                    step(
                        id = "open_accessibility",
                        action = "open_settings",
                        section = "accessibility",
                        requireScreenChange = true,
                        expectAny =
                            listOf(
                                "Специальные возможности",
                                "Accessibility"
                            )
                    ),
                    step(
                        id = "open_installed_services",
                        action = "click_any",
                        targets =
                            listOf(
                                "Установленные приложения",
                                "Установленные службы",
                                "Установленные сервисы",
                                "Installed apps",
                                "Installed services"
                            ),
                        scrollIfMissing = true,
                        maxScrolls = 1,
                        requireScreenChange = true
                    ),
                    step(
                        id = "open_accessibility_service",
                        action = "click_any",
                        targets =
                            listOf(
                                app
                            ),
                        scrollIfMissing = true,
                        maxScrolls = 2,
                        terminal = true,
                        requireScreenChange = true,
                        expectAll =
                            listOf(
                                app
                            )
                    )
                )
        )
    }

    private fun compileDefaultAppCategory(
        category: String,
        maxActions: Int
    ): JSONObject? {

        val targets =
            defaultAppCategoryTargets(
                category
            )

        if (targets.isEmpty()) {
            return null
        }

        return plan(
            goal =
                "Открыть категорию приложения по умолчанию: $category",
            maxActions =
                minOf(
                    maxActions,
                    4
                ),
            steps =
                listOf(
                    step(
                        id = "open_default_apps",
                        action = "open_settings",
                        section = "default_apps",
                        requireScreenChange = true,
                        expectAny =
                            listOf(
                                "Приложения по умолчанию",
                                "Default apps"
                            )
                    ),
                    step(
                        id = "open_default_category",
                        action = "click_any",
                        targets = targets,
                        scrollIfMissing = true,
                        maxScrolls = 1,
                        terminal = true,
                        requireScreenChange = true
                    )
                )
        )
    }

    private fun appDetailTargets(
        section: String
    ): List<String> {

        return when (
            canonicalSectionKey(
                section
            )
        ) {

            "permissions" ->
                listOf(
                    "Разрешения",
                    "Permissions"
                )

            "battery" ->
                listOf(
                    "Батарея",
                    "Использование батареи",
                    "Аккумулятор",
                    "Battery"
                )

            "storage" ->
                listOf(
                    "Хранилище",
                    "Память",
                    "Storage"
                )

            "mobile_data" ->
                listOf(
                    "Мобильные данные",
                    "Использование мобильных данных",
                    "Использование данных",
                    "Мобильный трафик",
                    "Mobile data",
                    "Data usage"
                )

            else ->
                emptyList()
        }
    }

    private fun canonicalDirectAppSettings(
        section: String
    ): String? {

        return when (
            canonicalSectionKey(
                section
            )
        ) {

            "notifications" ->
                "notifications"

            "open_by_default" ->
                "open_by_default"

            "language" ->
                "language"

            "info" ->
                "info"

            else ->
                null
        }
    }

    private fun canonicalSettingsSection(
        section: String
    ): String? {

        val key =
            canonicalSectionKey(
                section
            )

        return when (key) {
            "general" -> "general"
            "apps" -> "apps"
            "wifi" -> "wifi"
            "bluetooth" -> "bluetooth"
            "sound" -> "sound"
            "display" -> "display"
            "accessibility" -> "accessibility"
            "location" -> "location"
            "security" -> "security"
            "date_time" -> "date_time"
            "battery" -> "battery"
            "storage" -> "storage"
            "notifications" -> "notifications"
            "data_usage" -> "data_usage"
            "vpn" -> "vpn"
            "nfc" -> "nfc"
            "language" -> "language"
            "keyboard" -> "keyboard"
            "default_apps" -> "default_apps"
            "developer_options" -> "developer_options"
            "device_info" -> "device_info"
            "privacy" -> "privacy"
            "battery_optimization" -> "battery_optimization"
            else -> null
        }
    }

    private fun defaultAppCategoryTargets(
        category: String
    ): List<String> {

        return when (
            canonicalSectionKey(
                category
            )
        ) {

            "browser" ->
                listOf(
                    "Браузер",
                    "Приложение браузера",
                    "Browser app",
                    "Browser"
                )

            "home" ->
                listOf(
                    "Главный экран",
                    "Домашний экран",
                    "Home app"
                )

            "phone" ->
                listOf(
                    "Звонки",
                    "Телефон",
                    "Phone app"
                )

            "sms" ->
                listOf(
                    "SMS",
                    "Сообщения",
                    "SMS app"
                )

            "assistant" ->
                listOf(
                    "Цифровой помощник",
                    "Помощник",
                    "Digital assistant app",
                    "Assistant app"
                )

            "links" ->
                listOf(
                    "Открытие ссылок",
                    "Opening links"
                )

            else ->
                emptyList()
        }
    }

    private fun canonicalSectionKey(
        raw: String
    ): String {

        val value =
            normalize(
                raw
            )

        return when {

            value in
                setOf(
                    "permissions",
                    "permission",
                    "разрешения",
                    "разрешение"
                ) ->
                "permissions"

            value.contains(
                "батар"
            ) ||
                value.contains(
                    "аккумуля"
                ) ||
                value == "battery" ->
                "battery"

            value.contains(
                "хранилищ"
            ) ||
                value == "storage" ||
                value == "память" ->
                "storage"

            value.contains(
                "мобильн"
            ) &&
                value.contains(
                    "данн"
                ) ||
                value.contains(
                    "трафик"
                ) ||
                value == "data_usage" ||
                value == "mobile_data" ->
                "mobile_data"

            value.contains(
                "уведом"
            ) ||
                value == "notifications" ->
                "notifications"

            value.contains(
                "по умолч"
            ) &&
                value.contains(
                    "откры"
                ) ||
                value == "open_by_default" ->
                "open_by_default"

            value.contains(
                "язык"
            ) ||
                value == "language" ->
                "language"

            value in
                setOf(
                    "info",
                    "app_info",
                    "информация",
                    "информация о приложении"
                ) ->
                "info"

            value in
                setOf(
                    "general",
                    "общие",
                    "общие настройки"
                ) ->
                "general"

            value in
                setOf(
                    "apps",
                    "приложения"
                ) ->
                "apps"

            value in
                setOf(
                    "wifi",
                    "wi-fi",
                    "вайфай",
                    "вай фай"
                ) ->
                "wifi"

            value.contains(
                "bluetooth"
            ) ||
                value.contains(
                    "блютуз"
                ) ->
                "bluetooth"

            value.contains(
                "звук"
            ) ||
                value == "sound" ->
                "sound"

            value.contains(
                "экран"
            ) ||
                value.contains(
                    "диспле"
                ) ||
                value == "display" ->
                "display"

            value.contains(
                "специальн"
            ) ||
                value.contains(
                    "accessibility"
                ) ->
                "accessibility"

            value.contains(
                "местополож"
            ) ||
                value.contains(
                    "геолока"
                ) ||
                value == "location" ->
                "location"

            value.contains(
                "безопас"
            ) ||
                value == "security" ->
                "security"

            value.contains(
                "дата"
            ) &&
                value.contains(
                    "время"
                ) ||
                value == "date_time" ->
                "date_time"

            value == "data_usage" ||
                value == "использование данных" ->
                "data_usage"

            value == "vpn" ||
                value == "впн" ->
                "vpn"

            value == "nfc" ||
                value == "нфс" ->
                "nfc"

            value.contains(
                "клавиатур"
            ) ||
                value == "keyboard" ->
                "keyboard"

            value.contains(
                "приложения по умолчанию"
            ) ||
                value == "default_apps" ->
                "default_apps"

            value.contains(
                "разработчик"
            ) ||
                value == "developer_options" ->
                "developer_options"

            value.contains(
                "об устройстве"
            ) ||
                value.contains(
                    "о планшете"
                ) ||
                value == "device_info" ->
                "device_info"

            value.contains(
                "конфиденц"
            ) ||
                value.contains(
                    "приват"
                ) ||
                value == "privacy" ->
                "privacy"

            value.contains(
                "оптимизац"
            ) &&
                value.contains(
                    "батар"
                ) ||
                value == "battery_optimization" ->
                "battery_optimization"

            value in
                setOf(
                    "browser",
                    "браузер",
                    "приложение браузера"
                ) ->
                "browser"

            value in
                setOf(
                    "home",
                    "главный экран",
                    "домашний экран"
                ) ->
                "home"

            value in
                setOf(
                    "phone",
                    "телефон",
                    "звонки"
                ) ->
                "phone"

            value in
                setOf(
                    "sms",
                    "сообщения"
                ) ->
                "sms"

            value.contains(
                "помощник"
            ) ||
                value.contains(
                    "assistant"
                ) ->
                "assistant"

            value.contains(
                "ссыл"
            ) ||
                value == "links" ->
                "links"

            else ->
                value
        }
    }

    private fun plan(
        goal: String,
        maxActions: Int,
        steps: List<JSONObject>
    ): JSONObject {

        val array =
            JSONArray()

        steps.forEach {
            array.put(
                it
            )
        }

        return JSONObject()
            .put(
                "goal",
                goal
            )
            .put(
                "max_actions",
                maxActions
            )
            .put(
                "steps",
                array
            )
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
    ): JSONObject {

        return JSONObject()
            .put(
                "id",
                id
            )
            .put(
                "action",
                action
            )
            .put(
                "section",
                section
            )
            .put(
                "name",
                name
            )
            .put(
                "volume_action",
                volumeAction
            )
            .put(
                "targets",
                JSONArray(
                    targets
                )
            )
            .put(
                "scroll_if_missing",
                scrollIfMissing
            )
            .put(
                "max_scrolls",
                maxScrolls
            )
            .put(
                "scroll_direction",
                scrollDirection
            )
            .put(
                "direction",
                direction
            )
            .put(
                "target",
                target
            )
            .put(
                "text",
                text
            )
            .put(
                "sensitive",
                sensitive
            )
            .put(
                "allow_overflow",
                allowOverflow
            )
            .put(
                "optional",
                optional
            )
            .put(
                "terminal",
                terminal
            )
            .put(
                "require_screen_change",
                requireScreenChange
            )
            .put(
                "expect_any",
                JSONArray(
                    expectAny
                )
            )
            .put(
                "expect_all",
                JSONArray(
                    expectAll
                )
            )
            .put(
                "expect_none",
                JSONArray(
                    expectNone
                )
            )
    }

    private fun executionContract(
        goalType: String,
        section: String
    ): JSONObject {

        val normalizedSection = normalize(section)

        val executorKey =
            when (goalType) {
                "open_app" -> "app_launch_executor"
                "open_settings_section" -> "system_settings_executor"
                "app_info" -> "app_info_executor"
                "app_detail_section" ->
                    when (normalizedSection) {
                        "notifications" -> "app_notifications_executor"
                        "permissions" -> "app_permissions_executor"
                        "battery" -> "app_battery_executor"
                        "storage" -> "app_storage_executor"
                        "mobile_data" -> "app_mobile_data_executor"
                        "open_by_default" -> "app_defaults_executor"
                        "language" -> "app_language_executor"
                        else -> "app_detail_executor"
                    }
                "accessibility_service_page" -> "accessibility_settings_executor"
                "default_app_category" -> "default_apps_executor"
                else -> "unsupported_executor"
            }

        val verificationPolicy =
            when (goalType) {
                "open_app" -> "fresh_foreground_package"
                "app_info", "app_detail_section" -> "settings_intent_attestation_plus_same_window_evidence"
                "open_settings_section", "accessibility_service_page", "default_app_category" -> "fresh_same_window_terminal_evidence"
                else -> "fail_closed"
            }

        return JSONObject()
            .put("executor_key", executorKey)
            .put("terminal_policy", "verified_terminal_only")
            .put("verification_policy", verificationPolicy)
            .put("cancellation_required", true)
            .put("replan_policy", "bounded_safe_replan")
            .put("false_success_allowed", false)
    }

    private fun defaultBudgetFor(
        type: String
    ): Int {

        return when (type) {
            "open_app",
            "open_settings_section",
            "app_info" ->
                2

            "app_detail_section",
            "default_app_category" ->
                5

            "accessibility_service_page" ->
                6

            else ->
                DEFAULT_MAX_ACTIONS
        }
    }

    private fun failure(
        message: String,
        terminalStatus: String = "ERROR"
    ): JSONObject {

        return JSONObject()
            .put(
                "success",
                false
            )
            .put(
                "status",
                if (terminalStatus == "UNSUPPORTED") "unsupported_goal" else if (terminalStatus == "BLOCKED") "blocked_goal" else "invalid_goal"
            )
            .put(
                "terminal_status",
                terminalStatus
            )
            .put(
                "message",
                message
            )
    }

    private fun normalize(
        value: String
    ): String {

        return value
            .lowercase(
                Locale.ROOT
            )
            .replace(
                'ё',
                'е'
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    companion object {

        private const val DEFAULT_MAX_ACTIONS =
            5

        private const val HARD_MAX_ACTIONS =
            8
    }
}

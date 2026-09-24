package kg.autonomous.agent

import java.util.Locale

/**
 * AYANA R9.3 App Integration Registry v1.0.
 *
 * One machine-readable contract for deterministic cross-app actions.  The registry
 * does not execute Android actions; AyanaVoiceService remains the executor and owns
 * terminal verification/recovery.  Hard-coded package names are hints only and must
 * be validated against AyanaAppResolver's observed launcher map before use.
 *
 * Initial integrations:
 * - Samsung My Files
 * - Samsung Gallery
 * - Samsung Internet / Chrome
 * - YouTube
 * - Calendar
 *
 * Safety model:
 * - OPEN / SEARCH / OPEN_URL are navigation-only;
 * - FIND_LOCAL is delegated to AYANA Personal Search, never to brittle UI tapping;
 * - CREATE_EVENT_DRAFT only opens a calendar editor; it never claims the event was saved;
 * - no send/delete/payment/account mutation is exposed by v1.0.
 */
class AyanaAppIntegrationRegistry {

    enum class CommitSemantics {
        READ_ONLY,
        NAVIGATION,
        DRAFT_ONLY,
        MUTATION
    }

    enum class VerificationMode {
        FOREGROUND_PACKAGE,
        EXTERNAL_PACKAGE,
        LOCAL_RESULT,
        DRAFT_FOREGROUND
    }

    data class ActionSpec(
        val key: String,
        val label: String,
        val executor: String,
        val commitSemantics: CommitSemantics,
        val verificationMode: VerificationMode,
        val requiresConfirmation: Boolean = false,
        val autonomousAllowed: Boolean = true
    )

    data class AppSpec(
        val key: String,
        val displayName: String,
        val aliases: List<String>,
        val preferredPackages: List<String>,
        val actions: List<ActionSpec>
    )

    data class ParsedCommand(
        val appKey: String,
        val actionKey: String,
        val payload: String = "",
        val source: String
    )

    private val apps: List<AppSpec> =
        listOf(
            AppSpec(
                key = APP_FILES,
                displayName = "Мои файлы",
                aliases = listOf("мои файлы", "файлы", "my files"),
                preferredPackages = listOf("com.sec.android.app.myfiles"),
                actions = listOf(
                    ActionSpec(
                        key = ACTION_OPEN,
                        label = "Открыть приложение",
                        executor = "app_resolver",
                        commitSemantics = CommitSemantics.NAVIGATION,
                        verificationMode = VerificationMode.FOREGROUND_PACKAGE
                    ),
                    ActionSpec(
                        key = ACTION_FIND_LOCAL,
                        label = "Найти файл/документ локально",
                        executor = "personal_search",
                        commitSemantics = CommitSemantics.READ_ONLY,
                        verificationMode = VerificationMode.LOCAL_RESULT
                    )
                )
            ),
            AppSpec(
                key = APP_GALLERY,
                displayName = "Галерея",
                aliases = listOf("галерея", "галерею", "gallery", "фото галерея"),
                preferredPackages = listOf("com.sec.android.gallery3d"),
                actions = listOf(
                    ActionSpec(
                        key = ACTION_OPEN,
                        label = "Открыть приложение",
                        executor = "app_resolver",
                        commitSemantics = CommitSemantics.NAVIGATION,
                        verificationMode = VerificationMode.FOREGROUND_PACKAGE
                    ),
                    ActionSpec(
                        key = ACTION_FIND_LOCAL,
                        label = "Найти фото локально",
                        executor = "personal_search",
                        commitSemantics = CommitSemantics.READ_ONLY,
                        verificationMode = VerificationMode.LOCAL_RESULT
                    )
                )
            ),
            AppSpec(
                key = APP_BROWSER,
                displayName = "Браузер",
                aliases = listOf(
                    "браузер",
                    "browser",
                    "samsung browser",
                    "samsung internet",
                    "chrome",
                    "хром"
                ),
                preferredPackages = listOf(
                    "com.sec.android.app.sbrowser",
                    "com.android.chrome"
                ),
                actions = listOf(
                    ActionSpec(
                        key = ACTION_OPEN,
                        label = "Открыть браузер",
                        executor = "app_resolver",
                        commitSemantics = CommitSemantics.NAVIGATION,
                        verificationMode = VerificationMode.FOREGROUND_PACKAGE
                    ),
                    ActionSpec(
                        key = ACTION_SEARCH,
                        label = "Открыть результаты веб-поиска",
                        executor = "intent",
                        commitSemantics = CommitSemantics.NAVIGATION,
                        verificationMode = VerificationMode.EXTERNAL_PACKAGE
                    ),
                    ActionSpec(
                        key = ACTION_OPEN_URL,
                        label = "Открыть URL",
                        executor = "intent",
                        commitSemantics = CommitSemantics.NAVIGATION,
                        verificationMode = VerificationMode.EXTERNAL_PACKAGE
                    )
                )
            ),
            AppSpec(
                key = APP_YOUTUBE,
                displayName = "YouTube",
                aliases = listOf("youtube", "ютуб", "ютьюб"),
                preferredPackages = listOf("com.google.android.youtube"),
                actions = listOf(
                    ActionSpec(
                        key = ACTION_OPEN,
                        label = "Открыть YouTube",
                        executor = "app_resolver",
                        commitSemantics = CommitSemantics.NAVIGATION,
                        verificationMode = VerificationMode.FOREGROUND_PACKAGE
                    ),
                    ActionSpec(
                        key = ACTION_SEARCH,
                        label = "Открыть результаты поиска YouTube",
                        executor = "intent",
                        commitSemantics = CommitSemantics.NAVIGATION,
                        verificationMode = VerificationMode.FOREGROUND_PACKAGE
                    )
                )
            ),
            AppSpec(
                key = APP_CALENDAR,
                displayName = "Календарь",
                aliases = listOf("календарь", "calendar"),
                preferredPackages = listOf(
                    "com.samsung.android.calendar",
                    "com.google.android.calendar"
                ),
                actions = listOf(
                    ActionSpec(
                        key = ACTION_OPEN,
                        label = "Открыть календарь",
                        executor = "app_resolver",
                        commitSemantics = CommitSemantics.NAVIGATION,
                        verificationMode = VerificationMode.FOREGROUND_PACKAGE
                    ),
                    ActionSpec(
                        key = ACTION_CREATE_EVENT_DRAFT,
                        label = "Открыть черновик события с заголовком",
                        executor = "calendar_insert_intent",
                        commitSemantics = CommitSemantics.DRAFT_ONLY,
                        verificationMode = VerificationMode.DRAFT_FOREGROUND,
                        requiresConfirmation = false,
                        autonomousAllowed = true
                    )
                )
            )
        )

    fun allApps(): List<AppSpec> = apps

    fun app(
        key: String
    ): AppSpec? =
        apps.firstOrNull {
            it.key == normalizeKey(key)
        }

    fun resolveAlias(
        value: String
    ): AppSpec? {
        val normalized = normalize(value)
        if (normalized.isBlank()) return null

        return apps.firstOrNull { spec ->
            normalize(spec.displayName) == normalized ||
                normalize(spec.key) == normalized ||
                spec.aliases.any { normalize(it) == normalized }
        }
    }

    fun action(
        appKey: String,
        actionKey: String
    ): ActionSpec? =
        app(appKey)
            ?.actions
            ?.firstOrNull {
                it.key == normalizeKey(actionKey)
            }

    fun describe(
        appKey: String
    ): String {
        val spec = app(appKey) ?: return "Интеграция приложения не зарегистрирована."
        val actionText =
            spec.actions.joinToString("; ") { it.label }

        return "${spec.displayName}: $actionText. " +
            "R9.3 выполняет только зарегистрированные действия и требует проверяемого результата."
    }

    /**
     * Conservative command parser for deterministic local actions.  Personal file/photo
     * search intentionally remains owned by AyanaPersonalSearchEngine and therefore is
     * not claimed here.
     */
    fun parse(
        command: String
    ): ParsedCommand? {
        val clean =
            command
                .trim()
                .replace(Regex("\\s+"), " ")

        val normalized = normalize(clean)
        if (normalized.isBlank()) return null

        capabilityQuery(normalized)?.let { appKey ->
            return ParsedCommand(
                appKey = appKey,
                actionKey = ACTION_DESCRIBE,
                source = "capability_query"
            )
        }

        val calendarDraftPatterns =
            listOf(
                Regex(
                    """^(?:создай|добавь|подготовь)\s+(?:черновик\s+)?(?:событие|мероприятие)\s+(?:в\s+календар(?:е|ь)\s+)?(.+)$""",
                    RegexOption.IGNORE_CASE
                ),
                Regex(
                    """^(?:в\s+календар(?:ь|е)\s+)(?:создай|добавь|подготовь)\s+(?:черновик\s+)?(?:событие|мероприятие)\s+(.+)$""",
                    RegexOption.IGNORE_CASE
                )
            )

        calendarDraftPatterns.forEach { regex ->
            val match = regex.matchEntire(clean)
            val title = match?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (title.isNotBlank()) {
                return ParsedCommand(
                    appKey = APP_CALENDAR,
                    actionKey = ACTION_CREATE_EVENT_DRAFT,
                    payload = title,
                    source = "calendar_draft"
                )
            }
        }

        Regex(
            """^(?:найди|поищи|поиск)\s+(?:в\s+)?(?:youtube|ютубе|ютьюбе|ютуб|ютьюб)\s+(.+)$""",
            RegexOption.IGNORE_CASE
        )
            .matchEntire(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { query ->
                return ParsedCommand(
                    appKey = APP_YOUTUBE,
                    actionKey = ACTION_SEARCH,
                    payload = query,
                    source = "youtube_search"
                )
            }

        Regex(
            """^(?:найди|поищи|поиск)\s+(?:в\s+)?(?:браузере|интернете|google|гугле|chrome|хроме)\s+(.+)$""",
            RegexOption.IGNORE_CASE
        )
            .matchEntire(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { query ->
                return ParsedCommand(
                    appKey = APP_BROWSER,
                    actionKey = ACTION_SEARCH,
                    payload = query,
                    source = "browser_search"
                )
            }

        Regex(
            """^(?:открой|перейди\s+на|зайди\s+на)\s+(?:сайт\s+)?(.+)$""",
            RegexOption.IGNORE_CASE
        )
            .matchEntire(clean)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { looksLikeUrlTarget(it.lowercase(Locale.ROOT)) }
            ?.let { target ->
                return ParsedCommand(
                    appKey = APP_BROWSER,
                    actionKey = ACTION_OPEN_URL,
                    payload = target,
                    source = "browser_url"
                )
            }

        val openMatch =
            Regex(
                """^(?:открой|запусти|покажи)\s+(.+)$""",
                RegexOption.IGNORE_CASE
            )
                .matchEntire(clean)

        if (openMatch != null) {
            val requested = openMatch.groupValues[1].trim()
            val spec = resolveAlias(requested)
            if (spec != null) {
                return ParsedCommand(
                    appKey = spec.key,
                    actionKey = ACTION_OPEN,
                    source = "registered_app_open"
                )
            }
        }

        return null
    }

    fun selfTest(): Boolean {
        val keys = apps.map { it.key }.toSet()
        if (keys != setOf(APP_FILES, APP_GALLERY, APP_BROWSER, APP_YOUTUBE, APP_CALENDAR)) {
            return false
        }

        if (apps.any { it.actions.isEmpty() }) return false
        if (apps.any { it.preferredPackages.isEmpty() }) return false
        if (apps.flatMap { it.actions }.any { it.commitSemantics == CommitSemantics.MUTATION }) {
            return false
        }

        val youtube = parse("найди в YouTube новости")
        if (youtube?.appKey != APP_YOUTUBE || youtube.actionKey != ACTION_SEARCH || youtube.payload != "новости") {
            return false
        }

        val browser = parse("открой сайт example.com")
        if (browser?.appKey != APP_BROWSER || browser.actionKey != ACTION_OPEN_URL) {
            return false
        }

        val calendar = parse("создай событие в календаре встреча с командой")
        if (calendar?.appKey != APP_CALENDAR || calendar.actionKey != ACTION_CREATE_EVENT_DRAFT) {
            return false
        }

        val gallery = parse("открой галерею")
        if (gallery?.appKey != APP_GALLERY || gallery.actionKey != ACTION_OPEN) {
            return false
        }

        // Local file/photo search must remain owned by Personal Search.
        if (parse("найди файл отчет") != null) return false
        if (parse("найди фото паспорт") != null) return false

        return true
    }

    private fun capabilityQuery(
        normalized: String
    ): String? {
        val prefixes =
            listOf(
                "что умеешь в ",
                "что ты умеешь в ",
                "что можешь в ",
                "что ты можешь в ",
                "какие действия доступны в "
            )

        val tail =
            prefixes
                .firstOrNull { normalized.startsWith(it) }
                ?.let { normalized.removePrefix(it).trim() }
                ?: return null

        return resolveAlias(tail)?.key
    }

    private fun looksLikeUrlTarget(
        value: String
    ): Boolean {
        val v = value.trim()
        if (v.startsWith("http://") || v.startsWith("https://")) return true
        if (v.contains(" ")) return false
        return Regex("""^[a-z0-9][a-z0-9.-]*\.[a-z]{2,}(?:/.*)?$""")
            .matches(v)
    }

    private fun normalizeKey(
        value: String
    ): String =
        value
            .trim()
            .lowercase(Locale.ROOT)

    private fun normalize(
        value: String
    ): String =
        value
            .trim()
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("\\s+"), " ")

    companion object {
        const val VERSION = "1.0"

        const val APP_FILES = "files"
        const val APP_GALLERY = "gallery"
        const val APP_BROWSER = "browser"
        const val APP_YOUTUBE = "youtube"
        const val APP_CALENDAR = "calendar"

        const val ACTION_DESCRIBE = "describe"
        const val ACTION_OPEN = "open"
        const val ACTION_FIND_LOCAL = "find_local"
        const val ACTION_SEARCH = "search"
        const val ACTION_OPEN_URL = "open_url"
        const val ACTION_CREATE_EVENT_DRAFT = "create_event_draft"
    }
}

package kg.autonomous.agent

import java.util.Locale

/**
 * AYANA Project Command Router v1.0 — R10.28.2.
 *
 * Deterministic project control only. No Agent Core round-trip is required for
 * create/list/current/switch/leave/self-test commands.
 *
 * Destructive delete is intentionally not implemented in this slice.
 */
class AyanaProjectCommandRouter(
    private val store: AyanaProjectStore,
    private val contextManager: AyanaProjectContextManager
) {

    data class Result(
        val handled: Boolean,
        val success: Boolean,
        val terminalStatus: String,
        val message: String,
        val technical: String
    )

    fun isCandidate(command: String): Boolean {
        val n = normalize(command)

        return isSelfTest(n) ||
            isList(n) ||
            isCurrent(n) ||
            isLeave(n) ||
            extractCreateName(command) != null ||
            extractSwitchName(command) != null
    }

    fun tryHandle(command: String): Result {
        val n = normalize(command)

        if (isSelfTest(n)) {
            val storeOk =
                try {
                    store.selfTest()
                } catch (_: Exception) {
                    false
                }

            val contextOk =
                try {
                    contextManager.selfTest()
                } catch (_: Exception) {
                    false
                }

            val snapshot =
                try {
                    store.snapshot()
                } catch (_: Exception) {
                    null
                }

            val ok =
                storeOk &&
                    contextOk &&
                    snapshot != null

            val activeName =
                snapshot
                    ?.optString(
                        "active_project_name",
                        ""
                    )
                    .orEmpty()

            val count =
                snapshot
                    ?.optInt(
                        "projects_active_count",
                        0
                    )
                    ?: 0

            return Result(
                handled = true,
                success = ok,
                terminalStatus = if (ok) "SUCCESS" else "ERROR",
                message =
                    if (ok) {
                        if (activeName.isBlank()) {
                            "Проекты AYANA прошли локальный self-test. Проектов: $count. Активного проекта нет."
                        } else {
                            "Проекты AYANA прошли локальный self-test. Проектов: $count. Активный проект: $activeName."
                        }
                    } else {
                        "Локальный self-test проектов AYANA не пройден."
                    },
                technical =
                    "r10_28_projects_self_test=$ok; " +
                        "project_store_self_test=$storeOk; " +
                        "project_context_self_test=$contextOk; " +
                        "project_store_version=${AyanaProjectStore.VERSION}; " +
                        "project_context_version=${AyanaProjectContextManager.VERSION}; " +
                        "projects=$count; " +
                        "active_project_id=${store.activeProjectId().orEmpty()}; " +
                        "cross_project_default=blocked"
            )
        }

        extractCreateName(command)?.let { name ->
            val result =
                store.create(
                    name = name,
                    makeActive = true
                )

            return Result(
                handled = true,
                success = result.success,
                terminalStatus =
                    if (result.success) {
                        "SUCCESS"
                    } else {
                        "ERROR"
                    },
                message =
                    if (result.success) {
                        "Проект «${result.project?.name.orEmpty()}» создан и открыт."
                    } else {
                        when (result.reason) {
                            "project_name_already_exists" ->
                                "Проект с таким названием уже существует."
                            "project_name_empty" ->
                                "Не указано название проекта."
                            else ->
                                "Не удалось создать проект."
                        }
                    },
                technical =
                    "r10_28_project_create=${result.success}; " +
                        "reason=${result.reason}; " +
                        "project_id=${result.project?.projectId.orEmpty()}; " +
                        "active_project_id=${result.activeProjectId.orEmpty()}; " +
                        "project_isolation=true"
            )
        }

        if (isList(n)) {
            val projects =
                store.list(
                    includeArchived = false
                )

            val activeId =
                store.activeProjectId()

            val message =
                if (projects.isEmpty()) {
                    "Проектов пока нет."
                } else {
                    buildString {
                        append("Проекты: ")
                        projects.forEachIndexed { index, project ->
                            if (index > 0) append("; ")
                            if (project.projectId == activeId) {
                                append("активный — ")
                            }
                            append(project.name)
                        }
                        append(".")
                    }
                }

            return Result(
                handled = true,
                success = true,
                terminalStatus = "SUCCESS",
                message = message,
                technical =
                    "r10_28_project_list=true; " +
                        "projects=${projects.size}; " +
                        "active_project_id=${activeId.orEmpty()}"
            )
        }

        if (isCurrent(n)) {
            val active =
                store.activeProject()

            return Result(
                handled = true,
                success = true,
                terminalStatus = "SUCCESS",
                message =
                    if (active == null) {
                        "Активного проекта нет. AYANA работает в глобальном контексте."
                    } else {
                        "Текущий проект: «${active.name}»."
                    },
                technical =
                    "r10_28_project_current=true; " +
                        "active_project_id=${active?.projectId.orEmpty()}; " +
                        "active_project_name=${active?.name.orEmpty()}; " +
                        "scope=${if (active == null) "GLOBAL" else "CURRENT_PROJECT"}"
            )
        }

        if (isLeave(n)) {
            val before =
                store.activeProject()

            val result =
                store.clearActive()

            return Result(
                handled = true,
                success = result.success,
                terminalStatus = if (result.success) "SUCCESS" else "ERROR",
                message =
                    if (result.success) {
                        if (before == null) {
                            "Активного проекта не было. AYANA уже работает в глобальном контексте."
                        } else {
                            "Проект «${before.name}» закрыт. AYANA перешла в глобальный контекст."
                        }
                    } else {
                        "Не удалось выйти из проекта."
                    },
                technical =
                    "r10_28_project_leave=${result.success}; " +
                        "previous_project_id=${before?.projectId.orEmpty()}; " +
                        "active_project_id=; scope=GLOBAL"
            )
        }

        extractSwitchName(command)?.let { name ->
            val result =
                store.switchActiveByName(
                    name
                )

            return Result(
                handled = true,
                success = result.success,
                terminalStatus =
                    if (result.success) {
                        "SUCCESS"
                    } else {
                        "ERROR"
                    },
                message =
                    if (result.success) {
                        "Открыт проект «${result.project?.name.orEmpty()}»."
                    } else {
                        when (result.reason) {
                            "project_archived" ->
                                "Этот проект архивирован."
                            else ->
                                "Проект «$name» не найден."
                        }
                    },
                technical =
                    "r10_28_project_switch=${result.success}; " +
                        "reason=${result.reason}; " +
                        "project_id=${result.project?.projectId.orEmpty()}; " +
                        "active_project_id=${result.activeProjectId.orEmpty()}; " +
                        "project_isolation=true"
            )
        }

        return Result(
            handled = false,
            success = false,
            terminalStatus = "UNSUPPORTED",
            message = "",
            technical = ""
        )
    }

    private fun isSelfTest(n: String): Boolean =
        n in setOf(
            "проверь проекты",
            "проверь проекты аяна",
            "протестируй проекты",
            "проверь изоляцию проектов"
        )

    private fun isList(n: String): Boolean =
        n in setOf(
            "покажи проекты",
            "покажи все проекты",
            "список проектов",
            "какие проекты есть",
            "перечисли проекты"
        )

    private fun isCurrent(n: String): Boolean =
        n in setOf(
            "текущий проект",
            "какой проект активен",
            "какой сейчас проект",
            "покажи текущий проект",
            "где я сейчас работаю"
        )

    private fun isLeave(n: String): Boolean =
        n in setOf(
            "выйди из проекта",
            "закрой текущий проект",
            "перейди в глобальный контекст",
            "работай без проекта"
        )

    private fun extractCreateName(command: String): String? {
        val clean =
            cleanOriginal(command)

        val match =
            Regex(
                """^(?:создай|создать|добавь|добавить)\s+(?:новый\s+)?проект\s+(.+)$""",
                RegexOption.IGNORE_CASE
            )
                .find(clean)
                ?: Regex(
                    """^новый\s+проект\s+(.+)$""",
                    RegexOption.IGNORE_CASE
                )
                    .find(clean)
                ?: return null

        return cleanProjectName(
            match.groupValues[1]
        )
    }

    private fun extractSwitchName(command: String): String? {
        val clean =
            cleanOriginal(command)

        val match =
            Regex(
                """^(?:открой|открыть|переключись\s+на|переключиться\s+на|перейди\s+в|перейти\s+в|выбери|выбрать)\s+проект\s+(.+)$""",
                RegexOption.IGNORE_CASE
            )
                .find(clean)
                ?: return null

        return cleanProjectName(
            match.groupValues[1]
        )
    }

    private fun cleanProjectName(value: String): String? {
        val clean =
            value
                .trim()
                .trim(' ', '.', ',', '!', '?', ';', ':', '…')
                .removeSurrounding("«", "»")
                .removeSurrounding("\"")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .take(120)

        return clean.takeIf {
            it.isNotBlank()
        }
    }

    private fun cleanOriginal(value: String): String =
        value
            .trim()
            .replace(Regex("""\s+"""), " ")
            .trimEnd('.', ',', '!', '?', ';', ':', '…')
            .trim()

    private fun normalize(value: String): String =
        cleanOriginal(value)
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("""\s+"""), " ")
            .trim()

    companion object {
        const val VERSION = "1.0"
    }
}

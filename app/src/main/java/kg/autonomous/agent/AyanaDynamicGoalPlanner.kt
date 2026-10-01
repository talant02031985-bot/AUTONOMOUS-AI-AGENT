package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * AYANA Dynamic Goal Planner v1.2 — R10.19 AUTONOMOUS MULTI-APP TASKS 2.0.
 *
 * R10.19 keeps the R10.11 verified-failure suffix-replan contract and expands the
 * registered production planner with three already-authorized runtime primitives:
 * verified App Info navigation, Universal UI exact-target click, and Calendar DRAFT_ONLY.
 * The planner itself still never dispatches Android actions or widens authority.
 *
 * The dedicated R10.19 acceptance DAG contains eight required subgoals across Browser,
 * YouTube, Samsung Settings and Calendar. A verified Browser title is transferred to both
 * YouTube search and Calendar draft, while the Settings click is executed only through the
 * accepted R10.18 Universal UI Action Engine. All candidates still pass compile().
 *
 * Pure planning/policy component. It never dispatches Android actions and never grants
 * execution authority. It converts a bounded free-form objective into a candidate DAG,
 * or validates an externally proposed DAG, before AyanaLongObjectiveCoordinator owns
 * execution state.
 *
 * Security / truth guarantees:
 * - only registered executors may appear in a plan;
 * - every executor has one exact lane + authority binding;
 * - no model/proposal field can widen that binding;
 * - duplicate ids, unknown dependencies, self-dependencies and cycles fail closed;
 * - result consumers must depend on the verified producer that owns the result key;
 * - plan size/dependency/result bounds are enforced before dispatch;
 * - local decomposition is explicitly bounded to registered primitives;
 * - arbitrary unsupported objectives return unsupported rather than fabricated plans.
 */
class AyanaDynamicGoalPlanner private constructor(
    private val objectiveValue: String,
    private val sourceValue: String,
    private val subgoalsValue: List<PlannedSubgoal>,
    private val fingerprintValue: String
) {

    data class PlannedSubgoal(
        val id: String,
        val title: String,
        val executor: String,
        val lane: String,
        val authority: String,
        val dependencies: List<String>,
        val resultKey: String,
        val required: Boolean,
        val arguments: JSONObject
    )

    data class ExecutorContract(
        val executor: String,
        val lane: String,
        val authority: String,
        val mayMutateRuntime: Boolean,
        val mayProduceResult: Boolean,
        val mayConsumeResult: Boolean
    )

    fun objective(): String = objectiveValue

    fun source(): String = sourceValue

    fun subgoalCount(): Int = subgoalsValue.size

    fun planFingerprint(): String = fingerprintValue

    fun subgoals(): List<PlannedSubgoal> =
        subgoalsValue.map {
            it.copy(arguments = JSONObject(it.arguments.toString()))
        }

    fun toLongObjectiveSpecs(): List<AyanaLongObjectiveCoordinator.SubgoalSpec> =
        subgoalsValue.map {
            AyanaLongObjectiveCoordinator.SubgoalSpec(
                id = it.id,
                title = it.title,
                lane = it.lane,
                authority = it.authority,
                dependencies = it.dependencies,
                resultKey = it.resultKey,
                required = it.required
            )
        }

    fun argumentsFor(subgoalId: String): JSONObject {
        val id = normalizeId(subgoalId)
        val match = subgoalsValue.firstOrNull { it.id == id }
            ?: return JSONObject()
        return JSONObject(match.arguments.toString())
    }

    fun executorFor(subgoalId: String): String =
        subgoalsValue.firstOrNull { it.id == normalizeId(subgoalId) }
            ?.executor
            .orEmpty()

    fun snapshot(): JSONObject {
        val array = JSONArray()
        for (subgoal in subgoalsValue) {
            array.put(
                JSONObject()
                    .put("id", subgoal.id)
                    .put("title", subgoal.title)
                    .put("executor", subgoal.executor)
                    .put("lane", subgoal.lane)
                    .put("authority", subgoal.authority)
                    .put("dependencies", JSONArray(subgoal.dependencies))
                    .put("result_key", subgoal.resultKey)
                    .put("required", subgoal.required)
                    .put("arguments", JSONObject(subgoal.arguments.toString()))
            )
        }

        return JSONObject()
            .put("version", VERSION)
            .put("snapshot_version", SNAPSHOT_VERSION)
            .put("objective", objectiveValue)
            .put("source", sourceValue)
            .put("subgoals", array)
            .put("subgoal_count", subgoalsValue.size)
            .put("plan_fingerprint", fingerprintValue)
    }

    fun compactSummary(): String =
        "dynamic_planner=v$VERSION; source=$sourceValue; subgoals=${subgoalsValue.size}; fingerprint=$fingerprintValue"

    companion object {
        const val VERSION = "1.2"
        const val SNAPSHOT_VERSION = 1

        const val EXEC_DEVICE_STATE = "get_device_state"
        const val EXEC_ANDROID_GOAL = "execute_android_goal"
        const val EXEC_BROWSER_OPEN_URL = "browser_open_url"
        const val EXEC_STRUCTURED_SCREEN_READ = "structured_screen_read"
        const val EXEC_PARTIAL_RESULT_CHECK = "partial_result_check"
        const val EXEC_YOUTUBE_SEARCH = "youtube_search"
        const val EXEC_APP_INFO = "app_info"
        const val EXEC_UNIVERSAL_UI_CLICK = "universal_ui_click"
        const val EXEC_CALENDAR_DRAFT = "calendar_draft"

        private const val MAX_SUBGOALS = 12
        private const val MAX_DEPENDENCIES = 8
        private const val MAX_OBJECTIVE_CHARS = 1400
        private const val MAX_TITLE_CHARS = 240
        private const val MAX_ARGUMENT_TEXT_CHARS = 2000

        fun executorRegistry(): LinkedHashMap<String, ExecutorContract> =
            linkedMapOf(
                EXEC_DEVICE_STATE to
                    ExecutorContract(
                        executor = EXEC_DEVICE_STATE,
                        lane = AyanaCrossLaneAdaptiveContinuity.LANE_AGENT_CORE,
                        authority = AyanaCrossLaneAdaptiveContinuity.AUTH_AGENT_CORE,
                        mayMutateRuntime = false,
                        mayProduceResult = false,
                        mayConsumeResult = false
                    ),
                EXEC_ANDROID_GOAL to
                    ExecutorContract(
                        executor = EXEC_ANDROID_GOAL,
                        lane = AyanaCrossLaneAdaptiveContinuity.LANE_ANDROID_GOAL,
                        authority = AyanaCrossLaneAdaptiveContinuity.AUTH_ANDROID_GOAL,
                        mayMutateRuntime = true,
                        mayProduceResult = false,
                        mayConsumeResult = false
                    ),
                EXEC_BROWSER_OPEN_URL to
                    ExecutorContract(
                        executor = EXEC_BROWSER_OPEN_URL,
                        lane = AyanaCrossLaneAdaptiveContinuity.LANE_MULTI_APP,
                        authority = AyanaCrossLaneAdaptiveContinuity.AUTH_MULTI_APP,
                        mayMutateRuntime = true,
                        mayProduceResult = false,
                        mayConsumeResult = false
                    ),
                EXEC_STRUCTURED_SCREEN_READ to
                    ExecutorContract(
                        executor = EXEC_STRUCTURED_SCREEN_READ,
                        lane = AyanaCrossLaneAdaptiveContinuity.LANE_MULTI_APP,
                        authority = AyanaCrossLaneAdaptiveContinuity.AUTH_MULTI_APP,
                        mayMutateRuntime = false,
                        mayProduceResult = true,
                        mayConsumeResult = false
                    ),
                EXEC_PARTIAL_RESULT_CHECK to
                    ExecutorContract(
                        executor = EXEC_PARTIAL_RESULT_CHECK,
                        lane = AyanaCrossLaneAdaptiveContinuity.LANE_AGENT_CORE,
                        authority = AyanaCrossLaneAdaptiveContinuity.AUTH_AGENT_CORE,
                        mayMutateRuntime = false,
                        mayProduceResult = false,
                        mayConsumeResult = true
                    ),
                EXEC_YOUTUBE_SEARCH to
                    ExecutorContract(
                        executor = EXEC_YOUTUBE_SEARCH,
                        lane = AyanaCrossLaneAdaptiveContinuity.LANE_MULTI_APP,
                        authority = AyanaCrossLaneAdaptiveContinuity.AUTH_MULTI_APP,
                        mayMutateRuntime = true,
                        mayProduceResult = false,
                        mayConsumeResult = true
                    ),
                EXEC_APP_INFO to
                    ExecutorContract(
                        executor = EXEC_APP_INFO,
                        lane = AyanaCrossLaneAdaptiveContinuity.LANE_MULTI_APP,
                        authority = AyanaCrossLaneAdaptiveContinuity.AUTH_MULTI_APP,
                        mayMutateRuntime = true,
                        mayProduceResult = false,
                        mayConsumeResult = false
                    ),
                EXEC_UNIVERSAL_UI_CLICK to
                    ExecutorContract(
                        executor = EXEC_UNIVERSAL_UI_CLICK,
                        lane = AyanaCrossLaneAdaptiveContinuity.LANE_MULTI_APP,
                        authority = AyanaCrossLaneAdaptiveContinuity.AUTH_MULTI_APP,
                        mayMutateRuntime = true,
                        mayProduceResult = false,
                        mayConsumeResult = false
                    ),
                EXEC_CALENDAR_DRAFT to
                    ExecutorContract(
                        executor = EXEC_CALENDAR_DRAFT,
                        lane = AyanaCrossLaneAdaptiveContinuity.LANE_MULTI_APP,
                        authority = AyanaCrossLaneAdaptiveContinuity.AUTH_MULTI_APP,
                        mayMutateRuntime = true,
                        mayProduceResult = false,
                        mayConsumeResult = true
                    )
            )

        /**
         * Bounded local free-form decomposer used when the objective is composed only
         * from primitives AYANA can already execute and verify locally. Unsupported
         * objectives are returned as supported=false and are not guessed.
         */
        fun localProposal(objective: String): JSONObject {
            val cleanObjective = objective.trim().take(MAX_OBJECTIVE_CHARS)
            val normalized = normalizeText(cleanObjective)

            if (cleanObjective.isBlank()) {
                return JSONObject()
                    .put("supported", false)
                    .put("reason", "objective_required")
                    .put("objective", cleanObjective)
            }

            if (isR10_19AcceptanceObjective(normalized)) {
                return r10_19AcceptanceProposal(cleanObjective)
            }

            val mentionsState =
                normalized.contains("состояни") ||
                    normalized.contains("device state") ||
                    normalized.contains("заряд") ||
                    normalized.contains("хранилищ")

            val mentionsAyana =
                (normalized.contains("ayana") || normalized.contains("аяна")) &&
                    containsOpenVerb(normalized)

            val mentionsExample =
                normalized.contains("example domain") ||
                    normalized.contains("example.com") ||
                    normalized.contains("example domain в браузере")

            val mentionsTitle =
                normalized.contains("заголов") ||
                    normalized.contains("title")

            val mentionsYoutubeApp =
                normalized.contains("youtube") ||
                    normalized.contains("ютуб")

            val mentionsYoutube =
                mentionsYoutubeApp &&
                    (
                        normalized.contains("найди") ||
                            normalized.contains("поиск") ||
                            normalized.contains("search")
                        )

            val mentionsAppInfo =
                (
                    normalized.contains("информац") &&
                        normalized.contains("прилож")
                    ) ||
                    normalized.contains("app info")

            val mentionsPermissions =
                normalized.contains("разрешен") ||
                    normalized.contains("permissions")

            val mentionsCalendar =
                normalized.contains("календар") ||
                    normalized.contains("calendar")

            val mentionsCalendarDraft =
                mentionsCalendar &&
                    (
                        normalized.contains("чернов") ||
                            normalized.contains("draft") ||
                            normalized.contains("событ")
                        )

            val mentionsFinalState =
                mentionsState &&
                    (
                        normalized.contains("в конце") ||
                            normalized.contains("снова проверь") ||
                            normalized.contains("потом снова") ||
                            normalized.contains("finally")
                        )

            val recognized =
                listOf(
                    mentionsState,
                    mentionsAyana,
                    mentionsExample,
                    mentionsTitle,
                    mentionsYoutube,
                    mentionsAppInfo,
                    mentionsPermissions,
                    mentionsCalendarDraft,
                    mentionsFinalState
                ).count { it }

            if (recognized < 2) {
                return JSONObject()
                    .put("supported", false)
                    .put("reason", "objective_not_locally_decomposable")
                    .put("objective", cleanObjective)
                    .put("recognized_primitive_count", recognized)
            }

            val subgoals = JSONArray()
            var previousId = ""

            if (mentionsState) {
                subgoals.put(
                    planItem(
                        id = "device_state",
                        title = "Verify factual device state",
                        executor = EXEC_DEVICE_STATE,
                        dependencies = emptyList(),
                        arguments = JSONObject()
                    )
                )
                previousId = "device_state"
            }

            if (mentionsAyana) {
                subgoals.put(
                    planItem(
                        id = "open_ayana",
                        title = "Open AYANA AI through Android Goal",
                        executor = EXEC_ANDROID_GOAL,
                        dependencies = deps(previousId),
                        arguments =
                            JSONObject()
                                .put("type", "open_app")
                                .put("app", "AYANA AI")
                                .put("max_actions", 2)
                    )
                )
                previousId = "open_ayana"
            }

            if (mentionsExample) {
                subgoals.put(
                    planItem(
                        id = "open_example",
                        title = "Open Example Domain in Browser",
                        executor = EXEC_BROWSER_OPEN_URL,
                        dependencies = deps(previousId),
                        arguments =
                            JSONObject()
                                .put("url", "https://example.com")
                    )
                )
                previousId = "open_example"
            }

            if (mentionsTitle) {
                if (!mentionsExample) {
                    return JSONObject()
                        .put("supported", false)
                        .put("reason", "title_read_requires_browser_source")
                        .put("objective", cleanObjective)
                }
                subgoals.put(
                    planItem(
                        id = "read_title",
                        title = "Read verified page title",
                        executor = EXEC_STRUCTURED_SCREEN_READ,
                        dependencies = listOf("open_example"),
                        resultKey = "page_title",
                        arguments = JSONObject()
                    )
                )
                previousId = "read_title"
            }

            if (mentionsYoutube) {
                if (!mentionsTitle) {
                    return JSONObject()
                        .put("supported", false)
                        .put("reason", "youtube_consumer_requires_verified_title")
                        .put("objective", cleanObjective)
                }
                subgoals.put(
                    planItem(
                        id = "youtube_search",
                        title = "Search verified title in YouTube",
                        executor = EXEC_YOUTUBE_SEARCH,
                        dependencies = listOf("read_title"),
                        arguments =
                            JSONObject()
                                .put("input_result_key", "page_title")
                    )
                )
                previousId = "youtube_search"
            }

            if (mentionsAppInfo) {
                if (!mentionsYoutubeApp) {
                    return JSONObject()
                        .put("supported", false)
                        .put("reason", "app_info_target_not_locally_resolved")
                        .put("objective", cleanObjective)
                }

                subgoals.put(
                    planItem(
                        id = "app_info_youtube",
                        title = "Open verified App Info for YouTube",
                        executor = EXEC_APP_INFO,
                        dependencies = deps(previousId),
                        arguments = JSONObject().put("app", "YouTube")
                    )
                )
                previousId = "app_info_youtube"
            }

            if (mentionsPermissions) {
                if (!mentionsAppInfo) {
                    return JSONObject()
                        .put("supported", false)
                        .put("reason", "permissions_requires_verified_app_info")
                        .put("objective", cleanObjective)
                }

                subgoals.put(
                    planItem(
                        id = "open_permissions",
                        title = "Open Permissions through Universal UI Action Engine",
                        executor = EXEC_UNIVERSAL_UI_CLICK,
                        dependencies = listOf("app_info_youtube"),
                        arguments =
                            JSONObject()
                                .put("target", "пункт Разрешения")
                                .put("confirmed", false)
                    )
                )
                previousId = "open_permissions"
            }

            if (mentionsCalendarDraft) {
                if (!mentionsTitle) {
                    return JSONObject()
                        .put("supported", false)
                        .put("reason", "calendar_draft_requires_verified_title_source")
                        .put("objective", cleanObjective)
                }

                val calendarDependencies =
                    mutableListOf("read_title")
                if (previousId.isNotBlank() && previousId != "read_title") {
                    calendarDependencies.add(previousId)
                }

                subgoals.put(
                    planItem(
                        id = "calendar_draft",
                        title = "Open Calendar DRAFT_ONLY with verified title",
                        executor = EXEC_CALENDAR_DRAFT,
                        dependencies = calendarDependencies.distinct(),
                        arguments = JSONObject().put("input_result_key", "page_title")
                    )
                )
                previousId = "calendar_draft"
            }

            if (mentionsFinalState) {
                val finalDependencies = mutableListOf<String>()
                if (previousId.isNotBlank()) {
                    finalDependencies.add(previousId)
                } else {
                    if (mentionsTitle) finalDependencies.add("read_title")
                    if (mentionsYoutube) finalDependencies.add("youtube_search")
                }
                subgoals.put(
                    planItem(
                        id = "final_device_state",
                        title = "Final factual device verification",
                        executor = EXEC_DEVICE_STATE,
                        dependencies = finalDependencies.distinct(),
                        arguments = JSONObject()
                    )
                )
            }

            if (subgoals.length() == 0) {
                return JSONObject()
                    .put("supported", false)
                    .put("reason", "no_registered_subgoals")
                    .put("objective", cleanObjective)
            }

            return JSONObject()
                .put("supported", true)
                .put("reason", "bounded_local_decomposition")
                .put("planner_version", VERSION)
                .put("source", "local_bounded_decomposer")
                .put("objective", cleanObjective)
                .put("subgoals", subgoals)
        }

        /**
         * R10.11 bounded alternative suffix proposal after a VERIFIED failure.
         *
         * This method never grants a new executor or authority. It only builds a candidate
         * from the same executor registry and is still passed through compile(). The current
         * v1.1 alternative is intentionally narrow: when youtube_search fails without an
         * unresolved side effect, the revised suffix first opens YouTube through the
         * registered Android Goal lane and then performs the same verified-result search.
         * The old failed youtube subgoal and any unresolved descendants are replaced; the
         * prefix before the failed step is copied byte-for-byte at the planner-contract level.
         */
        fun alternativeProposalAfterVerifiedFailure(
            objective: String,
            current: AyanaDynamicGoalPlanner,
            failedSubgoalId: String,
            failureReason: String = ""
        ): JSONObject {
            val failedId = normalizeId(failedSubgoalId)
            val currentSubgoals = current.subgoals()
            val failedIndex = currentSubgoals.indexOfFirst { it.id == failedId }

            if (failedIndex < 0) {
                return JSONObject()
                    .put("supported", false)
                    .put("reason", "failed_subgoal_not_in_current_plan")
                    .put("objective", objective.trim().take(MAX_OBJECTIVE_CHARS))
            }

            val failed = currentSubgoals[failedIndex]
            if (failed.executor != EXEC_YOUTUBE_SEARCH) {
                return JSONObject()
                    .put("supported", false)
                    .put("reason", "no_registered_verified_failure_alternative")
                    .put("failed_subgoal_id", failedId)
                    .put("failed_executor", failed.executor)
                    .put("objective", objective.trim().take(MAX_OBJECTIVE_CHARS))
            }

            val failedArgs = current.argumentsFor(failed.id)
            val inputKey = normalizeId(failedArgs.optString("input_result_key"))
            if (inputKey.isBlank()) {
                return JSONObject()
                    .put("supported", false)
                    .put("reason", "failed_youtube_input_result_missing")
                    .put("objective", objective.trim().take(MAX_OBJECTIVE_CHARS))
            }

            val producer =
                currentSubgoals.firstOrNull { it.resultKey == inputKey }
                    ?: return JSONObject()
                        .put("supported", false)
                        .put("reason", "failed_youtube_result_producer_missing")
                        .put("objective", objective.trim().take(MAX_OBJECTIVE_CHARS))

            val revised = JSONArray()

            // Preserve the planner prefix before the failed step exactly.
            for (index in 0 until failedIndex) {
                revised.put(plannedSubgoalJson(currentSubgoals[index]))
            }

            val openRecoveryId = uniqueRecoveryId(currentSubgoals, "open_youtube_recovery")
            val searchRecoveryId = uniqueRecoveryId(currentSubgoals, "youtube_search_recovered")

            revised.put(
                planItem(
                    id = openRecoveryId,
                    title = "Open YouTube before recovered verified-title search",
                    executor = EXEC_ANDROID_GOAL,
                    dependencies = listOf(producer.id),
                    arguments =
                        JSONObject()
                            .put("type", "open_app")
                            .put("app", "YouTube")
                            .put("max_actions", 2)
                )
            )

            revised.put(
                planItem(
                    id = searchRecoveryId,
                    title = "Search verified title in YouTube after verified app launch",
                    executor = EXEC_YOUTUBE_SEARCH,
                    dependencies = listOf(producer.id, openRecoveryId),
                    arguments = JSONObject().put("input_result_key", inputKey)
                )
            )

            // Preserve later unresolved intent where it is safe, replacing references to the
            // failed YouTube step with the recovered search step. Do not copy descendants that
            // depend on other unresolved nodes removed by this bounded revision.
            val prefixIds = mutableSetOf<String>()
            for (index in 0 until failedIndex) prefixIds.add(currentSubgoals[index].id)
            prefixIds.add(openRecoveryId)
            prefixIds.add(searchRecoveryId)

            for (index in failedIndex + 1 until currentSubgoals.size) {
                val old = currentSubgoals[index]
                val mappedDeps = old.dependencies.map {
                    if (it == failed.id) searchRecoveryId else it
                }
                if (mappedDeps.all { it in prefixIds }) {
                    revised.put(
                        planItem(
                            id = old.id,
                            title = old.title,
                            executor = old.executor,
                            dependencies = mappedDeps,
                            resultKey = old.resultKey,
                            arguments = current.argumentsFor(old.id),
                            required = old.required
                        )
                    )
                    prefixIds.add(old.id)
                }
            }

            return JSONObject()
                .put("supported", true)
                .put("reason", "registered_verified_failure_alternative")
                .put("source", "r10_11_verified_failure_replan")
                .put("objective", objective.trim().take(MAX_OBJECTIVE_CHARS))
                .put("failed_subgoal_id", failed.id)
                .put("failed_executor", failed.executor)
                .put("failure_reason", failureReason.take(300))
                .put("subgoals", revised)
        }

        fun validateProposal(
            objective: String,
            proposal: JSONObject?
        ): JSONObject =
            try {
                val planner = compile(objective, proposal)
                JSONObject()
                    .put("valid", true)
                    .put("reason", "planner_contract_verified")
                    .put("subgoal_count", planner.subgoalCount())
                    .put("plan_fingerprint", planner.planFingerprint())
                    .put("source", planner.source())
            } catch (error: Throwable) {
                JSONObject()
                    .put("valid", false)
                    .put("reason", error.message ?: error.javaClass.simpleName)
                    .put("subgoal_count", 0)
            }

        fun compile(
            objective: String,
            proposal: JSONObject?
        ): AyanaDynamicGoalPlanner {
            require(proposal != null) { "planner_proposal_required" }

            val cleanObjective = objective.trim().take(MAX_OBJECTIVE_CHARS)
            require(cleanObjective.isNotBlank()) { "objective_required" }

            if (proposal.hasBooleanFalse("supported")) {
                throw IllegalArgumentException(
                    proposal.optString("reason", "planner_proposal_unsupported")
                )
            }

            val proposalObjective =
                proposal.optString("objective").trim().take(MAX_OBJECTIVE_CHARS)
            require(proposalObjective.isNotBlank()) { "proposal_objective_required" }
            require(normalizeText(proposalObjective) == normalizeText(cleanObjective)) {
                "proposal_objective_mismatch"
            }

            val source =
                proposal.optString("source", "external_planner_proposal")
                    .trim()
                    .take(120)
                    .ifBlank { "external_planner_proposal" }

            val array = proposal.optJSONArray("subgoals")
                ?: throw IllegalArgumentException("planner_subgoals_required")

            require(array.length() in 1..MAX_SUBGOALS) {
                if (array.length() > MAX_SUBGOALS) "planner_subgoal_limit_exceeded" else "planner_subgoals_required"
            }

            val registry = executorRegistry()
            val planned = mutableListOf<PlannedSubgoal>()
            val ids = linkedSetOf<String>()
            val resultProducers = linkedMapOf<String, String>()

            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                    ?: throw IllegalArgumentException("planner_subgoal_not_object:$index")

                val id = normalizeId(item.optString("id"))
                require(id.isNotBlank()) { "planner_subgoal_id_required:$index" }
                require(ids.add(id)) { "planner_duplicate_subgoal_id:$id" }

                val title = item.optString("title").trim().take(MAX_TITLE_CHARS)
                require(title.isNotBlank()) { "planner_subgoal_title_required:$id" }

                val executor = normalizeToken(item.optString("executor"))
                val contract = registry[executor]
                    ?: throw IllegalArgumentException("planner_unknown_executor:$executor")

                val lane = normalizeToken(item.optString("lane"))
                val authority = item.optString("authority").trim()
                require(lane == contract.lane) { "planner_lane_mismatch:$id" }
                require(authority == contract.authority) { "planner_authority_mismatch:$id" }

                val dependencies = mutableListOf<String>()
                val dependencyArray = item.optJSONArray("dependencies") ?: JSONArray()
                require(dependencyArray.length() <= MAX_DEPENDENCIES) {
                    "planner_dependency_limit_exceeded:$id"
                }
                for (dependencyIndex in 0 until dependencyArray.length()) {
                    val dependency = normalizeId(dependencyArray.optString(dependencyIndex))
                    require(dependency.isNotBlank()) { "planner_dependency_id_required:$id" }
                    require(dependency != id) { "planner_self_dependency:$id" }
                    if (dependency !in dependencies) dependencies.add(dependency)
                }

                val resultKey = normalizeId(item.optString("result_key"))
                if (resultKey.isNotBlank()) {
                    require(contract.mayProduceResult) {
                        "planner_executor_cannot_produce_result:$id"
                    }
                    require(resultKey !in resultProducers) {
                        "planner_duplicate_result_key:$resultKey"
                    }
                    resultProducers[resultKey] = id
                }

                val arguments =
                    item.optJSONObject("arguments")
                        ?.let { JSONObject(it.toString()) }
                        ?: JSONObject()

                validateExecutorArguments(
                    subgoalId = id,
                    contract = contract,
                    arguments = arguments
                )

                planned.add(
                    PlannedSubgoal(
                        id = id,
                        title = title,
                        executor = executor,
                        lane = lane,
                        authority = authority,
                        dependencies = dependencies,
                        resultKey = resultKey,
                        required = item.optBoolean("required", true),
                        arguments = arguments
                    )
                )
            }

            val byId = planned.associateBy { it.id }
            for (subgoal in planned) {
                for (dependency in subgoal.dependencies) {
                    require(dependency in byId) {
                        "planner_unknown_dependency:${subgoal.id}->$dependency"
                    }
                }
            }

            require(isAcyclic(planned)) { "planner_cyclic_dependency_graph" }

            for (subgoal in planned) {
                val inputKey =
                    normalizeId(
                        subgoal.arguments.optString("input_result_key")
                    )
                if (inputKey.isBlank()) continue

                val producerId = resultProducers[inputKey]
                    ?: throw IllegalArgumentException(
                        "planner_input_result_has_no_producer:${subgoal.id}:$inputKey"
                    )
                require(producerId in subgoal.dependencies) {
                    "planner_result_producer_not_dependency:${subgoal.id}:$producerId"
                }
                require(executorRegistry()[subgoal.executor]?.mayConsumeResult == true) {
                    "planner_executor_cannot_consume_result:${subgoal.id}"
                }
            }

            val fingerprint = planFingerprint(cleanObjective, planned)

            return AyanaDynamicGoalPlanner(
                objectiveValue = cleanObjective,
                sourceValue = source,
                subgoalsValue = planned,
                fingerprintValue = fingerprint
            )
        }

        fun restore(snapshot: JSONObject?): AyanaDynamicGoalPlanner {
            require(snapshot != null) { "dynamic_planner_snapshot_required" }
            require(snapshot.optString("version") in setOf("1.0", "1.1", VERSION)) {
                "unsupported_dynamic_planner_version"
            }
            require(snapshot.optInt("snapshot_version", -1) == SNAPSHOT_VERSION) {
                "unsupported_dynamic_planner_snapshot"
            }

            val proposal =
                JSONObject()
                    .put("supported", true)
                    .put("source", snapshot.optString("source"))
                    .put("objective", snapshot.optString("objective"))
                    .put(
                        "subgoals",
                        snapshot.optJSONArray("subgoals") ?: JSONArray()
                    )

            val restored = compile(snapshot.optString("objective"), proposal)
            require(restored.planFingerprint() == snapshot.optString("plan_fingerprint")) {
                "dynamic_planner_fingerprint_mismatch"
            }
            return restored
        }

        fun selfTest(): Boolean {
            return try {
                val objective =
                    "Сначала проверь состояние устройства, открой AYANA AI, затем открой Example Domain в браузере, прочитай заголовок страницы, найди этот заголовок в YouTube и в конце снова проверь состояние устройства."

                val local = localProposal(objective)
                val planner = compile(objective, local)
                val localOk =
                    local.optBoolean("supported", false) &&
                        planner.subgoalCount() == 6 &&
                        planner.toLongObjectiveSpecs().size == 6

                val unknownExecutor = JSONObject(local.toString())
                val unknownArray = unknownExecutor.optJSONArray("subgoals") ?: JSONArray()
                unknownArray.optJSONObject(0)?.put("executor", "github_repository_write")
                val unknownBlocked = !validateProposal(objective, unknownExecutor)
                    .optBoolean("valid", true)

                val authorityViolation = JSONObject(local.toString())
                val authorityArray = authorityViolation.optJSONArray("subgoals") ?: JSONArray()
                authorityArray.optJSONObject(0)?.put("authority", "github_repository_write")
                val authorityBlocked = !validateProposal(objective, authorityViolation)
                    .optBoolean("valid", true)

                val cycle = JSONObject(local.toString())
                val cycleArray = cycle.optJSONArray("subgoals") ?: JSONArray()
                val first = cycleArray.optJSONObject(0)
                val last = cycleArray.optJSONObject(cycleArray.length() - 1)
                if (first != null && last != null) {
                    first.put("dependencies", JSONArray(listOf(last.optString("id"))))
                }
                val cycleBlocked = !validateProposal(objective, cycle)
                    .optBoolean("valid", true)

                val restored = restore(planner.snapshot())
                val restoreOk =
                    restored.planFingerprint() == planner.planFingerprint() &&
                        restored.subgoalCount() == planner.subgoalCount()

                val alternativeProposal =
                    alternativeProposalAfterVerifiedFailure(
                        objective = objective,
                        current = planner,
                        failedSubgoalId = "youtube_search",
                        failureReason = "self_test_verified_failure"
                    )
                val alternativePlanner = compile(objective, alternativeProposal)
                val alternativeOk =
                    alternativeProposal.optBoolean("supported", false) &&
                        alternativePlanner.planFingerprint() != planner.planFingerprint() &&
                        alternativePlanner.subgoals().any { it.id.startsWith("open_youtube_recovery") } &&
                        alternativePlanner.subgoals().any { it.id.startsWith("youtube_search_recovered") } &&
                        alternativePlanner.subgoals().none { it.id == "youtube_search" }

                val r10_19Objective = "проверь автономные многошаговые задачи 2.0"
                val r10_19Proposal = localProposal(r10_19Objective)
                val r10_19Planner = compile(r10_19Objective, r10_19Proposal)
                val r10_19Ok =
                    r10_19Proposal.optBoolean("supported", false) &&
                        r10_19Planner.subgoalCount() == 8 &&
                        r10_19Planner.subgoals().map { it.executor }.containsAll(
                            listOf(
                                EXEC_BROWSER_OPEN_URL,
                                EXEC_STRUCTURED_SCREEN_READ,
                                EXEC_YOUTUBE_SEARCH,
                                EXEC_APP_INFO,
                                EXEC_UNIVERSAL_UI_CLICK,
                                EXEC_CALENDAR_DRAFT
                            )
                        ) &&
                        r10_19Planner.argumentsFor("r10_19_calendar_draft")
                            .optString("input_result_key") == "r10_19_page_title"

                val r10_19NaturalObjective =
                    "Открой Example Domain в браузере, прочитай заголовок, найди его в YouTube, открой информацию о приложении YouTube и Разрешения, затем создай черновик события в календаре с этим заголовком."
                val r10_19NaturalProposal = localProposal(r10_19NaturalObjective)
                val r10_19NaturalPlanner =
                    compile(r10_19NaturalObjective, r10_19NaturalProposal)
                val r10_19NaturalOk =
                    r10_19NaturalProposal.optBoolean("supported", false) &&
                        r10_19NaturalPlanner.subgoals().any { it.executor == EXEC_UNIVERSAL_UI_CLICK } &&
                        r10_19NaturalPlanner.subgoals().any { it.executor == EXEC_CALENDAR_DRAFT } &&
                        r10_19NaturalPlanner.argumentsFor("calendar_draft")
                            .optString("input_result_key") == "page_title"

                localOk &&
                    r10_19Ok &&
                    r10_19NaturalOk &&
                    unknownBlocked &&
                    authorityBlocked &&
                    cycleBlocked &&
                    restoreOk &&
                    alternativeOk
            } catch (_: Throwable) {
                false
            }
        }

        private fun isR10_19AcceptanceObjective(normalized: String): Boolean =
            normalized in
                setOf(
                    "проверь автономные многошаговые задачи 2.0",
                    "проверь автономные многошаговые задачи 2 0",
                    "протестируй автономные многошаговые задачи 2.0",
                    "протестируй автономные многошаговые задачи 2 0",
                    "проверь autonomous multi-app tasks 2.0",
                    "проверь autonomous multi-app tasks 2 0",
                    "проверь r10.19",
                    "проверь r10 19"
                )

        private fun r10_19AcceptanceProposal(objective: String): JSONObject {
            val subgoals = JSONArray()

            subgoals.put(
                planItem(
                    id = "r10_19_device_state_before",
                    title = "Read factual device state before multi-app execution",
                    executor = EXEC_DEVICE_STATE,
                    dependencies = emptyList(),
                    arguments = JSONObject()
                )
            )
            subgoals.put(
                planItem(
                    id = "r10_19_open_example",
                    title = "Open Example Domain in Browser",
                    executor = EXEC_BROWSER_OPEN_URL,
                    dependencies = listOf("r10_19_device_state_before"),
                    arguments = JSONObject().put("url", "https://example.com")
                )
            )
            subgoals.put(
                planItem(
                    id = "r10_19_read_title",
                    title = "Read verified Browser title",
                    executor = EXEC_STRUCTURED_SCREEN_READ,
                    dependencies = listOf("r10_19_open_example"),
                    resultKey = "r10_19_page_title",
                    arguments = JSONObject()
                )
            )
            subgoals.put(
                planItem(
                    id = "r10_19_youtube_search",
                    title = "Search the verified Browser title in YouTube",
                    executor = EXEC_YOUTUBE_SEARCH,
                    dependencies = listOf("r10_19_read_title"),
                    arguments = JSONObject().put("input_result_key", "r10_19_page_title")
                )
            )
            subgoals.put(
                planItem(
                    id = "r10_19_app_info_youtube",
                    title = "Open verified App Info for YouTube",
                    executor = EXEC_APP_INFO,
                    dependencies = listOf("r10_19_youtube_search"),
                    arguments = JSONObject().put("app", "YouTube")
                )
            )
            subgoals.put(
                planItem(
                    id = "r10_19_permissions",
                    title = "Open Permissions through Universal UI Action Engine",
                    executor = EXEC_UNIVERSAL_UI_CLICK,
                    dependencies = listOf("r10_19_app_info_youtube"),
                    arguments = JSONObject().put("target", "пункт Разрешения").put("confirmed", false)
                )
            )
            subgoals.put(
                planItem(
                    id = "r10_19_calendar_draft",
                    title = "Open Calendar DRAFT_ONLY with verified Browser title",
                    executor = EXEC_CALENDAR_DRAFT,
                    dependencies = listOf("r10_19_read_title", "r10_19_permissions"),
                    arguments = JSONObject().put("input_result_key", "r10_19_page_title")
                )
            )
            subgoals.put(
                planItem(
                    id = "r10_19_device_state_after",
                    title = "Read factual device state after multi-app execution",
                    executor = EXEC_DEVICE_STATE,
                    dependencies = listOf("r10_19_calendar_draft"),
                    arguments = JSONObject()
                )
            )

            return JSONObject()
                .put("supported", true)
                .put("reason", "r10_19_autonomous_multi_app_acceptance")
                .put("planner_version", VERSION)
                .put("source", "r10_19_local_acceptance_decomposer")
                .put("objective", objective)
                .put("subgoals", subgoals)
        }

        private fun plannedSubgoalJson(subgoal: PlannedSubgoal): JSONObject =
            JSONObject()
                .put("id", subgoal.id)
                .put("title", subgoal.title)
                .put("executor", subgoal.executor)
                .put("lane", subgoal.lane)
                .put("authority", subgoal.authority)
                .put("dependencies", JSONArray(subgoal.dependencies))
                .put("result_key", subgoal.resultKey)
                .put("required", subgoal.required)
                .put("arguments", JSONObject(subgoal.arguments.toString()))

        private fun uniqueRecoveryId(
            existing: List<PlannedSubgoal>,
            base: String
        ): String {
            val ids = existing.map { it.id }.toSet()
            if (base !in ids) return base
            for (index in 2..20) {
                val candidate = "${base}_$index"
                if (candidate !in ids) return candidate
            }
            throw IllegalArgumentException("recovery_subgoal_id_space_exhausted:$base")
        }

        private fun planItem(
            id: String,
            title: String,
            executor: String,
            dependencies: List<String>,
            resultKey: String = "",
            arguments: JSONObject,
            required: Boolean = true
        ): JSONObject {
            val contract = executorRegistry()[executor]
                ?: throw IllegalArgumentException("unknown_registered_executor:$executor")

            return JSONObject()
                .put("id", id)
                .put("title", title)
                .put("executor", executor)
                .put("lane", contract.lane)
                .put("authority", contract.authority)
                .put("dependencies", JSONArray(dependencies))
                .put("result_key", resultKey)
                .put("required", required)
                .put("arguments", arguments)
        }

        private fun deps(id: String): List<String> =
            if (id.isBlank()) emptyList() else listOf(id)

        private fun validateExecutorArguments(
            subgoalId: String,
            contract: ExecutorContract,
            arguments: JSONObject
        ) {
            when (contract.executor) {
                EXEC_DEVICE_STATE -> {
                    require(arguments.toString().length <= MAX_ARGUMENT_TEXT_CHARS) {
                        "planner_arguments_too_large:$subgoalId"
                    }
                }

                EXEC_ANDROID_GOAL -> {
                    val type = normalizeToken(arguments.optString("type"))
                    val app = arguments.optString("app").trim()
                    require(type == "open_app") {
                        "planner_android_goal_type_not_registered:$subgoalId"
                    }
                    require(app.isNotBlank()) {
                        "planner_android_goal_app_required:$subgoalId"
                    }
                    require(arguments.optInt("max_actions", 2) in 1..4) {
                        "planner_android_goal_action_limit:$subgoalId"
                    }
                }

                EXEC_BROWSER_OPEN_URL -> {
                    val url = arguments.optString("url").trim()
                    require(url.startsWith("https://")) {
                        "planner_browser_url_must_be_https:$subgoalId"
                    }
                    require(url.length <= 1000) {
                        "planner_browser_url_too_large:$subgoalId"
                    }
                }

                EXEC_STRUCTURED_SCREEN_READ -> {
                    require(arguments.toString().length <= MAX_ARGUMENT_TEXT_CHARS) {
                        "planner_arguments_too_large:$subgoalId"
                    }
                }

                EXEC_PARTIAL_RESULT_CHECK,
                EXEC_YOUTUBE_SEARCH -> {
                    val inputKey = normalizeId(arguments.optString("input_result_key"))
                    require(inputKey.isNotBlank()) {
                        "planner_input_result_key_required:$subgoalId"
                    }
                }

                EXEC_APP_INFO -> {
                    val app = arguments.optString("app").trim()
                    require(app.isNotBlank()) {
                        "planner_app_info_app_required:$subgoalId"
                    }
                    require(app.length <= 160) {
                        "planner_app_info_app_too_large:$subgoalId"
                    }
                }

                EXEC_UNIVERSAL_UI_CLICK -> {
                    val target = arguments.optString("target").trim()
                    require(target.isNotBlank()) {
                        "planner_ui_click_target_required:$subgoalId"
                    }
                    require(target.length <= 240) {
                        "planner_ui_click_target_too_large:$subgoalId"
                    }
                    require(!arguments.optBoolean("confirmed", false)) {
                        "planner_ui_click_cannot_preconfirm:$subgoalId"
                    }
                }

                EXEC_CALENDAR_DRAFT -> {
                    val inputKey = normalizeId(arguments.optString("input_result_key"))
                    val staticTitle = arguments.optString("title").trim()
                    require(inputKey.isNotBlank() || staticTitle.isNotBlank()) {
                        "planner_calendar_draft_title_required:$subgoalId"
                    }
                    require(staticTitle.length <= 240) {
                        "planner_calendar_draft_title_too_large:$subgoalId"
                    }
                }
            }
        }

        private fun isAcyclic(subgoals: List<PlannedSubgoal>): Boolean {
            val byId = subgoals.associateBy { it.id }
            val visiting = mutableSetOf<String>()
            val visited = mutableSetOf<String>()

            fun visit(id: String): Boolean {
                if (id in visited) return true
                if (!visiting.add(id)) return false
                val subgoal = byId[id] ?: return false
                for (dependency in subgoal.dependencies) {
                    if (!visit(dependency)) return false
                }
                visiting.remove(id)
                visited.add(id)
                return true
            }

            return byId.keys.all { visit(it) }
        }

        private fun planFingerprint(
            objective: String,
            subgoals: List<PlannedSubgoal>
        ): String {
            val canonical = buildString {
                append(normalizeText(objective))
                for (subgoal in subgoals) {
                    append('|')
                    append(subgoal.id)
                    append(':')
                    append(subgoal.executor)
                    append(':')
                    append(subgoal.lane)
                    append(':')
                    append(subgoal.authority)
                    append(':')
                    append(subgoal.required)
                    append(':')
                    append(subgoal.resultKey)
                    append(':')
                    append(subgoal.dependencies.joinToString(","))
                    append(':')
                    append(canonicalArguments(subgoal.executor, subgoal.arguments))
                }
            }
            return fingerprint(canonical)
        }

        private fun canonicalArguments(
            executor: String,
            arguments: JSONObject
        ): String =
            when (executor) {
                EXEC_ANDROID_GOAL ->
                    "type=${normalizeToken(arguments.optString("type"))};app=${normalizeText(arguments.optString("app"))};max=${arguments.optInt("max_actions", 2)}"

                EXEC_BROWSER_OPEN_URL ->
                    "url=${arguments.optString("url").trim()}"

                EXEC_PARTIAL_RESULT_CHECK,
                EXEC_YOUTUBE_SEARCH ->
                    "input_result_key=${normalizeId(arguments.optString("input_result_key"))}"

                EXEC_APP_INFO ->
                    "app=${normalizeText(arguments.optString("app"))}"

                EXEC_UNIVERSAL_UI_CLICK ->
                    "target=${normalizeText(arguments.optString("target"))};confirmed=false"

                EXEC_CALENDAR_DRAFT ->
                    "input_result_key=${normalizeId(arguments.optString("input_result_key"))};title=${normalizeText(arguments.optString("title"))}"

                else -> ""
            }

        private fun normalizeId(value: String): String =
            value.trim()
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9._-]+"), "_")
                .trim('_')
                .take(96)

        private fun normalizeToken(value: String): String =
            value.trim().lowercase(Locale.ROOT).take(160)

        private fun normalizeText(value: String): String =
            value.lowercase(Locale.ROOT)
                .replace('ё', 'е')
                .replace(Regex("\\s+"), " ")
                .trim()

        private fun containsOpenVerb(normalized: String): Boolean =
            normalized.contains("открой") ||
                normalized.contains("открыть") ||
                normalized.contains("open")

        private fun fingerprint(value: String): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return bytes.take(12).joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
        }

        private fun JSONObject.hasBooleanFalse(name: String): Boolean =
            this.opt(name) is Boolean && !this.optBoolean(name, true)
    }
}
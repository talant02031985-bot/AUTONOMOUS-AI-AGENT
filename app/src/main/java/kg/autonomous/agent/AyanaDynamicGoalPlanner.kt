package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * AYANA Dynamic Goal Planner v1.0 — R10.9 DYNAMIC GOAL DECOMPOSITION + PLANNER CONTRACT.
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
        const val VERSION = "1.0"
        const val SNAPSHOT_VERSION = 1

        const val EXEC_DEVICE_STATE = "get_device_state"
        const val EXEC_ANDROID_GOAL = "execute_android_goal"
        const val EXEC_BROWSER_OPEN_URL = "browser_open_url"
        const val EXEC_STRUCTURED_SCREEN_READ = "structured_screen_read"
        const val EXEC_PARTIAL_RESULT_CHECK = "partial_result_check"
        const val EXEC_YOUTUBE_SEARCH = "youtube_search"

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

            val mentionsYoutube =
                normalized.contains("youtube") ||
                    normalized.contains("ютуб")

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

            if (mentionsFinalState) {
                val finalDependencies = mutableListOf<String>()
                if (mentionsTitle) finalDependencies.add("read_title")
                if (mentionsYoutube) finalDependencies.add("youtube_search")
                if (finalDependencies.isEmpty() && previousId.isNotBlank()) {
                    finalDependencies.add(previousId)
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
            require(snapshot.optString("version") == VERSION) {
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

                localOk &&
                    unknownBlocked &&
                    authorityBlocked &&
                    cycleBlocked &&
                    restoreOk
            } catch (_: Throwable) {
                false
            }
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

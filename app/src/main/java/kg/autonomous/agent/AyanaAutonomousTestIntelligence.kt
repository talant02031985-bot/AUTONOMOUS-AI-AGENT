package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * AYANA Autonomous Test Intelligence v1.6 — R9 FOUNDATION PROBES.
 *
 * This layer is intentionally different from a fixed acceptance checklist.
 * It discovers test opportunities from the current build/runtime itself:
 * - Capability Registry entries are converted into generic invariant checks;
 * - the installed launcher map is tested end-to-end through App Resolver;
 * - planner tests are generated from the apps that actually exist on the device;
 * - recent real commands are mutated into safe planner-only metamorphic checks;
 * - Command History is mined for terminal/evidence contradictions and platform drift;
 * - effective device-confirmation is resolved through AyanaDeviceEvidenceTruth;
 * - unresolved implemented+available capabilities may invoke one explicit allow-listed safe probe;
 * - PASS probe evidence is consumed immediately and may be persisted by the Android provider;
 * - accepted historical/device evidence suppresses stale coverage-gap hypotheses;
 * - R9 foundation contracts can contribute explicit generated tests through one provider;
 * - fresh PASS evidence from baseline runtime probes can satisfy device-confirmation
 *   for that diagnostic run without mutating Capability Registry metadata.
 *
 * Generated discovery tests remain READ-ONLY/PURE except the dedicated capability-probe
 * provider. That provider is Android-owned, allow-listed, reversible, and must return PASS
 * only after restore/cleanup. ATI never invents active probes by itself and never upgrades
 * WARNING/failed probe evidence to device confirmation.
 */
class AyanaAutonomousTestIntelligence(
    private val capabilitySnapshotProvider: () -> JSONObject,
    private val recentHistoryProvider: (Int) -> List<JSONObject>,
    private val fullResultProvider: (JSONObject) -> String,
    private val appCatalogProvider: () -> JSONArray,
    private val resolveAppProvider: (String) -> JSONObject,
    private val plannerProvider: (String) -> JSONObject,
    private val safeCapabilityProbeProvider: (String) -> JSONObject? = { null },
    private val foundationProbeProvider: () -> JSONArray = { JSONArray() },
    private val shouldCancel: () -> Boolean = { false }
) {

    fun run(
        baseline: JSONObject
    ): JSONObject {
        val startedAt = System.currentTimeMillis()
        val tests = JSONArray()
        val hypotheses = JSONArray()
        val anomalies = JSONArray()

        // Baseline probes execute before the adaptive layer. A fresh PASS probe may
        // provide stronger evidence than stale registry metadata. This is read-only:
        // ATI consumes the evidence for this run but does not rewrite the Registry.
        val runtimeConfirmedCapabilities =
            runtimeConfirmedCapabilitiesFromBaseline(
                baseline
            ).toMutableSet()

        var generated = 0
        var capabilityTests = 0
        var capabilityProbeTests = 0
        var capabilityProbePasses = 0
        var capabilityCoverageGaps = 0
        val sharedHistoricalCapabilities = linkedSetOf<String>()
        var resolverTests = 0
        var plannerTests = 0
        var metamorphicTests = 0
        var historyTests = 0
        var foundationTests = 0
        var cancelled = false
        var truncated = false

        val snapshot =
            safeObject {
                capabilitySnapshotProvider()
            }

        val capabilities =
            snapshot.optJSONArray("capabilities")
                ?: JSONArray()

        val projectPlatformAndroid =
            snapshot.optInt("android_sdk", -1) > 0 ||
                snapshot.optString("build").contains("v12.", ignoreCase = true)

        // ---------------------------------------------------------
        // 1) Generic capability invariants — no per-capability list.
        // ---------------------------------------------------------
        for (index in 0 until capabilities.length()) {
            if (stopRequested(tests.length())) {
                cancelled = shouldCancel()
                truncated = !cancelled
                break
            }

            val item = capabilities.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            if (id.isBlank()) continue

            val implemented = item.optBoolean("implemented", false)
            val available = item.optBoolean("available_now", false)
            val registryConfirmed = item.optBoolean("device_confirmed", false)
            val staticConfirmed = item.optBoolean("static_device_confirmed", false)
            val persistedConfirmed = item.optBoolean("runtime_evidence_persisted", false)
            val persistedDetail = item.optString("runtime_evidence_detail")

            var runtimeProbeConfirmed = id in runtimeConfirmedCapabilities
            var evidenceResolution =
                AyanaDeviceEvidenceTruth.resolve(
                    capabilityId = id,
                    implemented = implemented,
                    availableNow = available,
                    registryDeviceConfirmed = registryConfirmed,
                    staticDeviceConfirmed = staticConfirmed,
                    runtimeEvidencePersisted = persistedConfirmed,
                    runtimeEvidenceDetail = persistedDetail,
                    runtimeProbeConfirmed = runtimeProbeConfirmed
                )

            // v1.5: only unresolved implemented+available capabilities are offered to
            // the Android allow-listed probe provider. Null means "no safe probe".
            if (
                implemented &&
                available &&
                !evidenceResolution.effectiveConfirmed &&
                !stopRequested(tests.length())
            ) {
                val probeStartedAt = System.currentTimeMillis()
                val probe =
                    try {
                        safeCapabilityProbeProvider(id)
                    } catch (error: Exception) {
                        JSONObject()
                            .put("status", STATUS_WARNING)
                            .put("verified", false)
                            .put("message", "Safe capability probe exception: ${error.javaClass.simpleName}.")
                            .put("evidence_scope", "safe_capability_probe_exception")
                            .put("evidence", JSONObject().put("error", error.message.orEmpty().take(500)))
                    }

                if (probe != null) {
                    val rawStatus = probe.optString("status").uppercase(Locale.ROOT)
                    val probeStatus =
                        when (rawStatus) {
                            STATUS_PASS -> STATUS_PASS
                            STATUS_FAIL -> STATUS_WARNING
                            else -> STATUS_WARNING
                        }
                    val probeVerified =
                        probeStatus == STATUS_PASS &&
                            probe.optBoolean("verified", false)

                    val probeEvidence =
                        copyJsonObject(
                            probe.optJSONObject("evidence") ?: JSONObject()
                        )
                            .put("capability_id", id)
                            .put("provider_status", rawStatus.ifBlank { "UNKNOWN" })
                            .put("probe_verified", probeVerified)

                    tests.put(
                        JSONObject()
                            .put("id", "AUTO-PROBE-${shortId(id)}")
                            .put("title", "Safe device evidence probe: $id")
                            .put("status", probeStatus)
                            .put("critical", false)
                            .put("duration_ms", (System.currentTimeMillis() - probeStartedAt).coerceAtLeast(0L))
                            .put(
                                "message",
                                probe.optString("message")
                                    .ifBlank { if (probeVerified) "Safe device probe PASS." else "Safe device probe did not confirm capability." }
                                    .take(1200)
                            )
                            .put("evidence_scope", probe.optString("evidence_scope").ifBlank { "safe_capability_device_probe" })
                            .put("verified", probeVerified)
                            .put("evidence", probeEvidence)
                            .put("generated", true)
                            .put("generator", "AyanaAutonomousTestIntelligence/$ENGINE_VERSION")
                    )
                    generated++
                    capabilityProbeTests++

                    if (probeVerified) {
                        runtimeConfirmedCapabilities += id
                        runtimeProbeConfirmed = true
                        capabilityProbePasses++
                        evidenceResolution =
                            AyanaDeviceEvidenceTruth.resolve(
                                capabilityId = id,
                                implemented = implemented,
                                availableNow = available,
                                registryDeviceConfirmed = registryConfirmed,
                                staticDeviceConfirmed = staticConfirmed,
                                runtimeEvidencePersisted = persistedConfirmed,
                                runtimeEvidenceDetail = persistedDetail,
                                runtimeProbeConfirmed = true
                            )
                    }
                }
            }

            val confirmed = evidenceResolution.effectiveConfirmed
            val truthState = evidenceResolution.truthState
            val note = item.optString("note").take(MAX_NOTE_CHARS)

            if (evidenceResolution.historicalAccepted) {
                sharedHistoricalCapabilities += id
            }

            val contradiction =
                (!implemented && available) ||
                    (!implemented && confirmed) ||
                    (confirmed && !implemented)

            val status = if (contradiction) STATUS_FAIL else STATUS_PASS
            val critical = contradiction

            tests.put(
                result(
                    id = "AUTO-CAP-${shortId(id)}",
                    title = "Capability invariant: $id",
                    status = status,
                    critical = critical,
                    verified = !contradiction,
                    message =
                        if (contradiction) {
                            "Capability Registry contradiction: implemented=$implemented, available=$available, device_confirmed=$confirmed."
                        } else {
                            "Generic capability invariant consistent: implemented=$implemented, available=$available, device_confirmed=$confirmed."
                        },
                    evidenceScope = "self_directed_capability_invariant",
                    evidence =
                        JSONObject()
                            .put("capability_id", id)
                            .put("implemented", implemented)
                            .put("available_now", available)
                            .put("device_confirmed", confirmed)
                            .put("registry_device_confirmed", registryConfirmed)
                            .put("runtime_probe_confirmed", runtimeProbeConfirmed)
                            .put("shared_truth_version", AyanaDeviceEvidenceTruth.VERSION)
                            .put("evidence_source", evidenceResolution.sourceCode)
                            .put("historical_accepted_evidence", evidenceResolution.historicalAccepted)
                            .put("evidence_detail", evidenceResolution.sourceDetail.take(500))
                            .put("truth_state", truthState)
                            .put("note", note)
                )
            )
            generated++
            capabilityTests++

            if (implemented && available && !confirmed) {
                capabilityCoverageGaps++
                hypotheses.put(
                    JSONObject()
                        .put("id", "HYP-CAP-${shortId(id)}")
                        .put("kind", "coverage_gap")
                        .put("capability_id", id)
                        .put("priority", inferHypothesisPriority(id, note))
                        .put("safe_execution", "requires_capability_specific_probe")
                        .put(
                            "hypothesis",
                            "Capability реализована и доступна, но shared device-evidence truth не содержит подтверждения. Сгенерировать безопасный runtime-probe для: $id."
                        )
                        .put("note", note)
                )
            }
        }

        // ---------------------------------------------------------
        // 2) Device-discovered App Resolver matrix.
        //    Tests are derived from installed apps, not a hard-coded list.
        // ---------------------------------------------------------
        val apps =
            safeArray {
                appCatalogProvider()
            }

        val uniqueApps =
            mutableListOf<JSONObject>()
        val labelPackages =
            linkedMapOf<String, MutableSet<String>>()

        for (index in 0 until apps.length()) {
            val app = apps.optJSONObject(index) ?: continue
            val label = app.optString("label").trim()
            val pkg = app.optString("package").trim()
            if (label.isBlank() || pkg.isBlank()) continue
            val key = normalize(label)
            if (key.isBlank()) continue
            labelPackages.getOrPut(key) { linkedSetOf() }.add(pkg)
            uniqueApps += app
        }

        uniqueApps
            .filter {
                val label = normalize(it.optString("label"))
                labelPackages[label]?.size == 1
            }
            .distinctBy {
                normalize(it.optString("label")) + "|" + it.optString("package")
            }
            .take(MAX_RESOLVER_TESTS)
            .forEach { app ->
                if (stopRequested(tests.length())) {
                    cancelled = shouldCancel()
                    truncated = !cancelled
                    return@forEach
                }

                val label = app.optString("label").trim()
                val expectedPackage = app.optString("package").trim()
                val resolved =
                    safeObject {
                        resolveAppProvider(label)
                    }

                val actualPackage = resolved.optString("package").trim()
                val ok =
                    resolved.optBoolean("success", false) &&
                        actualPackage == expectedPackage

                tests.put(
                    result(
                        id = "AUTO-APP-${shortId("$label|$expectedPackage")}",
                        title = "Installed-app resolver round-trip: $label",
                        status = if (ok) STATUS_PASS else STATUS_FAIL,
                        critical = true,
                        verified = ok,
                        message =
                            if (ok) {
                                "Installed launcher entry resolves back to the same package."
                            } else {
                                "Resolver drift: expected=$expectedPackage, actual=${actualPackage.ifBlank { "none" }}."
                            },
                        evidenceScope = "self_directed_installed_app_matrix",
                        evidence =
                            JSONObject()
                                .put("label", label)
                                .put("expected_package", expectedPackage)
                                .put("actual_package", actualPackage)
                                .put("confidence", resolved.optInt("confidence", -1))
                                .put("source", resolved.optString("source"))
                    )
                )
                generated++
                resolverTests++
            }

        // ---------------------------------------------------------
        // 3) Planner matrix generated from actual installed apps.
        // ---------------------------------------------------------
        val plannerCandidates =
            uniqueApps
                .filter {
                    val label = normalize(it.optString("label"))
                    labelPackages[label]?.size == 1
                }
                .distinctBy { normalize(it.optString("label")) }
                .take(MAX_PLANNER_APP_TESTS)

        for (app in plannerCandidates) {
            if (stopRequested(tests.length())) {
                cancelled = shouldCancel()
                truncated = !cancelled
                break
            }

            val label = app.optString("label").trim()
            val expectedPackage = app.optString("package").trim()
            val command = "открой приложение $label"
            val envelope = safeObject { plannerProvider(command) }
            val resolution = envelope.optJSONObject("app_resolution")
            val actualPackage = resolution?.optString("package").orEmpty()

            val ok =
                envelope.optString("domain") == "android_action" &&
                    resolution?.optBoolean("success", false) == true &&
                    actualPackage == expectedPackage &&
                    envelope.optString("terminal_criterion").isNotBlank()

            tests.put(
                result(
                    id = "AUTO-PLAN-${shortId("$label|$expectedPackage")}",
                    title = "Generated planner contract: $label",
                    status = if (ok) STATUS_PASS else STATUS_FAIL,
                    critical = true,
                    verified = ok,
                    message =
                        if (ok) {
                            "Planner generated Android envelope and preserved the device-observed app target."
                        } else {
                            "Generated planner contract drift for installed app $label."
                        },
                    evidenceScope = "self_directed_planner_matrix",
                    evidence =
                        JSONObject()
                            .put("generated_command", command)
                            .put("domain", envelope.optString("domain"))
                            .put("complexity", envelope.optString("complexity"))
                            .put("terminal_criterion", envelope.optString("terminal_criterion").take(500))
                            .put("expected_package", expectedPackage)
                            .put("actual_package", actualPackage)
                )
            )
            generated++
            plannerTests++
        }

        // ---------------------------------------------------------
        // 4) Recent-history metamorphic planner checks.
        //    Commands are selected from actual use, then automatically mutated.
        // ---------------------------------------------------------
        val history =
            try {
                recentHistoryProvider(MAX_HISTORY_RECORDS)
            } catch (_: Exception) {
                emptyList()
            }

        val seenCommands = linkedSetOf<String>()
        val metamorphicCandidates = mutableListOf<String>()


        // v1.3: a later successful Android/Kotlin result can close a historical
        // platform-drift candidate without deleting the old evidence. The old record
        // remains in the anomaly ledger, but no longer becomes a current WARNING/hypothesis.
        // recentHistoryProvider returns newest-first. Keep the newest successful
        // Android/Kotlin proof for each normalized project request. A historical drift
        // is suppressed only when the matching proof is NEWER than that drift record.
        val resolvedAndroidProjectCodeCommandIndices =
            linkedMapOf<String, Int>()

        history.forEachIndexed { historyIndex, record ->
            val status =
                record.optString("status")
                    .uppercase(Locale.ROOT)

            val command =
                normalize(
                    record.optString("command")
                )

            val resultText =
                try {
                    fullResultProvider(record)
                } catch (_: Exception) {
                    record.optString("result")
                }

            val projectCodeRequest =
                looksLikeAyanaProjectCodeRequest(command) ||
                    (
                        projectPlatformAndroid &&
                            (
                                "код" in command ||
                                    "файл" in command ||
                                    "визуализ" in command ||
                                    "интерфейс" in command
                                )
                        )

            if (
                (status == STATUS_PASS || status == "SUCCESS") &&
                projectCodeRequest &&
                containsPositiveAndroidKotlinEvidence(resultText)
            ) {
                val key =
                    projectRequestResolutionKey(command)

                if (key.isNotBlank()) {
                    val previousIndex =
                        resolvedAndroidProjectCodeCommandIndices[key]

                    if (
                        previousIndex == null ||
                        historyIndex < previousIndex
                    ) {
                        resolvedAndroidProjectCodeCommandIndices[key] =
                            historyIndex
                    }
                }
            }
        }

        history.forEach { record ->
            val command = record.optString("command").trim()
            if (
                command.isNotBlank() &&
                "[мультимодальное вложение]" !in command &&
                command.length <= MAX_METAMORPHIC_COMMAND_CHARS
            ) {
                val normalized = normalize(command)
                if (normalized.isNotBlank() && seenCommands.add(normalized)) {
                    metamorphicCandidates += command
                }
            }
        }

        metamorphicCandidates
            .take(MAX_METAMORPHIC_TESTS)
            .forEach { command ->
                if (stopRequested(tests.length())) {
                    cancelled = shouldCancel()
                    truncated = !cancelled
                    return@forEach
                }

                val base = safeObject { plannerProvider(command) }
                val variants =
                    buildMetamorphicVariants(command)

                val mismatches = JSONArray()
                variants.forEach { variant ->
                    val actual = safeObject { plannerProvider(variant) }
                    if (!samePlannerSemantics(base, actual)) {
                        mismatches.put(
                            JSONObject()
                                .put("variant", variant.take(500))
                                .put("base_signature", plannerSignature(base))
                                .put("actual_signature", plannerSignature(actual))
                        )
                    }
                }

                val ok = mismatches.length() == 0
                tests.put(
                    result(
                        id = "AUTO-META-${shortId(command)}",
                        title = "Metamorphic planner stability",
                        status = if (ok) STATUS_PASS else STATUS_FAIL,
                        critical = true,
                        verified = ok,
                        message =
                            if (ok) {
                                "Planner semantics preserved across automatically generated formatting variants."
                            } else {
                                "Planner semantics changed across formatting-only variants."
                            },
                        evidenceScope = "self_directed_metamorphic_planner",
                        evidence =
                            JSONObject()
                                .put("source_command", command.take(500))
                                .put("base_signature", plannerSignature(base))
                                .put("variant_count", variants.size)
                                .put("mismatches", mismatches)
                    )
                )
                generated++
                metamorphicTests++
            }

        // ---------------------------------------------------------
        // 5) History anomaly mining. These are regression candidates from real use.
        //    They are warnings (historical evidence), never silently upgraded to
        //    current-build FAIL unless an active generated probe reproduces them.
        // ---------------------------------------------------------
        val anomalyKindCounts = mutableMapOf<String, Int>()

        history.forEachIndexed { index, record ->
            if (stopRequested(tests.length())) {
                cancelled = shouldCancel()
                truncated = !cancelled
                return@forEachIndexed
            }

            val status = record.optString("status").uppercase(Locale.ROOT)
            val command = record.optString("command").trim()
            val resultText =
                try {
                    fullResultProvider(record)
                } catch (_: Exception) {
                    record.optString("result")
                }
            val technical = record.optString("technical")

            val detected =
                detectHistoryAnomalies(
                    status = status,
                    command = command,
                    resultText = resultText,
                    technical = technical,
                    durationMs = record.optLong("duration_ms", -1L),
                    projectPlatformAndroid = projectPlatformAndroid
                )

            detected.forEach { anomaly ->
                val kind = anomaly.optString("kind")

                val matchingResolutionIndex =
                    resolvedAndroidProjectCodeCommandIndices[
                        projectRequestResolutionKey(
                            normalize(command)
                        )
                    ]

                if (
                    kind == "project_platform_drift" &&
                    matchingResolutionIndex != null &&
                    matchingResolutionIndex < index
                ) {
                    anomalies.put(
                        copyJsonObject(anomaly)
                            .put("history_id", record.optString("id"))
                            .put("history_status", status)
                            .put("command", command.take(500))
                            .put("result_preview", resultText.take(1200))
                            .put("technical_preview", technical.take(1200))
                            .put("resolved_by_later_android_kotlin_evidence", true)
                            .put("warning_suppressed", true)
                    )
                    return@forEach
                }

                val currentKindCount = anomalyKindCounts[kind] ?: 0
                if (currentKindCount >= maxAnomaliesForKind(kind)) {
                    return@forEach
                }
                anomalyKindCounts[kind] = currentKindCount + 1
                if (tests.length() >= MAX_GENERATED_TESTS) {
                    truncated = true
                    return@forEach
                }

                val anomalyId =
                    "AUTO-HIST-${shortId(record.optString("id") + "|" + anomaly.optString("kind") + "|" + index)}"

                val evidence =
                    copyJsonObject(anomaly)
                        .put("history_id", record.optString("id"))
                        .put("history_status", status)
                        .put("command", command.take(500))
                        .put("result_preview", resultText.take(1200))
                        .put("technical_preview", technical.take(1200))

                anomalies.put(
                    copyJsonObject(evidence)
                        .put("test_id", anomalyId)
                )

                tests.put(
                    result(
                        id = anomalyId,
                        title = anomaly.optString("title", "History anomaly candidate"),
                        status = STATUS_WARNING,
                        critical = false,
                        verified = true,
                        message = anomaly.optString("message"),
                        evidenceScope = "self_directed_history_mining",
                        evidence = evidence
                    )
                )
                generated++
                historyTests++

                hypotheses.put(
                    JSONObject()
                        .put("id", "HYP-${shortId(anomalyId)}")
                        .put("kind", anomaly.optString("kind"))
                        .put("priority", anomaly.optString("priority", "medium"))
                        .put("source_test_id", anomalyId)
                        .put("safe_execution", anomaly.optString("safe_execution", "reproduce_with_safe_contract_probe"))
                        .put("hypothesis", anomaly.optString("hypothesis", anomaly.optString("message")))
                )
            }
        }

        // ---------------------------------------------------------
        // 6) R9 foundation probes supplied by the Android owner. These are pure
        //    contract probes unless the provider explicitly reports otherwise. ATI
        //    does not invent foundation PASS; it only normalizes provider evidence.
        // ---------------------------------------------------------
        val foundationProbes =
            safeArray {
                foundationProbeProvider()
            }

        for (index in 0 until foundationProbes.length()) {
            if (stopRequested(tests.length())) {
                cancelled = shouldCancel()
                truncated = !cancelled
                break
            }

            if (tests.length() >= MAX_GENERATED_TESTS) {
                truncated = true
                break
            }

            val raw =
                foundationProbes.optJSONObject(index)
                    ?: continue

            val id =
                raw.optString("id")
                    .ifBlank { "R9-FOUND-${index + 1}" }
                    .take(96)

            val rawStatus =
                raw.optString("status", STATUS_WARNING)
                    .uppercase(Locale.ROOT)

            val status =
                when (rawStatus) {
                    STATUS_PASS -> STATUS_PASS
                    STATUS_FAIL -> STATUS_FAIL
                    else -> STATUS_WARNING
                }

            val verified =
                status == STATUS_PASS &&
                    raw.optBoolean("verified", false)

            val evidence =
                copyJsonObject(
                    raw.optJSONObject("evidence")
                        ?: JSONObject()
                )
                    .put("provider_status", rawStatus)
                    .put("provider_verified", raw.optBoolean("verified", false))

            tests.put(
                result(
                    id = id,
                    title = raw.optString("title", "R9 foundation contract").take(220),
                    status = status,
                    critical = raw.optBoolean("critical", false),
                    verified = verified,
                    message = raw.optString("message").ifBlank {
                        if (verified) {
                            "R9 foundation contract verified."
                        } else {
                            "R9 foundation contract is not verified."
                        }
                    },
                    evidenceScope = raw.optString("evidence_scope").ifBlank {
                        "r9_foundation_contract"
                    },
                    evidence = evidence
                )
            )

            generated++
            foundationTests++
        }

        // ---------------------------------------------------------
        // 7) Baseline-driven hypothesis generation: any non-PASS becomes a target
        //    for a next probe without needing a hard-coded test ID.
        // ---------------------------------------------------------
        val baselineTests = baseline.optJSONArray("tests") ?: JSONArray()
        for (index in 0 until baselineTests.length()) {
            val item = baselineTests.optJSONObject(index) ?: continue
            val status = item.optString("status")
            if (status == STATUS_PASS) continue

            hypotheses.put(
                JSONObject()
                    .put("id", "HYP-BASE-${shortId(item.optString("id") + "|" + status)}")
                    .put("kind", "baseline_non_pass_follow_up")
                    .put("source_test_id", item.optString("id"))
                    .put("priority", if (item.optBoolean("critical", false)) "high" else "medium")
                    .put("safe_execution", "derive_follow_up_from_evidence")
                    .put(
                        "hypothesis",
                        "Сгенерировать уточняющий безопасный probe вокруг non-PASS: ${item.optString("title")} / $status."
                    )
                    .put("source_message", item.optString("message").take(1000))
            )
        }

        val finishedAt = System.currentTimeMillis()

        return JSONObject()
            .put("success", !cancelled)
            .put("engine", "AyanaAutonomousTestIntelligence")
            .put("engine_version", ENGINE_VERSION)
            .put("generated_at_ms", finishedAt)
            .put("duration_ms", (finishedAt - startedAt).coerceAtLeast(0L))
            .put("network_turns", 0)
            .put("cancelled", cancelled)
            .put("truncated", truncated)
            .put("tests", tests)
            .put("hypotheses", hypotheses)
            .put("anomalies", anomalies)
            .put(
                "coverage",
                JSONObject()
                    .put("capabilities_discovered", capabilities.length())
                    .put("capability_invariants_tested", capabilityTests)
                    .put("runtime_confirmed_capabilities", runtimeConfirmedCapabilities.size)
                    .put("safe_capability_probe_tests", capabilityProbeTests)
                    .put("safe_capability_probe_passes", capabilityProbePasses)
                    .put("shared_device_evidence_truth_version", AyanaDeviceEvidenceTruth.VERSION)
                    .put("shared_historical_evidence_capabilities", sharedHistoricalCapabilities.size)
                    .put("capability_coverage_gaps_after_fusion", capabilityCoverageGaps)
                    .put("installed_apps_discovered", apps.length())
                    .put("app_resolver_tests", resolverTests)
                    .put("generated_planner_tests", plannerTests)
                    .put("history_records_examined", history.size)
                    .put("metamorphic_planner_tests", metamorphicTests)
                    .put("history_anomaly_tests", historyTests)
                    .put("r9_foundation_contract_tests", foundationTests)
                    .put("hypotheses_generated", hypotheses.length())
                    .put("adaptive_tests_generated", generated)
            )
    }

    private fun runtimeConfirmedCapabilitiesFromBaseline(
        baseline: JSONObject
    ): Set<String> {
        val confirmed =
            linkedSetOf<String>()

        val tests =
            baseline.optJSONArray("tests")
                ?: JSONArray()

        for (index in 0 until tests.length()) {
            val item =
                tests.optJSONObject(index)
                    ?: continue

            if (
                item.optString("status") != STATUS_PASS ||
                !item.optBoolean("verified", false)
            ) {
                continue
            }

            val evidence =
                item.optJSONObject("evidence")
                    ?: continue

            val ids =
                evidence.optJSONArray(
                    "runtime_confirmed_capabilities"
                )
                    ?: continue

            for (capIndex in 0 until ids.length()) {
                val id =
                    ids.optString(capIndex)
                        .trim()

                if (id.isNotBlank()) {
                    confirmed += id
                }
            }
        }

        return confirmed
    }

    private fun stopRequested(
        currentTests: Int
    ): Boolean =
        shouldCancel() || currentTests >= MAX_GENERATED_TESTS

    private fun buildMetamorphicVariants(
        command: String
    ): List<String> {
        val clean = command.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return emptyList()

        val variants = linkedSetOf<String>()
        variants += "  $clean  "
        variants += clean.trimEnd('.', '!', '?', '…') + "."
        variants += clean.replace(" ", "  ")
        variants += clean.lowercase(Locale.getDefault())

        return variants
            .filter { it.trim() != clean }
            .take(MAX_VARIANTS_PER_COMMAND)
    }

    private fun samePlannerSemantics(
        first: JSONObject,
        second: JSONObject
    ): Boolean =
        plannerSignature(first) == plannerSignature(second)

    private fun plannerSignature(
        envelope: JSONObject
    ): String {
        val resolution = envelope.optJSONObject("app_resolution")
        val subgoals = envelope.optJSONArray("subgoals") ?: JSONArray()
        return listOf(
            envelope.optString("domain"),
            envelope.optString("complexity"),
            envelope.optString("risk_hint"),
            subgoals.length().toString(),
            resolution?.optBoolean("success", false)?.toString().orEmpty(),
            resolution?.optString("package").orEmpty(),
            envelope.optString("terminal_criterion").isNotBlank().toString()
        ).joinToString("|")
    }

    private fun looksLikeAyanaProjectCodeRequest(
        normalizedCommand: String
    ): Boolean =
        (
            "код" in normalizedCommand ||
                "файл" in normalizedCommand ||
                "визуализ" in normalizedCommand ||
                "интерфейс" in normalizedCommand
            ) &&
            (
                "аяна" in normalizedCommand ||
                    "ayana" in normalizedCommand ||
                    "android" in normalizedCommand ||
                    "kotlin" in normalizedCommand
                )

    private fun projectRequestResolutionKey(
        normalizedCommand: String
    ): String =
        normalizedCommand
            .replace("[мультимодальное вложение]", "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun containsPositiveAndroidKotlinEvidence(
        resultText: String
    ): Boolean {
        val resultLower =
            resultText.lowercase(
                Locale.ROOT
            )

        return listOf(
            "android/kotlin",
            "package kg.autonomous.agent",
            "import android.",
            "android.graphics.canvas",
            "android.view.view",
            ": view(",
            "class ayanavisual"
        ).any { marker ->
            marker in resultLower
        }
    }

    private fun isExpectedLongDiagnosticCommand(
        normalizedCommand: String
    ): Boolean =
        (
            "полную диагностику ayana" in normalizedCommand ||
                "полная диагностика ayana" in normalizedCommand ||
                "всестороннюю диагностику ayana" in normalizedCommand ||
                "exhaustive diagnostics" in normalizedCommand
            )

    private fun detectHistoryAnomalies(
        status: String,
        command: String,
        resultText: String,
        technical: String,
        durationMs: Long,
        projectPlatformAndroid: Boolean
    ): List<JSONObject> {
        val anomalies = mutableListOf<JSONObject>()
        val normalizedResult = normalize(resultText)
        val normalizedCommand = normalize(command)
        val normalizedTechnical = technical.lowercase(Locale.ROOT)

        val failureLanguage =
            listOf(
                "не удалось",
                "не могу выполнить",
                "недоступно",
                "не поддерживается",
                "невозможно выполнить",
                "нет возможности"
            ).any { it in normalizedResult }

        val looksActionIntent =
            listOf(
                "открой", "закрой", "установи", "поставь", "уменьш", "увелич",
                "создай", "удали", "включ", "выключ", "покажи", "проверь",
                "найди", "перейди", "зайди", "нажми", "напомни", "сохрани"
            ).any { it in normalizedCommand }

        if (status == "SUCCESS" && failureLanguage && looksActionIntent) {
            anomalies +=
                anomaly(
                    kind = "possible_false_success",
                    title = "Historical terminal/result contradiction",
                    message = "History contains SUCCESS while the user-visible result contains failure/unsupported language.",
                    priority = "high",
                    hypothesis = "Проверить Completion Truth для класса команды и воспроизвести безопасным contract-probe."
                )
        }

        val explicitVerifiedFalse =
            "\"verified\":false" in normalizedTechnical ||
                "verified=false" in normalizedTechnical

        if (status == "SUCCESS" && explicitVerifiedFalse) {
            anomalies +=
                anomaly(
                    kind = "success_with_unverified_evidence",
                    title = "Historical SUCCESS with verified=false",
                    message = "History contains terminal SUCCESS together with explicit verified=false evidence.",
                    priority = "high",
                    hypothesis = "Проверить, не обходит ли конкретный executor strict terminal verification."
                )
        }

        val asksForProjectCode =
            looksLikeAyanaProjectCodeRequest(
                normalizedCommand
            ) ||
                (
                    projectPlatformAndroid &&
                        (
                            "код" in normalizedCommand ||
                                "файл" in normalizedCommand ||
                                "визуализ" in normalizedCommand ||
                                "интерфейс" in normalizedCommand
                            )
                    )

        // "Canvas" is not a web-only marker: Android has android.graphics.Canvas.
        // The previous oracle treated any occurrence of "canvas" in a correct
        // Android/Kotlin answer as HTML/web drift and produced a false warning.
        val explicitlyAskedForWeb =
            listOf(
                "html",
                "веб",
                "web",
                "браузер",
                "javascript",
                "typescript",
                "css",
                "react",
                "vue",
                "svelte"
            ).any { it in normalizedCommand }

        val resultLower =
            resultText.lowercase(
                Locale.ROOT
            )

        val androidKotlinEvidence =
            containsPositiveAndroidKotlinEvidence(
                resultText
            )

        val strongWebEvidence =
            listOf(
                "<!doctype html",
                "<html",
                "<script",
                "<style",
                "html-файл",
                "html файл",
                ".html",
                "откройте в браузере",
                "сохраните код как `",
                "javascript",
                "document.getelementbyid",
                "getcontext(\\\"2d\\\")",
                "getcontext('2d')"
            ).any { marker ->
                marker in resultLower
            }

        val resultIsWebOnly =
            strongWebEvidence &&
                !androidKotlinEvidence

        if (asksForProjectCode && !explicitlyAskedForWeb && resultIsWebOnly) {
            anomalies +=
                anomaly(
                    kind = "project_platform_drift",
                    title = "Historical project platform / deliverable drift candidate",
                    message = "History contains an Android/AYANA project-code request that produced a web/HTML-style deliverable without an explicit web request.",
                    priority = "normal",
                    hypothesis = "Проверить сохранение platform context и deliverable contract в multimodal/codegen lane."
                )
        }

        val expectedDiagnosticWindow =
            isExpectedLongDiagnosticCommand(
                normalizedCommand
            ) &&
                durationMs < DIAGNOSTIC_HARD_WARNING_MS

        if (
            durationMs >= HISTORY_SLOW_WARNING_MS &&
            !expectedDiagnosticWindow
        ) {
            anomalies +=
                anomaly(
                    kind = "latency_outlier",
                    title = "Historical latency outlier",
                    message = "Command duration crossed the adaptive latency warning threshold: ${durationMs}ms.",
                    priority = "medium",
                    hypothesis = "Разложить latency по фазам и определить, это server/model wait, upload, Android prepare или executor stall."
                )
        }

        return anomalies
    }

    private fun anomaly(
        kind: String,
        title: String,
        message: String,
        priority: String,
        hypothesis: String
    ): JSONObject =
        JSONObject()
            .put("kind", kind)
            .put("title", title)
            .put("message", message)
            .put("priority", priority)
            .put("hypothesis", hypothesis)
            .put("safe_execution", "reproduce_with_safe_contract_probe")

    private fun maxAnomaliesForKind(
        kind: String
    ): Int =
        when (kind) {
            "latency_outlier" -> 3
            "project_platform_drift" -> 1
            "possible_false_success" -> 5
            "success_with_unverified_evidence" -> 5
            else -> 4
        }

    private fun inferHypothesisPriority(
        id: String,
        note: String
    ): String {
        val value = (id + " " + note).lowercase(Locale.ROOT)
        return when {
            listOf("terminal", "safety", "execution", "network", "foreground", "stop").any { it in value } -> "high"
            listOf("artifact", "multimodal", "memory", "reminder", "settings").any { it in value } -> "medium"
            else -> "normal"
        }
    }

    private fun result(
        id: String,
        title: String,
        status: String,
        critical: Boolean,
        verified: Boolean,
        message: String,
        evidenceScope: String,
        evidence: JSONObject
    ): JSONObject =
        JSONObject()
            .put("id", id.take(96))
            .put("title", title.take(220))
            .put("status", status)
            .put("critical", critical)
            .put("duration_ms", 0L)
            .put("message", message.take(1200))
            .put("evidence_scope", evidenceScope)
            .put("verified", verified)
            .put("evidence", evidence)
            .put("generated", true)
            .put("generator", "AyanaAutonomousTestIntelligence/$ENGINE_VERSION")

    private fun copyJsonObject(
        source: JSONObject
    ): JSONObject {
        val copy = JSONObject()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            copy.put(key, source.opt(key))
        }
        return copy
    }

    private fun safeObject(
        block: () -> JSONObject
    ): JSONObject =
        try {
            block()
        } catch (error: Exception) {
            JSONObject()
                .put("success", false)
                .put("error", error.javaClass.simpleName)
                .put("message", error.message.orEmpty().take(500))
        }

    private fun safeArray(
        block: () -> JSONArray
    ): JSONArray =
        try {
            block()
        } catch (_: Exception) {
            JSONArray()
        }

    private fun normalize(
        value: String
    ): String =
        value
            .lowercase(Locale.getDefault())
            .replace('ё', 'е')
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun shortId(
        value: String
    ): String {
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))

        return digest
            .take(6)
            .joinToString("") { byte ->
                "%02x".format(byte)
            }
            .uppercase(Locale.ROOT)
    }

    companion object {
        const val ENGINE_VERSION = "1.6"

        private const val STATUS_PASS = "PASS"
        private const val STATUS_WARNING = "WARNING"
        private const val STATUS_FAIL = "FAIL"

        private const val MAX_NOTE_CHARS = 1200
        private const val MAX_HISTORY_RECORDS = 60
        private const val MAX_RESOLVER_TESTS = 80
        private const val MAX_PLANNER_APP_TESTS = 24
        private const val MAX_METAMORPHIC_TESTS = 12
        private const val MAX_VARIANTS_PER_COMMAND = 4
        private const val MAX_METAMORPHIC_COMMAND_CHARS = 600
        private const val MAX_GENERATED_TESTS = 180
        private const val HISTORY_SLOW_WARNING_MS = 30_000L
        private const val DIAGNOSTIC_HARD_WARNING_MS = 180_000L
    }
}

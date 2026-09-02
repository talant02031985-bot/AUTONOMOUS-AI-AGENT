package kg.autonomous.agent

import android.content.Context
import android.content.Intent
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * AYANA System Settings Navigator v1.1 — SETTINGS ROUTING + MASTER-PANE RECOVERY.
 *
 * v1.1 preserves v1.0 terminal truth and adds the missing Samsung/large-screen
 * recovery layer:
 * - direct Settings intents are still preferred and must be verified;
 * - generic Intent dispatch is never SUCCESS by itself;
 * - top-level sections may be recovered through a bounded semantic search of
 *   the LEFT Settings master pane (AyanaSettingsMasterPaneNavigator);
 * - Battery gets a verified secondary Android action before semantic recovery;
 * - Battery can recover through Device care -> Battery;
 * - Device care / "Обслуживание устройства" is a first-class Settings section;
 * - Connections verification includes Samsung's factual title "Подключения".
 *
 * No blind coordinates, no fixed tap positions and no generic right-pane scroll
 * are used by this navigator.
 */
class AyanaSystemSettingsNavigator(
    context: Context,
    private val screenIntelligence: AyanaScreenIntelligence,
    private val shouldCancel: () -> Boolean = { false }
) {

    private val appContext =
        context.applicationContext

    private val masterPaneNavigator by lazy {
        AyanaSettingsMasterPaneNavigator(
            shouldCancel = shouldCancel
        )
    }

    data class Route(
        val section: String,
        val primaryAction: String,
        val markers: List<String>,
        val directTargets: List<String>,
        val exactTitleMarkers: List<String> = emptyList(),
        val parentTargets: List<String> = emptyList(),
        val childTargets: List<String> = emptyList(),
        val fallbackActions: List<String> = emptyList(),
        val allowOwnerOnly: Boolean = false
    )

    fun open(
        requestedSection: String
    ): JSONObject {
        val canonical =
            canonicalSection(
                requestedSection
            )
                ?: return unsupported(
                    requestedSection
                )

        val route =
            routeFor(
                canonical
            )
                ?: return unsupported(
                    requestedSection
                )

        if (isCancelled()) {
            return cancelled(
                canonical
            )
        }

        val before =
            safeScreen()

        val beforeFingerprint =
            screenFingerprint(
                before
            )

        // Strongest direct Android action first.
        if (route.primaryAction.isNotBlank()) {
            val directDispatch =
                dispatch(
                    route.primaryAction
                )

            if (directDispatch) {
                val directVerification =
                    awaitVerifiedSection(
                        route = route,
                        beforeFingerprint = beforeFingerprint,
                        timeoutMs = DIRECT_VERIFY_TIMEOUT_MS
                    )

                if (
                    directVerification.optBoolean(
                        "verified",
                        false
                    )
                ) {
                    return successResult(
                        route = route,
                        requestedSection = requestedSection,
                        verification = directVerification,
                        dispatchAction = route.primaryAction,
                        mode = "direct_intent_verified"
                    )
                }
            }
        }

        if (isCancelled()) {
            return cancelled(
                canonical
            )
        }

        // v1.1: some OEMs expose a useful legacy/secondary direct action while
        // android.settings.BATTERY_SETTINGS lands on the wrong surface.
        for (
            fallbackAction in
            route.fallbackActions
                .filter {
                    it.isNotBlank() &&
                        it != route.primaryAction
                }
                .distinct()
        ) {
            val fallbackBefore =
                screenFingerprint(
                    safeScreen()
                )

            if (
                dispatch(
                    fallbackAction
                )
            ) {
                val fallbackVerification =
                    awaitVerifiedSection(
                        route = route,
                        beforeFingerprint = fallbackBefore,
                        timeoutMs = DIRECT_VERIFY_TIMEOUT_MS
                    )

                if (
                    fallbackVerification.optBoolean(
                        "verified",
                        false
                    )
                ) {
                    return successResult(
                        route = route,
                        requestedSection = requestedSection,
                        verification = fallbackVerification,
                        dispatchAction = fallbackAction,
                        mode = "fallback_intent_verified"
                    )
                }
            }

            if (isCancelled()) {
                return cancelled(
                    canonical
                )
            }
        }

        // Exact direct action either does not exist on this OEM or did not land
        // on the requested section. Recover from Settings root.
        val rootBefore =
            safeScreen()

        val rootBeforeFingerprint =
            screenFingerprint(
                rootBefore
            )

        if (
            !dispatch(
                Settings.ACTION_SETTINGS
            )
        ) {
            return failure(
                route = route,
                requestedSection = requestedSection,
                reason = "settings_root_dispatch_failed",
                screen = safeScreen(),
                actionAccepted = false
            )
        }

        val root =
            awaitSettingsOwner(
                beforeFingerprint =
                    rootBeforeFingerprint,
                timeoutMs =
                    ROOT_READY_TIMEOUT_MS
            )

        if (
            !root.optBoolean(
                "settings_owner_verified",
                false
            )
        ) {
            return failure(
                route = route,
                requestedSection = requestedSection,
                reason = "settings_owner_not_verified",
                screen =
                    root.optJSONObject(
                        "screen"
                    )
                        ?: safeScreen(),
                actionAccepted = true
            )
        }

        if (
            route.allowOwnerOnly &&
            route.markers.isEmpty()
        ) {
            val rootScreen =
                root.optJSONObject(
                    "screen"
                )
                    ?: safeScreen()

            return JSONObject()
                .put(
                    "success",
                    true
                )
                .put(
                    "verified",
                    true
                )
                .put(
                    "action_accepted",
                    true
                )
                .put(
                    "terminal_status",
                    "SUCCESS"
                )
                .put(
                    "status",
                    "settings_section_verified"
                )
                .put(
                    "reason",
                    "settings_root_owner_verified"
                )
                .put(
                    "section",
                    route.section
                )
                .put(
                    "requested_section",
                    requestedSection
                )
                .put(
                    "canonical_section",
                    route.section
                )
                .put(
                    "dispatch_action",
                    Settings.ACTION_SETTINGS
                )
                .put(
                    "verification_mode",
                    "settings_root_owner_verified"
                )
                .put(
                    "proof_level",
                    "settings_foreground_owner"
                )
                .put(
                    "settings_owner_verified",
                    true
                )
                .put(
                    "screen_changed",
                    screenFingerprint(
                        rootScreen
                    ) != beforeFingerprint
                )
                .put(
                    "screen",
                    rootScreen
                )
                .put(
                    "message",
                    "Открыт раздел настроек: ${displayName(route.section)}"
                )
        }

        val recovery =
            recoverFromRoot(
                route
            )

        if (
            recovery.optBoolean(
                "verified",
                false
            )
        ) {
            return successResult(
                route = route,
                requestedSection = requestedSection,
                verification = recovery,
                dispatchAction = Settings.ACTION_SETTINGS,
                mode =
                    recovery.optString(
                        "verification_mode",
                        "semantic_recovery_verified"
                    )
            )
        }

        return failure(
            route = route,
            requestedSection = requestedSection,
            reason =
                recovery.optString(
                    "reason",
                    "settings_section_not_verified"
                ),
            screen =
                recovery.optJSONObject(
                    "screen"
                )
                    ?: safeScreen(),
            actionAccepted = true,
            details = recovery
        )
    }

    private fun recoverFromRoot(
        route: Route
    ): JSONObject {
        if (isCancelled()) {
            return cancelled(
                route.section
            )
        }

        // A. Current factual viewport, no scrolling.
        if (
            route.directTargets
                .isNotEmpty()
        ) {
            val directClick =
                clickAny(
                    route.directTargets
                )

            if (
                directClick.optBoolean(
                    "action_accepted",
                    false
                )
            ) {
                val verification =
                    awaitVerifiedSection(
                        route = route,
                        beforeFingerprint =
                            directClick.optString(
                                "before_fingerprint"
                            ),
                        timeoutMs =
                            RECOVERY_VERIFY_TIMEOUT_MS
                    )

                if (
                    verification.optBoolean(
                        "verified",
                        false
                    )
                ) {
                    return verification
                        .put(
                            "verification_mode",
                            "semantic_root_target_verified"
                        )
                        .put(
                            "recovery_target",
                            directClick.optString(
                                "clicked_target"
                            )
                        )
                }
            }
        }

        if (isCancelled()) {
            return cancelled(
                route.section
            )
        }

        // B. v1.1 bounded search in the LEFT master pane. This is the missing
        // path on Samsung Tab when the requested category is below the viewport.
        if (
            route.directTargets
                .isNotEmpty()
        ) {
            val masterClick =
                masterPaneClick(
                    route.directTargets
                )

            if (
                masterClick.optBoolean(
                    "action_accepted",
                    false
                )
            ) {
                val verification =
                    awaitVerifiedSection(
                        route = route,
                        beforeFingerprint =
                            masterClick.optString(
                                "before_fingerprint"
                            ),
                        timeoutMs =
                            MASTER_RECOVERY_VERIFY_TIMEOUT_MS
                    )

                if (
                    verification.optBoolean(
                        "verified",
                        false
                    )
                ) {
                    return verification
                        .put(
                            "verification_mode",
                            "semantic_master_pane_target_verified"
                        )
                        .put(
                            "recovery_target",
                            masterClick.optString(
                                "clicked_target"
                            )
                        )
                        .put(
                            "master_search_mode",
                            masterClick.optString(
                                "search_mode"
                            )
                        )
                }
            }
        }

        if (isCancelled()) {
            return cancelled(
                route.section
            )
        }

        // C. A direct action may have landed on the OEM parent page. Try its
        // factual child once before rebuilding the route.
        if (
            route.childTargets
                .isNotEmpty()
        ) {
            val childDirect =
                clickAny(
                    route.childTargets
                )

            if (
                childDirect.optBoolean(
                    "action_accepted",
                    false
                )
            ) {
                val verification =
                    awaitVerifiedSection(
                        route = route,
                        beforeFingerprint =
                            childDirect.optString(
                                "before_fingerprint"
                            ),
                        timeoutMs =
                            RECOVERY_VERIFY_TIMEOUT_MS
                    )

                if (
                    verification.optBoolean(
                        "verified",
                        false
                    )
                ) {
                    return verification
                        .put(
                            "verification_mode",
                            "semantic_current_parent_child_verified"
                        )
                        .put(
                            "recovery_target",
                            childDirect.optString(
                                "clicked_target"
                            )
                        )
                }
            }
        }

        if (isCancelled()) {
            return cancelled(
                route.section
            )
        }

        // D. Two-level OEM path. Battery on Samsung can be exposed as:
        // Settings master -> Обслуживание устройства -> Батарея.
        if (
            route.parentTargets
                .isNotEmpty() &&
            route.childTargets
                .isNotEmpty()
        ) {
            val resetBefore =
                screenFingerprint(
                    safeScreen()
                )

            dispatch(
                Settings.ACTION_SETTINGS
            )

            val reset =
                awaitSettingsOwner(
                    beforeFingerprint =
                        resetBefore,
                    timeoutMs =
                        ROOT_READY_TIMEOUT_MS
                )

            if (
                reset.optBoolean(
                    "settings_owner_verified",
                    false
                )
            ) {
                var parentClick =
                    clickAny(
                        route.parentTargets
                    )

                if (
                    !parentClick.optBoolean(
                        "action_accepted",
                        false
                    )
                ) {
                    parentClick =
                        masterPaneClick(
                            route.parentTargets
                        )
                }

                if (
                    parentClick.optBoolean(
                        "action_accepted",
                        false
                    )
                ) {
                    // Let the right detail pane reacquire after the master row
                    // click before asking Screen Intelligence for the child.
                    // This wait does not grant success; it only waits for a factual
                    // screen fingerprint transition and remains bounded.
                    awaitScreenChange(
                        beforeFingerprint =
                            parentClick.optString(
                                "before_fingerprint"
                            ),
                        timeoutMs =
                            PARENT_SETTLE_TIMEOUT_MS
                    )

                    val childClick =
                        clickAny(
                            route.childTargets
                        )

                    if (
                        childClick.optBoolean(
                            "action_accepted",
                            false
                        )
                    ) {
                        val verification =
                            awaitVerifiedSection(
                                route = route,
                                beforeFingerprint =
                                    childClick.optString(
                                        "before_fingerprint"
                                    ),
                                timeoutMs =
                                    MASTER_RECOVERY_VERIFY_TIMEOUT_MS
                            )

                        if (
                            verification.optBoolean(
                                "verified",
                                false
                            )
                        ) {
                            return verification
                                .put(
                                    "verification_mode",
                                    "semantic_master_parent_child_verified"
                                )
                                .put(
                                    "recovery_parent",
                                    parentClick.optString(
                                        "clicked_target"
                                    )
                                )
                                .put(
                                    "recovery_target",
                                    childClick.optString(
                                        "clicked_target"
                                    )
                                )
                                .put(
                                    "master_search_mode",
                                    parentClick.optString(
                                        "search_mode"
                                    )
                                )
                        }
                    }
                }
            }
        }

        return JSONObject()
            .put(
                "success",
                false
            )
            .put(
                "verified",
                false
            )
            .put(
                "reason",
                "semantic_recovery_not_verified"
            )
            .put(
                "terminal_status",
                "ERROR"
            )
            .put(
                "screen",
                safeScreen()
            )
    }

    private fun masterPaneClick(
        targets: List<String>
    ): JSONObject {
        val before =
            screenFingerprint(
                safeScreen()
            )

        val result =
            try {
                masterPaneNavigator
                    .findAndClick(
                        targets
                    )
            } catch (
                error: Exception
            ) {
                JSONObject()
                    .put(
                        "success",
                        false
                    )
                    .put(
                        "verified",
                        false
                    )
                    .put(
                        "action_accepted",
                        false
                    )
                    .put(
                        "terminal_status",
                        "ERROR"
                    )
                    .put(
                        "reason",
                        "master_pane_exception:${error.javaClass.simpleName}"
                    )
            }

        return result
            .put(
                "before_fingerprint",
                before
            )
    }

    private fun clickAny(
        targets: List<String>
    ): JSONObject {
        val before =
            safeScreen()

        val beforeFingerprint =
            screenFingerprint(
                before
            )

        var last =
            JSONObject()
                .put(
                    "success",
                    false
                )
                .put(
                    "action_accepted",
                    false
                )

        for (
            target in
            targets.distinct()
        ) {
            if (isCancelled()) {
                return cancelled(
                    ""
                )
            }

            val result =
                try {
                    screenIntelligence
                        .click(
                            target = target,
                            confirmed = false
                        )
                } catch (
                    error: Exception
                ) {
                    JSONObject()
                        .put(
                            "success",
                            false
                        )
                        .put(
                            "verified",
                            false
                        )
                        .put(
                            "action_accepted",
                            false
                        )
                        .put(
                            "reason",
                            error.javaClass.simpleName
                        )
                }

            last =
                result

            if (
                result.optBoolean(
                    "success",
                    false
                ) ||
                result.optBoolean(
                    "action_accepted",
                    false
                ) ||
                result.optBoolean(
                    "screen_changed",
                    false
                )
            ) {
                return result
                    .put(
                        "clicked_target",
                        target
                    )
                    .put(
                        "before_fingerprint",
                        beforeFingerprint
                    )
            }
        }

        return last
            .put(
                "before_fingerprint",
                beforeFingerprint
            )
    }

    private fun awaitScreenChange(
        beforeFingerprint: String,
        timeoutMs: Long
    ): JSONObject {
        val deadline =
            System.currentTimeMillis() +
                timeoutMs.coerceAtLeast(
                    0L
                )

        var latest =
            safeScreen()

        while (
            System.currentTimeMillis() <
            deadline &&
            !isCancelled()
        ) {
            val fingerprint =
                screenFingerprint(
                    latest
                )

            if (
                beforeFingerprint.isNotBlank() &&
                fingerprint.isNotBlank() &&
                fingerprint != beforeFingerprint
            ) {
                return JSONObject()
                    .put(
                        "changed",
                        true
                    )
                    .put(
                        "screen",
                        latest
                    )
            }

            sleep(
                POLL_MS
            )

            latest =
                safeScreen()
        }

        return JSONObject()
            .put(
                "changed",
                false
            )
            .put(
                "screen",
                latest
            )
    }

    private fun awaitVerifiedSection(
        route: Route,
        beforeFingerprint: String,
        timeoutMs: Long
    ): JSONObject {
        val deadline =
            System.currentTimeMillis() +
                timeoutMs.coerceAtLeast(
                    0L
                )

        var latest =
            safeScreen()

        do {
            if (isCancelled()) {
                return cancelled(
                    route.section
                )
            }

            val verification =
                verifySection(
                    route,
                    latest
                )

            if (
                verification.optBoolean(
                    "verified",
                    false
                )
            ) {
                return verification
                    .put(
                        "screen_changed",
                        beforeFingerprint.isNotBlank() &&
                            screenFingerprint(
                                latest
                            )
                                .isNotBlank() &&
                            beforeFingerprint !=
                            screenFingerprint(
                                latest
                            )
                    )
                    .put(
                        "screen",
                        latest
                    )
            }

            if (
                System.currentTimeMillis() >=
                deadline
            ) {
                break
            }

            sleep(
                POLL_MS
            )

            latest =
                safeScreen()

        } while (true)

        val final =
            verifySection(
                route,
                latest
            )

        return final
            .put(
                "screen_changed",
                beforeFingerprint !=
                    screenFingerprint(
                        latest
                    )
            )
            .put(
                "screen",
                latest
            )
    }

    private fun awaitSettingsOwner(
        beforeFingerprint: String,
        timeoutMs: Long
    ): JSONObject {
        val deadline =
            System.currentTimeMillis() +
                timeoutMs.coerceAtLeast(
                    0L
                )

        var latest =
            safeScreen()

        do {
            if (isCancelled()) {
                return cancelled(
                    ""
                )
            }

            val owner =
                settingsOwnerProof(
                    latest
                )

            if (owner.first) {
                return JSONObject()
                    .put(
                        "success",
                        true
                    )
                    .put(
                        "verified",
                        true
                    )
                    .put(
                        "settings_owner_verified",
                        true
                    )
                    .put(
                        "settings_owner_source",
                        owner.second
                    )
                    .put(
                        "screen_changed",
                        beforeFingerprint !=
                            screenFingerprint(
                                latest
                            )
                    )
                    .put(
                        "screen",
                        latest
                    )
            }

            if (
                System.currentTimeMillis() >=
                deadline
            ) {
                break
            }

            sleep(
                POLL_MS
            )

            latest =
                safeScreen()

        } while (true)

        return JSONObject()
            .put(
                "success",
                false
            )
            .put(
                "verified",
                false
            )
            .put(
                "settings_owner_verified",
                false
            )
            .put(
                "screen_changed",
                beforeFingerprint !=
                    screenFingerprint(
                        latest
                    )
            )
            .put(
                "screen",
                latest
            )
    }

    private fun verifySection(
        route: Route,
        screen: JSONObject
    ): JSONObject {
        if (
            !screen.optBoolean(
                "snapshot_success",
                screen.optBoolean(
                    "success",
                    false
                )
            )
        ) {
            return JSONObject()
                .put(
                    "verified",
                    false
                )
                .put(
                    "reason",
                    "screen_snapshot_unavailable"
                )
                .put(
                    "settings_owner_verified",
                    false
                )
        }

        val owner =
            settingsOwnerProof(
                screen
            )

        if (!owner.first) {
            return JSONObject()
                .put(
                    "verified",
                    false
                )
                .put(
                    "reason",
                    "settings_owner_not_verified"
                )
                .put(
                    "settings_owner_verified",
                    false
                )
        }

        if (
            route.allowOwnerOnly &&
            route.markers.isEmpty()
        ) {
            return JSONObject()
                .put(
                    "verified",
                    true
                )
                .put(
                    "reason",
                    "settings_owner_verified"
                )
                .put(
                    "settings_owner_verified",
                    true
                )
                .put(
                    "settings_owner_source",
                    owner.second
                )
                .put(
                    "matched_marker",
                    ""
                )
                .put(
                    "proof_level",
                    "settings_foreground_owner"
                )
        }

        val corpus =
            settingsVerificationCorpus(
                screen
            )

        val normalizedCorpus =
            normalize(
                corpus
            )

        val titles =
            settingsFactualTitles(
                screen
            )

        val exactTitleMatch =
            route.exactTitleMarkers
                .firstOrNull {
                    marker ->
                    val normalizedMarker =
                        normalize(
                            marker
                        )

                    normalizedMarker.isNotBlank() &&
                        titles.any {
                            title ->
                            normalize(
                                title
                            ) ==
                                normalizedMarker
                        }
                }

        val corpusMatch =
            route.markers
                .firstOrNull {
                    marker ->
                    val normalizedMarker =
                        normalize(
                            marker
                        )

                    normalizedMarker.isNotBlank() &&
                        normalizedCorpus.contains(
                            normalizedMarker
                        )
                }

        val matched =
            exactTitleMatch
                ?: corpusMatch

        val verified =
            matched != null

        return JSONObject()
            .put(
                "verified",
                verified
            )
            .put(
                "reason",
                when {
                    exactTitleMatch != null ->
                        "settings_section_exact_title_verified"

                    corpusMatch != null ->
                        "settings_section_marker_verified"

                    else ->
                        "settings_section_marker_missing"
                }
            )
            .put(
                "settings_owner_verified",
                true
            )
            .put(
                "settings_owner_source",
                owner.second
            )
            .put(
                "matched_marker",
                matched.orEmpty()
            )
            .put(
                "proof_level",
                when {
                    exactTitleMatch != null ->
                        "same_settings_context_exact_title"

                    corpusMatch != null ->
                        "same_settings_context_section_marker"

                    else ->
                        "settings_owner_only"
                }
            )
            .put(
                "verification_corpus",
                corpus.take(
                    1400
                )
            )
    }

    private fun settingsOwnerProof(
        screen: JSONObject
    ): Pair<Boolean, String> {
        if (
            screen.optString(
                "interaction_package"
            )
                .trim() ==
            SETTINGS_PACKAGE
        ) {
            return true to
                "interaction_package"
        }

        if (
            screen.optString(
                "package"
            )
                .trim() ==
            SETTINGS_PACKAGE
        ) {
            return true to
                "primary_package"
        }

        val ownerPackage =
            screen.optString(
                "foreground_owner_package"
            )
                .trim()

        val ownerAge =
            screen.optLong(
                "foreground_owner_age_ms",
                -1L
            )

        if (
            ownerPackage ==
            SETTINGS_PACKAGE &&
            ownerAge in
            0L..OWNER_FRESH_MS
        ) {
            return true to
                "fresh_foreground_owner"
        }

        val windows =
            screen.optJSONArray(
                "windows"
            )
                ?: return false to
                    "settings_window_missing"

        for (
            index in
            0 until windows.length()
        ) {
            val window =
                windows.optJSONObject(
                    index
                )
                    ?: continue

            if (
                window.optString(
                    "package"
                )
                    .trim() !=
                SETTINGS_PACKAGE
            ) {
                continue
            }

            if (
                window.optBoolean(
                    "interaction_context",
                    false
                ) ||
                window.optBoolean(
                    "focused",
                    false
                ) ||
                window.optBoolean(
                    "active",
                    false
                )
            ) {
                return true to
                    "settings_interaction_window"
            }
        }

        return false to
            "settings_owner_not_proven"
    }

    private fun settingsVerificationCorpus(
        screen: JSONObject
    ): String {
        val values =
            linkedSetOf<String>()

        val windows =
            screen.optJSONArray(
                "windows"
            )

        if (windows != null) {
            var hadSettingsContext =
                false

            for (
                index in
                0 until windows.length()
            ) {
                val window =
                    windows.optJSONObject(
                        index
                    )
                        ?: continue

                if (
                    window.optString(
                        "package"
                    )
                        .trim() !=
                    SETTINGS_PACKAGE
                ) {
                    continue
                }

                val factual =
                    window.optBoolean(
                        "interaction_context",
                        false
                    ) ||
                        window.optBoolean(
                            "focused",
                            false
                        ) ||
                        window.optBoolean(
                            "active",
                            false
                        )

                if (!factual) {
                    continue
                }

                hadSettingsContext =
                    true

                addIfPresent(
                    values,
                    window.optString(
                        "title"
                    )
                )

                addIfPresent(
                    values,
                    window.optString(
                        "verification_text"
                    )
                )

                appendStrings(
                    values,
                    window.optJSONArray(
                        "visible_text"
                    )
                )

                val surface =
                    window.optString(
                        "semantic_surface"
                    )
                        .trim()

                if (
                    surface.isNotBlank()
                ) {
                    values.add(
                        surface
                    )
                }
            }

            if (hadSettingsContext) {
                return values
                    .joinToString(
                        " | "
                    )
            }
        }

        if (
            screen.optString(
                "interaction_package"
            )
                .trim() ==
            SETTINGS_PACKAGE ||
            screen.optString(
                "package"
            )
                .trim() ==
            SETTINGS_PACKAGE
        ) {
            addIfPresent(
                values,
                screen.optString(
                    "primary_window_title"
                )
            )

            addIfPresent(
                values,
                screen.optString(
                    "verification_text"
                )
            )

            appendStrings(
                values,
                screen.optJSONArray(
                    "visible_text"
                )
            )
        }

        return values
            .joinToString(
                " | "
            )
    }

    private fun settingsFactualTitles(
        screen: JSONObject
    ): List<String> {
        val result =
            linkedSetOf<String>()

        val windows =
            screen.optJSONArray(
                "windows"
            )

        if (windows != null) {
            for (
                index in
                0 until windows.length()
            ) {
                val window =
                    windows.optJSONObject(
                        index
                    )
                        ?: continue

                if (
                    window.optString(
                        "package"
                    )
                        .trim() !=
                    SETTINGS_PACKAGE
                ) {
                    continue
                }

                if (
                    !window.optBoolean(
                        "interaction_context",
                        false
                    ) &&
                    !window.optBoolean(
                        "focused",
                        false
                    ) &&
                    !window.optBoolean(
                        "active",
                        false
                    )
                ) {
                    continue
                }

                val title =
                    window.optString(
                        "title"
                    )
                        .replace(
                            Regex("\\s+"),
                            " "
                        )
                        .trim()

                if (
                    title.isNotBlank()
                ) {
                    result.add(
                        title
                    )
                }
            }
        }

        if (
            screen.optString(
                "interaction_package"
            )
                .trim() ==
            SETTINGS_PACKAGE ||
            screen.optString(
                "package"
            )
                .trim() ==
            SETTINGS_PACKAGE
        ) {
            val primaryTitle =
                screen.optString(
                    "primary_window_title"
                )
                    .replace(
                        Regex("\\s+"),
                        " "
                    )
                    .trim()

            if (
                primaryTitle.isNotBlank()
            ) {
                result.add(
                    primaryTitle
                )
            }
        }

        return result
            .toList()
    }

    private fun screenFingerprint(
        screen: JSONObject
    ): String {
        if (
            screen.length() ==
            0
        ) {
            return ""
        }

        val windows =
            screen.optJSONArray(
                "windows"
            )

        val windowSummary =
            buildString {
                if (
                    windows !=
                    null
                ) {
                    val limit =
                        minOf(
                            windows.length(),
                            6
                        )

                    for (
                        index in
                        0 until limit
                    ) {
                        val window =
                            windows.optJSONObject(
                                index
                            )
                                ?: continue

                        if (
                            isNotEmpty()
                        ) {
                            append(
                                "||"
                            )
                        }

                        append(
                            window.optInt(
                                "window_id",
                                -1
                            )
                        )
                        append(
                            ':'
                        )
                        append(
                            window.optString(
                                "package"
                            )
                        )
                        append(
                            ':'
                        )
                        append(
                            window.optString(
                                "title"
                            )
                        )
                        append(
                            ':'
                        )
                        append(
                            window.optString(
                                "semantic_surface"
                            )
                        )
                        append(
                            ':'
                        )
                        append(
                            window.optString(
                                "verification_text"
                            )
                                .take(
                                    500
                                )
                        )
                    }
                }
            }

        return buildString {
            append(
                screen.optString(
                    "interaction_package"
                )
            )
            append(
                '|'
            )
            append(
                screen.optString(
                    "package"
                )
            )
            append(
                '|'
            )
            append(
                screen.optString(
                    "primary_context_id"
                )
            )
            append(
                '|'
            )
            append(
                screen.optString(
                    "foreground_owner_package"
                )
            )
            append(
                '|'
            )
            append(
                screen.optString(
                    "verification_text"
                )
                    .take(
                        800
                    )
            )
            append(
                '|'
            )
            append(
                windowSummary
            )
        }
    }

    private fun routeFor(
        section: String
    ): Route? =
        when (
            section
        ) {
            "general" ->
                Route(
                    section = section,
                    primaryAction =
                        Settings.ACTION_SETTINGS,
                    markers =
                        emptyList(),
                    directTargets =
                        emptyList(),
                    allowOwnerOnly =
                        true
                )

            "connections" ->
                Route(
                    section = section,
                    primaryAction =
                        Settings.ACTION_WIRELESS_SETTINGS,
                    markers =
                        listOf(
                            "Подключения",
                            "Connections",
                            "Сеть и Интернет",
                            "Network & internet",
                            "Network and internet"
                        ),
                    directTargets =
                        listOf(
                            "Подключения",
                            "Connections",
                            "Сеть и Интернет",
                            "Network & internet",
                            "Network and internet"
                        ),
                    exactTitleMarkers =
                        listOf(
                            "Подключения",
                            "Connections",
                            "Сеть и Интернет",
                            "Network & internet",
                            "Network and internet"
                        )
                )

            "wifi" ->
                Route(
                    section = section,
                    primaryAction =
                        Settings.ACTION_WIFI_SETTINGS,
                    markers =
                        listOf(
                            "Wi-Fi",
                            "Wi‑Fi",
                            "WiFi",
                            "WLAN"
                        ),
                    directTargets =
                        listOf(
                            "Wi-Fi",
                            "Wi‑Fi",
                            "WiFi"
                        )
                )

            "bluetooth" ->
                Route(
                    section = section,
                    primaryAction =
                        Settings.ACTION_BLUETOOTH_SETTINGS,
                    markers =
                        listOf(
                            "Bluetooth",
                            "Блютуз"
                        ),
                    directTargets =
                        listOf(
                            "Bluetooth",
                            "Блютуз"
                        )
                )

            "sound" ->
                Route(
                    section = section,
                    primaryAction =
                        Settings.ACTION_SOUND_SETTINGS,
                    markers =
                        listOf(
                            "Звуки и вибрация",
                            "Звук",
                            "Sounds and vibration",
                            "Sound"
                        ),
                    directTargets =
                        listOf(
                            "Звуки и вибрация",
                            "Звук",
                            "Sounds and vibration",
                            "Sound"
                        )
                )

            "display" ->
                Route(
                    section = section,
                    primaryAction =
                        Settings.ACTION_DISPLAY_SETTINGS,
                    markers =
                        listOf(
                            "Дисплей",
                            "Экран",
                            "Display"
                        ),
                    directTargets =
                        listOf(
                            "Дисплей",
                            "Экран",
                            "Display"
                        )
                )

            "apps" ->
                Route(
                    section = section,
                    primaryAction =
                        ACTION_APPLICATION_SETTINGS,
                    markers =
                        listOf(
                            "Приложения",
                            "Apps",
                            "Applications"
                        ),
                    directTargets =
                        listOf(
                            "Приложения",
                            "Apps",
                            "Applications"
                        ),
                    exactTitleMarkers =
                        listOf(
                            "Приложения",
                            "Apps",
                            "Applications"
                        )
                )

            "accessibility" ->
                Route(
                    section = section,
                    primaryAction =
                        Settings.ACTION_ACCESSIBILITY_SETTINGS,
                    markers =
                        listOf(
                            "Специальные возможности",
                            "Accessibility"
                        ),
                    directTargets =
                        listOf(
                            "Специальные возможности",
                            "Accessibility"
                        )
                )

            "location" ->
                Route(
                    section = section,
                    primaryAction =
                        Settings.ACTION_LOCATION_SOURCE_SETTINGS,
                    markers =
                        listOf(
                            "Местоположение",
                            "Геолокация",
                            "Location"
                        ),
                    directTargets =
                        listOf(
                            "Местоположение",
                            "Геолокация",
                            "Location"
                        )
                )

            "security" ->
                Route(
                    section = section,
                    primaryAction =
                        ACTION_SECURITY_SETTINGS,
                    markers =
                        listOf(
                            "Безопасность",
                            "Security",
                            "Безопасность и конфиденциальность",
                            "Security and privacy"
                        ),
                    directTargets =
                        listOf(
                            "Безопасность",
                            "Безопасность и конфиденциальность",
                            "Security",
                            "Security and privacy"
                        )
                )

            "date_time" ->
                Route(
                    section = section,
                    primaryAction =
                        Settings.ACTION_DATE_SETTINGS,
                    markers =
                        listOf(
                            "Дата и время",
                            "Date and time",
                            "Date & time"
                        ),
                    directTargets =
                        listOf(
                            "Дата и время",
                            "Date and time",
                            "Date & time"
                        ),
                    exactTitleMarkers =
                        listOf(
                            "Дата и время",
                            "Date and time",
                            "Date & time"
                        )
                )

            "battery" ->
                Route(
                    section = section,
                    primaryAction =
                        BATTERY_OVERVIEW_ACTION,
                    fallbackActions =
                        listOf(
                            POWER_USAGE_SUMMARY_ACTION
                        ),
                    markers =
                        listOf(
                            "Батарея",
                            "Battery",
                            "Использование батареи",
                            "Battery usage"
                        ),
                    directTargets =
                        listOf(
                            "Батарея",
                            "Battery"
                        ),
                    exactTitleMarkers =
                        listOf(
                            "Батарея",
                            "Battery"
                        ),
                    parentTargets =
                        listOf(
                            "Обслуживание устройства",
                            "Battery and device care",
                            "Device care",
                            "Уход за устройством"
                        ),
                    childTargets =
                        listOf(
                            "Батарея",
                            "Battery"
                        )
                )

            "device_care" ->
                Route(
                    section = section,
                    primaryAction =
                        "",
                    markers =
                        listOf(
                            "Обслуживание устройства",
                            "Battery and device care",
                            "Device care",
                            "Уход за устройством"
                        ),
                    directTargets =
                        listOf(
                            "Обслуживание устройства",
                            "Battery and device care",
                            "Device care",
                            "Уход за устройством"
                        ),
                    exactTitleMarkers =
                        listOf(
                            "Обслуживание устройства",
                            "Battery and device care",
                            "Device care",
                            "Уход за устройством"
                        )
                )

            "storage" ->
                Route(
                    section = section,
                    primaryAction =
                        ACTION_INTERNAL_STORAGE_SETTINGS,
                    markers =
                        listOf(
                            "Хранилище",
                            "Память",
                            "Storage"
                        ),
                    directTargets =
                        listOf(
                            "Хранилище",
                            "Память",
                            "Storage"
                        )
                )

            "notifications" ->
                Route(
                    section = section,
                    primaryAction =
                        Settings.ACTION_NOTIFICATION_SETTINGS,
                    markers =
                        listOf(
                            "Уведомления",
                            "Notifications",
                            "Уведомления приложений",
                            "App notifications"
                        ),
                    directTargets =
                        listOf(
                            "Уведомления",
                            "Notifications"
                        )
                )

            "data_usage" ->
                Route(
                    section = section,
                    primaryAction =
                        Settings.ACTION_DATA_USAGE_SETTINGS,
                    markers =
                        listOf(
                            "Использование данных",
                            "Data usage",
                            "Мобильные данные",
                            "Mobile data"
                        ),
                    directTargets =
                        listOf(
                            "Использование данных",
                            "Data usage",
                            "Мобильные данные",
                            "Mobile data"
                        )
                )

            "vpn" ->
                Route(
                    section = section,
                    primaryAction =
                        ACTION_VPN_SETTINGS,
                    markers =
                        listOf(
                            "VPN"
                        ),
                    directTargets =
                        listOf(
                            "VPN"
                        )
                )

            "nfc" ->
                Route(
                    section = section,
                    primaryAction =
                        ACTION_NFC_SETTINGS,
                    markers =
                        listOf(
                            "NFC",
                            "Бесконтактные платежи",
                            "Contactless payments"
                        ),
                    directTargets =
                        listOf(
                            "NFC"
                        )
                )

            "language" ->
                Route(
                    section = section,
                    primaryAction =
                        Settings.ACTION_LOCALE_SETTINGS,
                    markers =
                        listOf(
                            "Язык",
                            "Языки",
                            "Languages",
                            "Language"
                        ),
                    directTargets =
                        listOf(
                            "Язык",
                            "Языки",
                            "Languages",
                            "Language"
                        )
                )

            "keyboard" ->
                Route(
                    section = section,
                    primaryAction =
                        Settings.ACTION_INPUT_METHOD_SETTINGS,
                    markers =
                        listOf(
                            "Клавиатура",
                            "Keyboard",
                            "Экранная клавиатура",
                            "On-screen keyboard"
                        ),
                    directTargets =
                        listOf(
                            "Клавиатура",
                            "Keyboard"
                        )
                )

            "default_apps" ->
                Route(
                    section = section,
                    primaryAction =
                        ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
                    markers =
                        listOf(
                            "Приложения по умолчанию",
                            "Default apps"
                        ),
                    directTargets =
                        listOf(
                            "Приложения по умолчанию",
                            "Default apps"
                        )
                )

            "developer_options" ->
                Route(
                    section = section,
                    primaryAction =
                        Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
                    markers =
                        listOf(
                            "Параметры разработчика",
                            "Для разработчиков",
                            "Developer options"
                        ),
                    directTargets =
                        listOf(
                            "Параметры разработчика",
                            "Для разработчиков",
                            "Developer options"
                        )
                )

            "device_info" ->
                Route(
                    section = section,
                    primaryAction =
                        Settings.ACTION_DEVICE_INFO_SETTINGS,
                    markers =
                        listOf(
                            "Сведения о планшете",
                            "Сведения об устройстве",
                            "Об устройстве",
                            "About tablet",
                            "About device"
                        ),
                    directTargets =
                        listOf(
                            "Сведения о планшете",
                            "Сведения об устройстве",
                            "Об устройстве",
                            "About tablet",
                            "About device"
                        )
                )

            "privacy" ->
                Route(
                    section = section,
                    primaryAction =
                        ACTION_PRIVACY_SETTINGS,
                    markers =
                        listOf(
                            "Конфиденциальность",
                            "Privacy",
                            "Безопасность и конфиденциальность",
                            "Security and privacy"
                        ),
                    directTargets =
                        listOf(
                            "Конфиденциальность",
                            "Privacy",
                            "Безопасность и конфиденциальность",
                            "Security and privacy"
                        )
                )

            "battery_optimization" ->
                Route(
                    section = section,
                    primaryAction =
                        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                    markers =
                        listOf(
                            "Оптимизация батареи",
                            "Battery optimization",
                            "Оптимизация энергопотребления"
                        ),
                    directTargets =
                        listOf(
                            "Оптимизация батареи",
                            "Battery optimization",
                            "Оптимизация энергопотребления"
                        )
                )

            else ->
                null
        }

    private fun canonicalSection(
        raw: String
    ): String? {
        val value =
            normalize(
                raw
            )

        if (
            value.isBlank()
        ) {
            return "general"
        }

        return when {
            value in
                setOf(
                    "general",
                    "settings",
                    "настройки",
                    "общие настройки"
                ) ->
                "general"

            value.contains(
                "обслуживан"
            ) &&
                value.contains(
                    "устройств"
                ) ||
                value.contains(
                    "уход за устрой"
                ) ||
                value.contains(
                    "device care"
                ) ||
                value.contains(
                    "battery and device care"
                ) ->
                "device_care"

            value.contains(
                "подключ"
            ) ||
                value ==
                "connections" ||
                value.contains(
                    "network internet"
                ) ||
                value.contains(
                    "сеть интернет"
                ) ->
                "connections"

            value in
                setOf(
                    "wifi",
                    "wi fi",
                    "вай фай",
                    "вайфай",
                    "wlan"
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
                value.contains(
                    "sound"
                ) ->
                "sound"

            value.contains(
                "диспле"
            ) ||
                value.contains(
                    "экран"
                ) ||
                value ==
                "display" ->
                "display"

            value.contains(
                "приложен"
            ) ||
                value ==
                "apps" ->
                "apps"

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
                    "геолокац"
                ) ||
                value ==
                "location" ->
                "location"

            value.contains(
                "безопасност"
            ) ||
                value ==
                "security" ->
                "security"

            (
                value.contains(
                    "дат"
                ) &&
                    value.contains(
                        "врем"
                    )
                ) ||
                value ==
                "date time" ->
                "date_time"

            value.contains(
                "оптимизац"
            ) &&
                value.contains(
                    "батар"
                ) ->
                "battery_optimization"

            value.contains(
                "батар"
            ) ||
                value.contains(
                    "аккумуля"
                ) ||
                value ==
                "battery" ->
                "battery"

            value.contains(
                "хранилищ"
            ) ||
                value.contains(
                    "памят"
                ) ||
                value ==
                "storage" ->
                "storage"

            value.contains(
                "уведомлен"
            ) ||
                value ==
                "notifications" ->
                "notifications"

            (
                value.contains(
                    "использован"
                ) &&
                    value.contains(
                        "дан"
                    )
                ) ||
                value ==
                "data usage" ->
                "data_usage"

            value ==
                "vpn" ||
                value ==
                "впн" ->
                "vpn"

            value ==
                "nfc" ||
                value ==
                "нфс" ->
                "nfc"

            value.contains(
                "язык"
            ) ||
                value ==
                "language" ||
                value ==
                "languages" ->
                "language"

            value.contains(
                "клавиатур"
            ) ||
                value.contains(
                    "метод ввода"
                ) ||
                value ==
                "keyboard" ->
                "keyboard"

            value.contains(
                "по умолчани"
            ) ||
                value ==
                "default apps" ->
                "default_apps"

            value.contains(
                "разработчик"
            ) ||
                value ==
                "developer options" ->
                "developer_options"

            value.contains(
                "сведения"
            ) ||
                value.contains(
                    "об устройстве"
                ) ||
                value.contains(
                    "о планшете"
                ) ||
                value ==
                "device info" ->
                "device_info"

            value.contains(
                "конфиденциаль"
            ) ||
                value.contains(
                    "приват"
                ) ||
                value ==
                "privacy" ->
                "privacy"

            else ->
                null
        }
    }

    private fun successResult(
        route: Route,
        requestedSection: String,
        verification: JSONObject,
        dispatchAction: String,
        mode: String
    ): JSONObject {
        val screen =
            verification.optJSONObject(
                "screen"
            )
                ?: safeScreen()

        return JSONObject()
            .put(
                "success",
                true
            )
            .put(
                "verified",
                true
            )
            .put(
                "action_accepted",
                true
            )
            .put(
                "terminal_status",
                "SUCCESS"
            )
            .put(
                "status",
                "settings_section_verified"
            )
            .put(
                "reason",
                verification.optString(
                    "reason",
                    "settings_section_verified"
                )
            )
            .put(
                "section",
                route.section
            )
            .put(
                "requested_section",
                requestedSection
            )
            .put(
                "canonical_section",
                route.section
            )
            .put(
                "dispatch_action",
                dispatchAction
            )
            .put(
                "verification_mode",
                mode
            )
            .put(
                "proof_level",
                verification.optString(
                    "proof_level",
                    "same_settings_context_section_marker"
                )
            )
            .put(
                "settings_owner_verified",
                verification.optBoolean(
                    "settings_owner_verified",
                    true
                )
            )
            .put(
                "settings_owner_source",
                verification.optString(
                    "settings_owner_source"
                )
            )
            .put(
                "matched_marker",
                verification.optString(
                    "matched_marker"
                )
            )
            .put(
                "screen_changed",
                verification.optBoolean(
                    "screen_changed",
                    false
                )
            )
            .put(
                "screen",
                screen
            )
            .put(
                "message",
                "Открыт и подтверждён раздел настроек: ${displayName(route.section)}"
            )
    }

    private fun failure(
        route: Route,
        requestedSection: String,
        reason: String,
        screen: JSONObject,
        actionAccepted: Boolean,
        details: JSONObject? = null
    ): JSONObject {
        val result =
            JSONObject()
                .put(
                    "success",
                    false
                )
                .put(
                    "verified",
                    false
                )
                .put(
                    "action_accepted",
                    actionAccepted
                )
                .put(
                    "terminal_status",
                    "ERROR"
                )
                .put(
                    "status",
                    "settings_section_verify_failed"
                )
                .put(
                    "reason",
                    reason
                )
                .put(
                    "section",
                    route.section
                )
                .put(
                    "requested_section",
                    requestedSection
                )
                .put(
                    "canonical_section",
                    route.section
                )
                .put(
                    "settings_owner_verified",
                    settingsOwnerProof(
                        screen
                    ).first
                )
                .put(
                    "screen",
                    screen
                )
                .put(
                    "message",
                    "Android открыл Settings, но раздел «${displayName(route.section)}» не удалось надёжно подтвердить"
                )

        if (
            details !=
            null
        ) {
            result.put(
                "recovery",
                details
            )
        }

        return result
    }

    private fun unsupported(
        requestedSection: String
    ): JSONObject =
        JSONObject()
            .put(
                "success",
                false
            )
            .put(
                "verified",
                false
            )
            .put(
                "action_accepted",
                false
            )
            .put(
                "terminal_status",
                "UNSUPPORTED"
            )
            .put(
                "status",
                "settings_section_unsupported"
            )
            .put(
                "reason",
                "unsupported_settings_section"
            )
            .put(
                "requested_section",
                requestedSection
            )
            .put(
                "canonical_section",
                ""
            )
            .put(
                "message",
                "Раздел системных настроек «$requestedSection» пока не поддерживается"
            )

    private fun cancelled(
        section: String
    ): JSONObject =
        JSONObject()
            .put(
                "success",
                false
            )
            .put(
                "verified",
                false
            )
            .put(
                "action_accepted",
                false
            )
            .put(
                "terminal_status",
                "CANCELLED"
            )
            .put(
                "status",
                "settings_navigation_cancelled"
            )
            .put(
                "reason",
                "cancelled"
            )
            .put(
                "section",
                section
            )
            .put(
                "canonical_section",
                section
            )
            .put(
                "message",
                "Переход в настройки отменён"
            )

    private fun safeScreen():
        JSONObject =
        try {
            screenIntelligence
                .getScreenState()
        } catch (
            error: Exception
        ) {
            JSONObject()
                .put(
                    "success",
                    false
                )
                .put(
                    "snapshot_success",
                    false
                )
                .put(
                    "reason",
                    error.javaClass.simpleName
                )
        }

    private fun dispatch(
        action: String
    ): Boolean {
        if (
            action.isBlank()
        ) {
            return false
        }

        val intent =
            Intent(
                action
            ).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }

        val resolvable =
            try {
                intent.resolveActivity(
                    appContext.packageManager
                ) !=
                    null
            } catch (
                _: Exception
            ) {
                true
            }

        if (!resolvable) {
            return false
        }

        return try {
            appContext.startActivity(
                intent
            )
            true
        } catch (
            _: Exception
        ) {
            false
        }
    }

    private fun appendStrings(
        target: MutableSet<String>,
        array: JSONArray?
    ) {
        if (
            array ==
            null
        ) {
            return
        }

        for (
            index in
            0 until array.length()
        ) {
            addIfPresent(
                target,
                array.optString(
                    index
                )
            )
        }
    }

    private fun addIfPresent(
        target: MutableSet<String>,
        value: String?
    ) {
        val clean =
            value
                .orEmpty()
                .replace(
                    Regex("\\s+"),
                    " "
                )
                .trim()

        if (
            clean.isNotBlank()
        ) {
            target.add(
                clean.take(
                    1800
                )
            )
        }
    }

    private fun normalize(
        value: String
    ): String =
        value
            .lowercase(
                Locale.ROOT
            )
            .replace(
                'ё',
                'е'
            )
            .replace(
                Regex(
                    "[^\\p{L}\\p{N}]+"
                ),
                " "
            )
            .replace(
                Regex(
                    "\\s+"
                ),
                " "
            )
            .trim()

    private fun displayName(
        section: String
    ): String =
        when (
            section
        ) {
            "general" ->
                "Общие настройки"

            "connections" ->
                "Подключения"

            "wifi" ->
                "Wi‑Fi"

            "bluetooth" ->
                "Bluetooth"

            "sound" ->
                "Звук"

            "display" ->
                "Экран"

            "apps" ->
                "Приложения"

            "accessibility" ->
                "Специальные возможности"

            "location" ->
                "Местоположение"

            "security" ->
                "Безопасность"

            "date_time" ->
                "Дата и время"

            "battery" ->
                "Батарея"

            "device_care" ->
                "Обслуживание устройства"

            "storage" ->
                "Хранилище"

            "notifications" ->
                "Уведомления"

            "data_usage" ->
                "Использование данных"

            "vpn" ->
                "VPN"

            "nfc" ->
                "NFC"

            "language" ->
                "Язык"

            "keyboard" ->
                "Клавиатура"

            "default_apps" ->
                "Приложения по умолчанию"

            "developer_options" ->
                "Параметры разработчика"

            "device_info" ->
                "Сведения об устройстве"

            "privacy" ->
                "Конфиденциальность"

            "battery_optimization" ->
                "Оптимизация батареи"

            else ->
                section
        }

    private fun sleep(
        ms: Long
    ) {
        try {
            Thread.sleep(
                ms
            )
        } catch (
            _: InterruptedException
        ) {
            Thread.currentThread()
                .interrupt()
        }
    }

    private fun isCancelled():
        Boolean =
        try {
            shouldCancel()
        } catch (
            _: Exception
        ) {
            false
        }

    companion object {
        private const val SETTINGS_PACKAGE =
            "com.android.settings"

        private const val BATTERY_OVERVIEW_ACTION =
            "android.settings.BATTERY_SETTINGS"

        private const val POWER_USAGE_SUMMARY_ACTION =
            "android.intent.action.POWER_USAGE_SUMMARY"

        private const val ACTION_APPLICATION_SETTINGS =
            "android.settings.APPLICATION_SETTINGS"

        private const val ACTION_SECURITY_SETTINGS =
            "android.settings.SECURITY_SETTINGS"

        private const val ACTION_INTERNAL_STORAGE_SETTINGS =
            "android.settings.INTERNAL_STORAGE_SETTINGS"

        private const val ACTION_VPN_SETTINGS =
            "android.settings.VPN_SETTINGS"

        private const val ACTION_NFC_SETTINGS =
            "android.settings.NFC_SETTINGS"

        private const val ACTION_MANAGE_DEFAULT_APPS_SETTINGS =
            "android.settings.MANAGE_DEFAULT_APPS_SETTINGS"

        private const val ACTION_PRIVACY_SETTINGS =
            "android.settings.PRIVACY_SETTINGS"

        private const val OWNER_FRESH_MS =
            3500L

        private const val DIRECT_VERIFY_TIMEOUT_MS =
            1900L

        private const val ROOT_READY_TIMEOUT_MS =
            1500L

        private const val RECOVERY_VERIFY_TIMEOUT_MS =
            1700L

        private const val MASTER_RECOVERY_VERIFY_TIMEOUT_MS =
            2300L

        private const val PARENT_SETTLE_TIMEOUT_MS =
            1000L

        private const val POLL_MS =
            120L
    }
}

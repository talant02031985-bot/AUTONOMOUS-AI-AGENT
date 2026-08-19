package kg.autonomous.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * AYANA App Resolver v2.1 — APP RESOLVER INTEGRATION.
 *
 * Dynamic source of truth for launchable apps on THIS Android device.
 * Hard-coded aliases and legacy package lists are only hints; every package must
 * be validated against the launcher map observed on THIS device before it can be
 * launched. Successful mappings are cached and validated again before use.
 * Package visibility is provided by the existing AndroidManifest <queries>
 * launcher intent.
 */
class AyanaAppResolver(
    context: Context
) {

    data class AppEntry(
        val label: String,
        val packageName: String,
        val activityName: String
    )

    data class Candidate(
        val entry: AppEntry,
        val score: Int,
        val source: String
    )

    data class Resolution(
        val success: Boolean,
        val requestedName: String,
        val label: String,
        val packageName: String,
        val activityName: String,
        val confidence: Int,
        val source: String,
        val reason: String,
        val alternatives: List<Candidate>
    ) {
        fun toJson(): JSONObject {
            val alternativesJson = JSONArray()
            alternatives.take(5).forEach { candidate ->
                alternativesJson.put(
                    JSONObject()
                        .put("label", candidate.entry.label)
                        .put("package", candidate.entry.packageName)
                        .put("activity", candidate.entry.activityName)
                        .put("score", candidate.score)
                        .put("source", candidate.source)
                )
            }

            return JSONObject()
                .put("success", success)
                .put("requested_name", requestedName)
                .put("label", label)
                .put("package", packageName)
                .put("activity", activityName)
                .put("confidence", confidence)
                .put("source", source)
                .put("reason", reason)
                .put("alternatives", alternativesJson)
        }
    }

    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val cacheFile = File(appContext.filesDir, CACHE_FILE_NAME)
    private val lock = Any()

    @Volatile
    private var cachedScan: List<AppEntry>? = null

    @Volatile
    private var cachedScanAt = 0L

    fun invalidate() {
        cachedScan = null
        cachedScanAt = 0L
    }

    fun listLaunchableApps(
        forceRefresh: Boolean = false
    ): List<AppEntry> {
        val now = System.currentTimeMillis()
        val existing = cachedScan
        if (
            !forceRefresh &&
            existing != null &&
            now - cachedScanAt <= SCAN_CACHE_MS
        ) {
            return existing
        }

        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val activities = try {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, 0)
        } catch (_: Exception) {
            emptyList()
        }

        val result = activities
            .mapNotNull { info ->
                val activityInfo = info.activityInfo ?: return@mapNotNull null
                val packageName = activityInfo.packageName.orEmpty().trim()
                val activityName = activityInfo.name.orEmpty().trim()
                if (packageName.isBlank() || activityName.isBlank()) {
                    return@mapNotNull null
                }

                val label = try {
                    info.loadLabel(packageManager)
                        ?.toString()
                        .orEmpty()
                        .trim()
                } catch (_: Exception) {
                    ""
                }
                    .ifBlank { packageName.substringAfterLast('.') }

                AppEntry(
                    label = label,
                    packageName = packageName,
                    activityName = activityName
                )
            }
            .distinctBy { it.packageName + "|" + it.activityName }
            .sortedWith(
                compareBy<AppEntry> { normalize(it.label) }
                    .thenBy { it.packageName }
            )

        cachedScan = result
        cachedScanAt = now
        return result
    }

    fun resolve(
        requestedName: String,
        forceRefresh: Boolean = false
    ): Resolution {
        val clean = normalizeQuery(requestedName)
        if (clean.isBlank()) {
            return failure(requestedName, "Пустое название приложения")
        }

        val apps = listLaunchableApps(forceRefresh)
        if (apps.isEmpty()) {
            return failure(
                requestedName,
                "Android не вернул список запускаемых приложений. Проверьте package visibility/launcher query."
            )
        }

        val learnedPackage = readLearnedAliases()[clean]
        if (!learnedPackage.isNullOrBlank()) {
            val learnedEntry = apps.firstOrNull { it.packageName == learnedPackage }
            if (learnedEntry != null) {
                return Resolution(
                    success = true,
                    requestedName = requestedName,
                    label = learnedEntry.label,
                    packageName = learnedEntry.packageName,
                    activityName = learnedEntry.activityName,
                    confidence = 100,
                    source = "learned_alias",
                    reason = "Использовано ранее подтверждённое соответствие на этом устройстве",
                    alternatives = emptyList()
                )
            }
        }

        val aliasPackages = staticAliasPackages(clean)
        for (packageName in aliasPackages) {
            val entry = apps.firstOrNull { it.packageName == packageName }
            if (entry != null) {
                learnAlias(clean, entry.packageName)
                return Resolution(
                    success = true,
                    requestedName = requestedName,
                    label = entry.label,
                    packageName = entry.packageName,
                    activityName = entry.activityName,
                    confidence = 99,
                    source = "device_validated_alias",
                    reason = "Пакет алиаса подтверждён среди реально запускаемых приложений",
                    alternatives = emptyList()
                )
            }
        }

        val scored = apps
            .map { entry ->
                Candidate(
                    entry = entry,
                    score = appNameScore(clean, entry.label, entry.packageName),
                    source = "dynamic_label"
                )
            }
            .filter { it.score > 0 }
            .sortedWith(
                compareByDescending<Candidate> { it.score }
                    .thenBy { normalize(it.entry.label) }
            )

        val best = scored.firstOrNull()
        if (best == null || best.score < MIN_CONFIDENCE) {
            return Resolution(
                success = false,
                requestedName = requestedName,
                label = "",
                packageName = "",
                activityName = "",
                confidence = best?.score ?: 0,
                source = "dynamic_label",
                reason = "Надёжного совпадения среди установленных запускаемых приложений нет",
                alternatives = scored.take(5)
            )
        }

        val second = scored.getOrNull(1)
        if (
            second != null &&
            best.score < 96 &&
            best.score - second.score < MIN_WIN_MARGIN
        ) {
            return Resolution(
                success = false,
                requestedName = requestedName,
                label = "",
                packageName = "",
                activityName = "",
                confidence = best.score,
                source = "ambiguous",
                reason = "Найдено несколько слишком близких совпадений; автоматический выбор небезопасен",
                alternatives = scored.take(5)
            )
        }

        learnAlias(clean, best.entry.packageName)
        return Resolution(
            success = true,
            requestedName = requestedName,
            label = best.entry.label,
            packageName = best.entry.packageName,
            activityName = best.entry.activityName,
            confidence = best.score,
            source = best.source,
            reason = "Приложение найдено по фактическому launcher-списку устройства",
            alternatives = scored.drop(1).take(4)
        )
    }

    /**
     * Resolves a user-visible name using the normal resolver first. If that is not
     * conclusive, legacy package candidates may be used only as hints and only
     * after the package is observed in the current launcher map.
     */
    fun resolveWithHints(
        requestedName: String,
        preferredPackages: List<String>,
        forceRefresh: Boolean = false
    ): Resolution {
        val primary =
            resolve(
                requestedName = requestedName,
                forceRefresh = forceRefresh
            )

        if (primary.success) {
            return primary
        }

        val clean = normalizeQuery(requestedName)
        if (clean.isBlank()) {
            return primary
        }

        val hints =
            preferredPackages
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()

        if (hints.isEmpty()) {
            return primary
        }

        val apps = listLaunchableApps(forceRefresh)
        for (packageName in hints) {
            val entry =
                apps.firstOrNull {
                    it.packageName == packageName
                }
                    ?: continue

            learnAlias(
                clean,
                entry.packageName
            )

            return Resolution(
                success = true,
                requestedName = requestedName,
                label = entry.label,
                packageName = entry.packageName,
                activityName = entry.activityName,
                confidence = 98,
                source = "device_validated_legacy_hint",
                reason = "Legacy package hint подтверждён фактической launcher-картой устройства",
                alternatives = emptyList()
            )
        }

        return Resolution(
            success = false,
            requestedName = requestedName,
            label = primary.label,
            packageName = primary.packageName,
            activityName = primary.activityName,
            confidence = primary.confidence,
            source = primary.source,
            reason =
                primary.reason +
                    "; ни один legacy package hint не подтверждён launcher-картой устройства",
            alternatives = primary.alternatives
        )
    }

    fun launch(
        requestedName: String
    ): JSONObject =
        launchResolved(
            resolve(
                requestedName
            )
        )

    fun launchWithHints(
        requestedName: String,
        preferredPackages: List<String>
    ): JSONObject =
        launchResolved(
            resolveWithHints(
                requestedName = requestedName,
                preferredPackages = preferredPackages
            )
        )

    private fun launchResolved(
        resolution: Resolution
    ): JSONObject {
        if (!resolution.success) {
            return resolution.toJson()
                .put(
                    "message",
                    "Приложение не найдено: ${resolution.requestedName}"
                )
        }

        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(
                    resolution.packageName,
                    resolution.activityName
                )
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
            }
            appContext.startActivity(intent)

            resolution.toJson()
                .put("success", true)
                .put("message", "Открыто приложение ${resolution.label}")
        } catch (error: Exception) {
            invalidate()
            resolution.toJson()
                .put("success", false)
                .put(
                    "message",
                    "Не удалось открыть ${resolution.label}: ${error.message ?: "неизвестная ошибка"}"
                )
        }
    }

    fun listAsJson(
        limit: Int = 120,
        forceRefresh: Boolean = false
    ): JSONObject {
        val apps = listLaunchableApps(forceRefresh)
        val array = JSONArray()
        apps.take(limit.coerceIn(1, 300)).forEach { app ->
            array.put(
                JSONObject()
                    .put("label", app.label)
                    .put("package", app.packageName)
                    .put("activity", app.activityName)
            )
        }

        return JSONObject()
            .put("success", true)
            .put("count", apps.size)
            .put("returned", array.length())
            .put("apps", array)
            .put("scan_age_ms", (System.currentTimeMillis() - cachedScanAt).coerceAtLeast(0L))
    }

    fun compactSummary(
        maxApps: Int = 24
    ): String {
        val apps = listLaunchableApps()
        if (apps.isEmpty()) {
            return "Установленные запускаемые приложения: список недоступен."
        }

        val names = apps
            .map { it.label }
            .distinct()
            .take(maxApps.coerceIn(1, 80))
            .joinToString(", ")

        return "Установленные запускаемые приложения: ${apps.size}. Примеры: $names"
    }

    private fun failure(
        requestedName: String,
        reason: String
    ): Resolution =
        Resolution(
            success = false,
            requestedName = requestedName,
            label = "",
            packageName = "",
            activityName = "",
            confidence = 0,
            source = "none",
            reason = reason,
            alternatives = emptyList()
        )

    private fun readLearnedAliases(): Map<String, String> {
        synchronized(lock) {
            if (!cacheFile.exists() || cacheFile.length() == 0L) {
                return emptyMap()
            }

            return try {
                val root = JSONObject(cacheFile.readText(Charsets.UTF_8))
                val aliases = root.optJSONObject("aliases") ?: JSONObject()
                buildMap {
                    val keys = aliases.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = aliases.optString(key).trim()
                        if (key.isNotBlank() && value.isNotBlank()) {
                            put(key, value)
                        }
                    }
                }
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }

    private fun learnAlias(
        normalizedQuery: String,
        packageName: String
    ) {
        if (normalizedQuery.isBlank() || packageName.isBlank()) {
            return
        }

        synchronized(lock) {
            try {
                val aliases = JSONObject()
                readLearnedAliases().forEach { (key, value) ->
                    aliases.put(key, value)
                }
                aliases.put(normalizedQuery, packageName)

                val root = JSONObject()
                    .put("version", 2)
                    .put("updated_at", System.currentTimeMillis())
                    .put("aliases", aliases)

                val temp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
                temp.writeText(root.toString(), Charsets.UTF_8)
                if (cacheFile.exists()) {
                    cacheFile.delete()
                }
                if (!temp.renameTo(cacheFile)) {
                    cacheFile.writeText(root.toString(), Charsets.UTF_8)
                    temp.delete()
                }
            } catch (_: Exception) {
                // Resolution must remain usable even when alias caching fails.
            }
        }
    }

    private fun staticAliasPackages(
        normalizedQuery: String
    ): List<String> {
        return when (normalizedQuery) {
            "youtube", "ютуб" -> listOf("com.google.android.youtube")
            "галерея", "галерею", "фото", "фотографии" ->
                listOf("com.sec.android.gallery3d", "com.google.android.apps.photos")
            "камера", "камеру" -> listOf("com.sec.android.app.camera")
            "калькулятор", "калькулятора" -> listOf("com.sec.android.app.popupcalculator")
            "файлы", "мои файлы" -> listOf("com.sec.android.app.myfiles")
            "переводчик", "переводчика", "гугл переводчик", "google translate", "translate" ->
                listOf("com.google.android.apps.translate")
            "telegram", "телеграм", "телеграмм" -> listOf("org.telegram.messenger")
            "whatsapp", "ватсап", "вотсап" -> listOf("com.whatsapp")
            else -> emptyList()
        }
    }

    private fun appNameScore(
        normalizedQuery: String,
        label: String,
        packageName: String
    ): Int {
        return semanticQueryVariants(normalizedQuery)
            .maxOfOrNull { variant ->
                appNameScoreSingle(
                    variant,
                    label,
                    packageName
                )
            }
            ?: 0
    }

    private fun appNameScoreSingle(
        normalizedQuery: String,
        label: String,
        packageName: String
    ): Int {
        val q = normalizeQuery(normalizedQuery)
        val l = normalizeQuery(label)
        if (q.isBlank() || l.isBlank()) {
            return 0
        }

        if (q == l) {
            return 100
        }

        if (l.contains(q) || q.contains(l)) {
            return 94
        }

        val packageTail = normalize(packageName.substringAfterLast('.'))
        if (q == packageTail) {
            return 90
        }

        val qTokens = q.split(' ').filter { it.isNotBlank() }
        val lTokens = l.split(' ').filter { it.isNotBlank() }
        if (qTokens.isEmpty() || lTokens.isEmpty()) {
            return 0
        }

        val qStems = qTokens.map(::stem)
        val lStems = lTokens.map(::stem)
        var matched = 0
        for (qs in qStems) {
            if (
                lStems.any { ls ->
                    qs.length >= 3 &&
                        ls.length >= 3 &&
                        (qs == ls || qs.startsWith(ls) || ls.startsWith(qs))
                }
            ) {
                matched += 1
            }
        }

        if (matched == 0) {
            return 0
        }

        val coverage = 72 + (24 * matched / qStems.size)
        return coverage.coerceAtMost(96)
    }

    private fun semanticQueryVariants(
        value: String
    ): List<String> {
        val q = normalizeQuery(value)
        val variants = linkedSetOf(q)
        when (q) {
            "калькулятор", "калькулятора", "calculator" -> {
                variants.add("калькулятор")
                variants.add("calculator")
            }
            "галерея", "галерею", "gallery" -> {
                variants.add("галерея")
                variants.add("gallery")
            }
            "фото", "фотографии", "photos" -> {
                variants.add("фото")
                variants.add("photos")
            }
            "камера", "камеру", "camera" -> {
                variants.add("камера")
                variants.add("camera")
            }
            "файлы", "мои файлы", "files" -> {
                variants.add("файлы")
                variants.add("files")
            }
        }
        return variants.toList()
    }

    private fun normalizeQuery(value: String): String =
        normalize(value)
            .removePrefix("приложение ")
            .removePrefix("программу ")
            .removePrefix("программа ")
            .trim()

    private fun normalize(value: String): String =
        value
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("[^a-zа-я0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun stem(value: String): String {
        var result = normalize(value).replace(" ", "")
        if (result.length <= 4) {
            return result
        }

        val endings = listOf(
            "иями", "ями", "ами", "ого", "его", "ому", "ему", "ыми", "ими",
            "ую", "юю", "ая", "яя", "ое", "ее", "ой", "ей", "ом", "ем", "ах",
            "ях", "ам", "ям", "ов", "ев", "ы", "и", "а", "я", "у", "ю", "е", "о"
        )
        for (ending in endings) {
            if (result.endsWith(ending) && result.length - ending.length >= 3) {
                result = result.dropLast(ending.length)
                break
            }
        }
        return result
    }

    companion object {
        private const val CACHE_FILE_NAME = "ayana_app_resolver_aliases.json"
        private const val SCAN_CACHE_MS = 60_000L
        private const val MIN_CONFIDENCE = 78
        private const val MIN_WIN_MARGIN = 8
    }
}

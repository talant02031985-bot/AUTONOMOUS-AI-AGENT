package kg.autonomous.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * AYANA Search Result Store v1.0 — persistent local actions for the latest search.
 *
 * Truth / safety contract:
 * - stores only the latest openable Personal Search results;
 * - keeps Android content:// URIs inside app-private SharedPreferences;
 * - never exposes the raw URI in the user-facing answer;
 * - result numbers match the exact numbers rendered in the latest search answer;
 * - a new search replaces the previous action map, preventing stale-number reuse;
 * - entries expire after a bounded TTL so an old result number cannot silently open
 *   something unrelated days later.
 */
class AyanaSearchResultStore(
    context: Context
) {

    data class Item(
        val resultNumber: Int,
        val source: String,
        val title: String,
        val uri: String,
        val mimeType: String,
        val kind: String,
        val savedAtMs: Long
    )

    private val preferences =
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    @Synchronized
    fun replace(
        items: List<Item>
    ) {
        val now = System.currentTimeMillis()
        val array = JSONArray()

        items
            .asSequence()
            .filter { item ->
                item.resultNumber > 0 &&
                    item.uri.startsWith("content://")
            }
            .take(MAX_ITEMS)
            .forEach { item ->
                array.put(
                    JSONObject()
                        .put("result_number", item.resultNumber)
                        .put("source", item.source.take(40))
                        .put("title", item.title.take(220))
                        .put("uri", item.uri.take(MAX_URI_CHARS))
                        .put("mime_type", item.mimeType.take(160))
                        .put("kind", item.kind.take(40))
                        .put("saved_at_ms", now)
                )
            }

        preferences
            .edit()
            .putLong(KEY_BATCH_SAVED_AT_MS, now)
            .putString(KEY_RESULTS_JSON, array.toString())
            .apply()
    }

    @Synchronized
    fun get(
        resultNumber: Int,
        maxAgeMs: Long = DEFAULT_TTL_MS
    ): Item? {
        if (resultNumber <= 0) {
            return null
        }

        val now = System.currentTimeMillis()
        val batchSavedAt =
            preferences.getLong(
                KEY_BATCH_SAVED_AT_MS,
                0L
            )

        if (
            batchSavedAt <= 0L ||
            now - batchSavedAt > maxAgeMs.coerceAtLeast(1L)
        ) {
            clear()
            return null
        }

        val raw =
            preferences.getString(
                KEY_RESULTS_JSON,
                null
            )
                ?: return null

        val array =
            try {
                JSONArray(raw)
            } catch (_: Exception) {
                clear()
                return null
            }

        for (index in 0 until array.length()) {
            val row =
                array.optJSONObject(index)
                    ?: continue

            if (
                row.optInt("result_number", -1) !=
                resultNumber
            ) {
                continue
            }

            val uri =
                row.optString("uri")
                    .trim()

            if (!uri.startsWith("content://")) {
                return null
            }

            return Item(
                resultNumber = resultNumber,
                source = row.optString("source").trim(),
                title = row.optString("title").trim(),
                uri = uri,
                mimeType = row.optString("mime_type").trim(),
                kind = row.optString("kind").trim(),
                savedAtMs = row.optLong("saved_at_ms", batchSavedAt)
            )
        }

        return null
    }

    @Synchronized
    fun count(): Int {
        val raw =
            preferences.getString(
                KEY_RESULTS_JSON,
                null
            )
                ?: return 0

        return try {
            JSONArray(raw).length()
        } catch (_: Exception) {
            0
        }
    }

    @Synchronized
    fun clear() {
        preferences
            .edit()
            .remove(KEY_RESULTS_JSON)
            .remove(KEY_BATCH_SAVED_AT_MS)
            .apply()
    }

    companion object {
        private const val PREFS_NAME =
            "ayana_search_result_actions_v1"

        private const val KEY_RESULTS_JSON =
            "latest_results_json"

        private const val KEY_BATCH_SAVED_AT_MS =
            "latest_results_saved_at_ms"

        private const val MAX_ITEMS = 20
        private const val MAX_URI_CHARS = 1800

        const val DEFAULT_TTL_MS =
            24L * 60L * 60L * 1000L
    }
}

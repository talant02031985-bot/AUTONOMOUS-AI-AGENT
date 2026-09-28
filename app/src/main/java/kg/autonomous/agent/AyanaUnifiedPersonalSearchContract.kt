package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * AYANA R10.2 Unified Personal Search Contract v1.0.
 *
 * This class does not create a second search engine. It formalizes and audits the
 * already-proven Personal Search stack as one local source-of-truth surface across:
 * - AYANA history;
 * - notifications;
 * - Android-visible file metadata;
 * - locally indexed document contents;
 * - Android-visible photo metadata;
 * - local photo OCR / visual labels / timestamps.
 *
 * Existing Memory and Tasks sources remain available as additional Personal Search
 * sources, but they are not required to satisfy the R10.2 roadmap scope.
 *
 * Safety / truth contract:
 * - search remains local; Agent Core and Worker are not required;
 * - inaccessible Android sources are surfaced as errors, never silently treated as empty;
 * - document-content coverage is distinct from generic file metadata in audit evidence;
 * - photo metadata, OCR, object/scene labels and timestamps are evidence signals only;
 * - no face embedding, face matching or person-identity inference is introduced;
 * - an image label such as "person" is not an identity claim;
 * - raw content:// URIs remain owned by the existing bounded search-result action store;
 * - this class grants no mutation authority and performs no Android action itself.
 */
class AyanaUnifiedPersonalSearchContract {

    fun selfTest(): Boolean {
        val broad =
            AyanaPersonalSearchEngine.parseRequest(
                "найди все про AYANA"
            ) ?: return false

        val expectedAll =
            AyanaPersonalSearchEngine.Source.values().toSet()

        if (broad.sources != expectedAll) return false

        val docs =
            AyanaPersonalSearchEngine.parseRequest(
                "найди в документах договор"
            ) ?: return false

        if (
            docs.sources !=
            setOf(AyanaPersonalSearchEngine.Source.FILES)
        ) {
            return false
        }

        val notifications =
            AyanaPersonalSearchEngine.parseRequest(
                "найди в уведомлениях AYANA"
            ) ?: return false

        if (
            notifications.sources !=
            setOf(AyanaPersonalSearchEngine.Source.NOTIFICATIONS)
        ) {
            return false
        }

        val history =
            AyanaPersonalSearchEngine.parseRequest(
                "найди в истории Camera"
            ) ?: return false

        if (
            history.sources !=
            setOf(AyanaPersonalSearchEngine.Source.HISTORY)
        ) {
            return false
        }

        val photos =
            AyanaPersonalSearchEngine.parseRequest(
                "найди в фото документ"
            ) ?: return false

        if (
            photos.sources !=
            setOf(AyanaPersonalSearchEngine.Source.PHOTOS)
        ) {
            return false
        }

        if (
            AyanaPersonalSearchEngine.parseRequest(
                "найди в google AYANA"
            ) != null
        ) {
            return false
        }

        val followUp =
            AyanaPersonalSearchEngine.parseFollowUp(
                "покажи только фото и уведомления"
            ) ?: return false

        if (
            followUp.sources !=
            setOf(
                AyanaPersonalSearchEngine.Source.PHOTOS,
                AyanaPersonalSearchEngine.Source.NOTIFICATIONS
            )
        ) {
            return false
        }

        return true
    }

    fun inspect(
        report: AyanaPersonalSearchEngine.Report
    ): JSONObject {
        val requested =
            report.request.sources

        val requestedArray =
            JSONArray()
        requested
            .sortedBy { it.ordinal }
            .forEach { source ->
                requestedArray.put(source.wireName)
            }

        val sourceErrors =
            JSONObject()
        report.sourceErrors
            .forEach { (source, detail) ->
                sourceErrors.put(
                    source.wireName,
                    detail.take(240)
                )
            }

        val sourceCounts =
            JSONObject()
        report.sourceMatchCounts
            .forEach { (source, count) ->
                sourceCounts.put(
                    source.wireName,
                    count
                )
            }

        val sourceCoverage =
            JSONObject()
        report.sourceCoverage
            .forEach { (source, detail) ->
                sourceCoverage.put(
                    source.wireName,
                    detail.take(700)
                )
            }

        val allRequestedReported =
            requested.all { source ->
                report.sourceMatchCounts.containsKey(source)
            }

        val historyRequested =
            AyanaPersonalSearchEngine.Source.HISTORY in requested
        val notificationRequested =
            AyanaPersonalSearchEngine.Source.NOTIFICATIONS in requested
        val filesRequested =
            AyanaPersonalSearchEngine.Source.FILES in requested
        val photosRequested =
            AyanaPersonalSearchEngine.Source.PHOTOS in requested

        val historyAccessible =
            !historyRequested ||
                AyanaPersonalSearchEngine.Source.HISTORY !in report.sourceErrors
        val notificationAccessible =
            !notificationRequested ||
                AyanaPersonalSearchEngine.Source.NOTIFICATIONS !in report.sourceErrors
        val filesAccessible =
            !filesRequested ||
                AyanaPersonalSearchEngine.Source.FILES !in report.sourceErrors
        val photosAccessible =
            !photosRequested ||
                AyanaPersonalSearchEngine.Source.PHOTOS !in report.sourceErrors

        val fileCoverage =
            report.sourceCoverage[
                AyanaPersonalSearchEngine.Source.FILES
            ].orEmpty()

        val photoCoverage =
            report.sourceCoverage[
                AyanaPersonalSearchEngine.Source.PHOTOS
            ].orEmpty()

        val documentContentLane =
            filesRequested &&
                filesAccessible &&
                fileCoverage.contains(
                    "индекс содержимого документов",
                    ignoreCase = true
                )

        val photoVisualLane =
            photosRequested &&
                photosAccessible &&
                (
                    photoCoverage.contains(
                        "ocr/labels",
                        ignoreCase = true
                    ) ||
                        report.imageIndexedPhotos > 0 ||
                        report.imagePendingPhotos > 0
                    )

        val timestampedHits =
            report.hits.count { hit ->
                hit.timestampMs > 0L
            }

        val photoContentHits =
            report.hits.count { hit ->
                hit.source == AyanaPersonalSearchEngine.Source.PHOTOS &&
                    hit.metadata.contains(
                        "image_content_match=true",
                        ignoreCase = true
                    )
            }

        val documentContentHits =
            report.hits.count { hit ->
                hit.source == AyanaPersonalSearchEngine.Source.FILES &&
                    hit.metadata.contains(
                        "content_match=true",
                        ignoreCase = true
                    )
            }

        val requiredRoadmapSourcesAccessible =
            historyAccessible &&
                notificationAccessible &&
                filesAccessible &&
                photosAccessible

        val result =
            JSONObject()
                .put("contract_version", VERSION)
                .put("local_only", true)
                .put("agent_core_turns", 0)
                .put("worker_turns", 0)
                .put("requested_sources", requestedArray)
                .put("requested_source_count", requested.size)
                .put("registered_source_count", AyanaPersonalSearchEngine.Source.values().size)
                .put("all_requested_sources_reported", allRequestedReported)
                .put("required_roadmap_sources_accessible", requiredRoadmapSourcesAccessible)
                .put("history_accessible", historyAccessible)
                .put("notifications_accessible", notificationAccessible)
                .put("files_metadata_lane", filesRequested && filesAccessible)
                .put("document_content_lane", documentContentLane)
                .put("photos_metadata_lane", photosRequested && photosAccessible)
                .put("photo_visual_ocr_label_lane", photoVisualLane)
                .put("photo_timestamp_signal_supported", true)
                .put("face_identity_inference", false)
                .put("person_identity_claimed", false)
                .put("face_matching_enabled", false)
                .put("source_errors", sourceErrors)
                .put("source_match_counts", sourceCounts)
                .put("source_coverage", sourceCoverage)
                .put("total_hits", report.hits.size)
                .put("timestamped_hits", timestampedHits)
                .put("document_content_hits", documentContentHits)
                .put("photo_content_hits", photoContentHits)
                .put("history_scanned", report.scannedHistoryRecords)
                .put("notifications_scanned", report.scannedNotifications)
                .put("files_scanned", report.scannedFiles)
                .put("photos_scanned", report.scannedPhotos)
                .put("document_provider_rows", report.contentProviderRowsScanned)
                .put("document_candidates", report.contentCandidateDocuments)
                .put("document_indexed", report.contentIndexedDocuments)
                .put("document_failed", report.contentFailedDocuments)
                .put("document_unsupported", report.contentUnsupportedDocuments)
                .put("document_pdf_best_effort", report.contentPdfBestEffortDocuments)
                .put("photo_provider_rows", report.imageProviderRowsScanned)
                .put("photo_candidates", report.imageCandidatePhotos)
                .put("photo_indexed", report.imageIndexedPhotos)
                .put("photo_pending", report.imagePendingPhotos)
                .put("photo_ocr_indexed", report.imageOcrPhotos)
                .put("photo_labeled", report.imageLabeledPhotos)
                .put("persistent_user_data_mutation", false)
                .put("search_index_cache_updates_allowed", true)

        result.put(
            "acceptance_ok",
            acceptanceOk(result)
        )

        return result
    }

    fun acceptanceOk(
        evidence: JSONObject
    ): Boolean =
        evidence.optBoolean("local_only", false) &&
            evidence.optInt("agent_core_turns", -1) == 0 &&
            evidence.optInt("worker_turns", -1) == 0 &&
            evidence.optInt("registered_source_count", 0) >= 6 &&
            evidence.optBoolean("all_requested_sources_reported", false) &&
            evidence.optBoolean("required_roadmap_sources_accessible", false) &&
            evidence.optBoolean("files_metadata_lane", false) &&
            evidence.optBoolean("document_content_lane", false) &&
            evidence.optBoolean("photos_metadata_lane", false) &&
            evidence.optBoolean("photo_visual_ocr_label_lane", false) &&
            evidence.optBoolean("photo_timestamp_signal_supported", false) &&
            !evidence.optBoolean("face_identity_inference", true) &&
            !evidence.optBoolean("person_identity_claimed", true) &&
            !evidence.optBoolean("face_matching_enabled", true) &&
            !evidence.optBoolean("persistent_user_data_mutation", true)

    companion object {
        const val VERSION = "1.0"
    }
}

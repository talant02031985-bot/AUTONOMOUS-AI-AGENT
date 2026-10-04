package kg.autonomous.agent

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlin.math.abs

/**
 * AYANA Internet Speed Probe v1.0 — R10.27.5 VERIFIED INTERNET SPEED.
 *
 * Bounded, read-only network measurement:
 * - binds every HTTPS request to the exact active Android Network captured at start;
 * - requires NET_CAPABILITY_INTERNET + NET_CAPABILITY_VALIDATED;
 * - verifies the active Network/transport did not change before returning SUCCESS;
 * - measures latency plus download/upload throughput from actual transferred bytes
 *   and monotonic elapsed time;
 * - uses Cloudflare's public speed-test transfer endpoints only as a byte source/sink;
 * - does not open a browser, scrape UI, mutate external state, or claim ISP-plan speed;
 * - results are bounded point-in-time estimates, not contractual line-rate guarantees.
 */
class AyanaInternetSpeedProbe(
    context: Context
) {

    private val appContext = context.applicationContext

    fun run(
        specificallyMobile: Boolean,
        shouldCancel: () -> Boolean = { false }
    ): JSONObject {
        val startedAt = SystemClock.elapsedRealtime()
        val connectivity =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return failure(
                    status = "ERROR",
                    reason = "connectivity_service_unavailable",
                    startedAt = startedAt
                )

        val network = connectivity.activeNetwork
            ?: return blocked(
                reason = "no_active_network",
                startedAt = startedAt
            )

        val capabilities = connectivity.getNetworkCapabilities(network)
            ?: return failure(
                status = "ERROR",
                reason = "network_capabilities_unavailable",
                startedAt = startedAt
            )

        val transport = transportOf(capabilities)
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        if (!hasInternet || !validated) {
            return blocked(
                reason = if (!hasInternet) "active_network_has_no_internet_capability" else "active_network_not_validated",
                startedAt = startedAt,
                transport = transport
            )
        }

        if (specificallyMobile && transport != "cellular") {
            return blocked(
                reason = when (transport) {
                    "wifi" -> "mobile_speed_test_blocked_by_active_wifi"
                    "vpn" -> "mobile_speed_test_blocked_by_active_vpn"
                    else -> "mobile_speed_test_blocked_unverified_cellular_transport"
                },
                startedAt = startedAt,
                transport = transport
            )
        }

        if (shouldCancel()) {
            return cancelled(startedAt, transport)
        }

        return try {
            // Prime TLS/edge route. The warm-up is deliberately excluded from latency output.
            measureDownloadOnce(
                network = network,
                bytesRequested = LATENCY_WARMUP_BYTES,
                readTimeoutMs = LATENCY_TIMEOUT_MS,
                shouldCancel = shouldCancel
            )

            val latencySamples = mutableListOf<Double>()
            repeat(LATENCY_SAMPLE_COUNT) {
                if (shouldCancel()) throw ProbeCancelledException()
                latencySamples += measureLatencyOnce(network, shouldCancel)
            }

            // A small real transfer selects a bounded payload appropriate for slow/fast links.
            val downloadWarmup = measureDownloadOnce(
                network = network,
                bytesRequested = DOWNLOAD_WARMUP_BYTES,
                readTimeoutMs = THROUGHPUT_TIMEOUT_MS,
                shouldCancel = shouldCancel
            )
            val downloadSampleBytes = chooseDownloadSampleBytes(downloadWarmup.mbps)
            val downloadSamples = mutableListOf<TransferSample>()
            repeat(DOWNLOAD_SAMPLE_COUNT) {
                if (shouldCancel()) throw ProbeCancelledException()
                downloadSamples += measureDownloadOnce(
                    network = network,
                    bytesRequested = downloadSampleBytes,
                    readTimeoutMs = THROUGHPUT_TIMEOUT_MS,
                    shouldCancel = shouldCancel
                )
            }

            val uploadWarmup = measureUploadOnce(
                network = network,
                bytesToSend = UPLOAD_WARMUP_BYTES,
                shouldCancel = shouldCancel
            )
            val uploadSampleBytes = chooseUploadSampleBytes(uploadWarmup.mbps)
            val uploadSamples = mutableListOf<TransferSample>()
            repeat(UPLOAD_SAMPLE_COUNT) {
                if (shouldCancel()) throw ProbeCancelledException()
                uploadSamples += measureUploadOnce(
                    network = network,
                    bytesToSend = uploadSampleBytes,
                    shouldCancel = shouldCancel
                )
            }

            val activeAfter = connectivity.activeNetwork
            val capabilitiesAfter = activeAfter?.let { connectivity.getNetworkCapabilities(it) }
            val transportAfter = capabilitiesAfter?.let(::transportOf) ?: "none"
            val validatedAfter = capabilitiesAfter?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            ) == true

            if (activeAfter != network || transportAfter != transport || !validatedAfter) {
                return failure(
                    status = "ERROR",
                    reason = "network_changed_during_speed_test",
                    startedAt = startedAt,
                    transport = transport,
                    extra = JSONObject()
                        .put("transport_after", transportAfter)
                        .put("validated_after", validatedAfter)
                )
            }

            val download = aggregate(downloadSamples)
            val upload = aggregate(uploadSamples)
            val latencyMedian = median(latencySamples)
            val latencyJitter = meanAbsoluteDelta(latencySamples)

            if (
                download.bytes <= 0L || download.mbps <= 0.0 ||
                upload.bytes <= 0L || upload.mbps <= 0.0 ||
                latencyMedian < 0.0
            ) {
                return failure(
                    status = "ERROR",
                    reason = "invalid_speed_measurement",
                    startedAt = startedAt,
                    transport = transport
                )
            }

            JSONObject()
                .put("success", true)
                .put("verified", true)
                .put("terminal_status", "SUCCESS")
                .put("status", "verified_internet_speed")
                .put("provider", "cloudflare_speed_test")
                .put("endpoint_host", SPEED_HOST)
                .put("transport", transport)
                .put("network_validated", true)
                .put("network_unchanged", true)
                .put("specifically_mobile", specificallyMobile)
                .put("latency_ms", round2(latencyMedian))
                .put("jitter_ms", round2(latencyJitter))
                .put("download_mbps", round2(download.mbps))
                .put("upload_mbps", round2(upload.mbps))
                .put("download_bytes", download.bytes)
                .put("upload_bytes", upload.bytes)
                .put("download_elapsed_ms", download.elapsedMs)
                .put("upload_elapsed_ms", upload.elapsedMs)
                .put("download_sample_bytes", downloadSampleBytes)
                .put("upload_sample_bytes", uploadSampleBytes)
                .put("latency_samples_ms", JSONArray(latencySamples.map(::round2)))
                .put("duration_ms", (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L))
                .put("measurement_method", "active_network_bound_https_actual_bytes_over_monotonic_time")
                .put("approximate_point_in_time", true)
        } catch (_: ProbeCancelledException) {
            cancelled(startedAt, transport)
        } catch (error: Exception) {
            failure(
                status = "ERROR",
                reason = "internet_speed_probe_failed:${error.javaClass.simpleName}",
                startedAt = startedAt,
                transport = transport,
                extra = JSONObject().put("error", (error.message ?: "").take(240))
            )
        }
    }

    private data class TransferSample(
        val bytes: Long,
        val elapsedMs: Long,
        val mbps: Double
    )

    private fun measureLatencyOnce(
        network: Network,
        shouldCancel: () -> Boolean
    ): Double {
        if (shouldCancel()) throw ProbeCancelledException()
        val url = URL("$DOWNLOAD_ENDPOINT?bytes=$LATENCY_BYTES&seed=${System.nanoTime()}")
        val connection = openHttps(network, url, LATENCY_TIMEOUT_MS)
        try {
            val started = SystemClock.elapsedRealtime()
            val code = connection.responseCode
            val input = connection.inputStream
            val buffer = ByteArray(256)
            while (input.read(buffer) > 0) {
                if (shouldCancel()) throw ProbeCancelledException()
            }
            input.close()
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)
            require(code in 200..299) { "latency_http_$code" }
            return elapsed.toDouble()
        } finally {
            connection.disconnect()
        }
    }

    private fun measureDownloadOnce(
        network: Network,
        bytesRequested: Int,
        readTimeoutMs: Int,
        shouldCancel: () -> Boolean
    ): TransferSample {
        require(bytesRequested > 0)
        if (shouldCancel()) throw ProbeCancelledException()

        val url = URL("$DOWNLOAD_ENDPOINT?bytes=$bytesRequested&seed=${System.nanoTime()}")
        val connection = openHttps(network, url, readTimeoutMs)
        try {
            val code = connection.responseCode
            require(code in 200..299) { "download_http_$code" }

            val buffer = ByteArray(IO_BUFFER_BYTES)
            var total = 0L
            val input = connection.inputStream
            val started = SystemClock.elapsedRealtime()
            while (true) {
                if (shouldCancel()) throw ProbeCancelledException()
                val read = input.read(buffer)
                if (read <= 0) break
                total += read.toLong()
                if (total > bytesRequested.toLong() + DOWNLOAD_BYTE_TOLERANCE) {
                    throw IllegalStateException("download_payload_exceeded_bound")
                }
            }
            input.close()
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)

            require(total >= bytesRequested.toLong()) {
                "download_truncated:$total/$bytesRequested"
            }

            return TransferSample(
                bytes = total,
                elapsedMs = elapsed,
                mbps = mbps(total, elapsed)
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun measureUploadOnce(
        network: Network,
        bytesToSend: Int,
        shouldCancel: () -> Boolean
    ): TransferSample {
        require(bytesToSend > 0)
        if (shouldCancel()) throw ProbeCancelledException()

        val url = URL("$UPLOAD_ENDPOINT?seed=${System.nanoTime()}")
        val connection = openHttps(network, url, THROUGHPUT_TIMEOUT_MS).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
            setFixedLengthStreamingMode(bytesToSend)
        }

        try {
            connection.connect()
            val chunk = ByteArray(IO_BUFFER_BYTES) { index -> (index and 0x7f).toByte() }
            var remaining = bytesToSend
            val started = SystemClock.elapsedRealtime()
            connection.outputStream.use { output ->
                while (remaining > 0) {
                    if (shouldCancel()) throw ProbeCancelledException()
                    val count = minOf(remaining, chunk.size)
                    output.write(chunk, 0, count)
                    remaining -= count
                }
                output.flush()
            }
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L)

            val code = connection.responseCode
            require(code in 200..299) { "upload_http_$code" }
            try {
                connection.inputStream.use { input ->
                    val buffer = ByteArray(512)
                    while (input.read(buffer) > 0) {
                        if (shouldCancel()) throw ProbeCancelledException()
                    }
                }
            } catch (_: Exception) {
                // Successful HTTP status is sufficient; response body is not part of throughput proof.
            }

            return TransferSample(
                bytes = bytesToSend.toLong(),
                elapsedMs = elapsed,
                mbps = mbps(bytesToSend.toLong(), elapsed)
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun openHttps(
        network: Network,
        url: URL,
        readTimeoutMs: Int
    ): HttpsURLConnection {
        val raw = network.openConnection(url)
        val connection = raw as? HttpsURLConnection
            ?: throw IllegalStateException("speed_endpoint_not_https")
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = readTimeoutMs
        connection.useCaches = false
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("Cache-Control", "no-store, no-cache")
        connection.setRequestProperty("Pragma", "no-cache")
        connection.setRequestProperty("User-Agent", "AYANA-AI/R10.27.5")
        return connection
    }

    private fun chooseDownloadSampleBytes(warmupMbps: Double): Int =
        when {
            warmupMbps < 1.5 -> 128 * 1024
            warmupMbps < 5.0 -> 384 * 1024
            warmupMbps < 20.0 -> 1024 * 1024
            warmupMbps < 80.0 -> 3 * 1024 * 1024
            else -> 5 * 1024 * 1024
        }

    private fun chooseUploadSampleBytes(warmupMbps: Double): Int =
        when {
            warmupMbps < 1.0 -> 64 * 1024
            warmupMbps < 4.0 -> 128 * 1024
            warmupMbps < 15.0 -> 384 * 1024
            warmupMbps < 60.0 -> 1024 * 1024
            else -> 2 * 1024 * 1024
        }

    private fun aggregate(samples: List<TransferSample>): TransferSample {
        require(samples.isNotEmpty())
        val bytes = samples.sumOf { it.bytes }
        val elapsed = samples.sumOf { it.elapsedMs }.coerceAtLeast(1L)
        return TransferSample(bytes, elapsed, mbps(bytes, elapsed))
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return -1.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        }
    }

    private fun meanAbsoluteDelta(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        var total = 0.0
        for (index in 1 until values.size) {
            total += abs(values[index] - values[index - 1])
        }
        return total / (values.size - 1).toDouble()
    }

    private fun mbps(bytes: Long, elapsedMs: Long): Double =
        (bytes.toDouble() * 8.0) / (elapsedMs.coerceAtLeast(1L).toDouble() * 1000.0)

    private fun round2(value: Double): Double =
        String.format(Locale.US, "%.2f", value).toDouble()

    private fun transportOf(capabilities: NetworkCapabilities): String =
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }

    private fun blocked(
        reason: String,
        startedAt: Long,
        transport: String = "none"
    ): JSONObject =
        JSONObject()
            .put("success", false)
            .put("verified", false)
            .put("terminal_status", "BLOCKED")
            .put("status", "internet_speed_blocked")
            .put("reason", reason)
            .put("transport", transport)
            .put("duration_ms", (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L))

    private fun cancelled(startedAt: Long, transport: String): JSONObject =
        JSONObject()
            .put("success", false)
            .put("verified", false)
            .put("terminal_status", "CANCELLED")
            .put("status", "internet_speed_cancelled")
            .put("reason", "cancelled")
            .put("transport", transport)
            .put("duration_ms", (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L))

    private fun failure(
        status: String,
        reason: String,
        startedAt: Long,
        transport: String = "unknown",
        extra: JSONObject? = null
    ): JSONObject {
        val result = JSONObject()
            .put("success", false)
            .put("verified", false)
            .put("terminal_status", status)
            .put("status", "internet_speed_failed")
            .put("reason", reason)
            .put("transport", transport)
            .put("duration_ms", (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L))
        if (extra != null) {
            val keys = extra.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result.put(key, extra.opt(key))
            }
        }
        return result
    }

    private class ProbeCancelledException : RuntimeException()

    companion object {
        const val VERSION = "1.0"
        private const val SPEED_HOST = "speed.cloudflare.com"
        private const val DOWNLOAD_ENDPOINT = "https://speed.cloudflare.com/__down"
        private const val UPLOAD_ENDPOINT = "https://speed.cloudflare.com/__up"

        private const val CONNECT_TIMEOUT_MS = 7_000
        private const val LATENCY_TIMEOUT_MS = 7_000
        private const val THROUGHPUT_TIMEOUT_MS = 15_000
        private const val IO_BUFFER_BYTES = 64 * 1024
        private const val DOWNLOAD_BYTE_TOLERANCE = 16L * 1024L

        private const val LATENCY_WARMUP_BYTES = 16
        private const val LATENCY_BYTES = 32
        private const val LATENCY_SAMPLE_COUNT = 3

        private const val DOWNLOAD_WARMUP_BYTES = 128 * 1024
        private const val DOWNLOAD_SAMPLE_COUNT = 2

        private const val UPLOAD_WARMUP_BYTES = 64 * 1024
        private const val UPLOAD_SAMPLE_COUNT = 2
    }
}

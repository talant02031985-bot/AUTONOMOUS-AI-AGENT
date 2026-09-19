package kg.autonomous.agent

/**
 * AYANA Background Image Indexer v1.0 — R8.3D.
 *
 * Purpose:
 * - progressively finish AyanaImageContentIndexEngine without requiring the user
 *   to repeat photo-search commands;
 * - preserve foreground responsiveness by indexing only small batches;
 * - stop between images as soon as canRun() becomes false;
 * - never write Command History by itself;
 * - never call Worker / Agent Core.
 *
 * Persistence lives in AyanaImageContentIndexEngine's app-private JSON index, so
 * progress survives service / app restarts. This class only owns scheduling state.
 */
class AyanaBackgroundImageIndexer(
    private val engine: AyanaImageContentIndexEngine,
    private val canRun: () -> Boolean
) {

    data class RuntimeStatus(
        val running: Boolean,
        val state: String,
        val indexedImages: Int,
        val candidateImages: Int,
        val pendingImages: Int,
        val failedImages: Int,
        val ocrIndexedImages: Int,
        val labeledImages: Int,
        val lastBatchUpdatedImages: Int,
        val lastBatchReusedImages: Int,
        val lastBatchAtMs: Long,
        val lastError: String
    )

    private val lifecycleLock = Any()
    private val wakeMonitor = java.lang.Object()

    @Volatile
    private var stopRequested = false

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var state = STATE_STOPPED

    @Volatile
    private var indexedImages = 0

    @Volatile
    private var candidateImages = 0

    @Volatile
    private var pendingImages = 0

    @Volatile
    private var failedImages = 0

    @Volatile
    private var ocrIndexedImages = 0

    @Volatile
    private var labeledImages = 0

    @Volatile
    private var lastBatchUpdatedImages = 0

    @Volatile
    private var lastBatchReusedImages = 0

    @Volatile
    private var lastBatchAtMs = 0L

    @Volatile
    private var lastError = ""

    fun start() {
        synchronized(lifecycleLock) {
            val existing = worker
            if (existing != null && existing.isAlive) {
                return
            }

            stopRequested = false
            state = STATE_WAITING

            val created =
                kotlin.concurrent.thread(
                    start = false,
                    isDaemon = true,
                    name = "AyanaBackgroundImageIndexer"
                ) {
                    runLoop()
                }

            worker = created
            created.start()
        }
    }

    fun stop() {
        stopRequested = true
        state = STATE_STOPPING
        synchronized(wakeMonitor) {
            wakeMonitor.notifyAll()
        }
    }

    /** Wake the scheduler early after a relevant foreground event if desired. */
    fun requestSoon() {
        synchronized(wakeMonitor) {
            wakeMonitor.notifyAll()
        }
    }

    fun runtimeStatus(): RuntimeStatus =
        RuntimeStatus(
            running = worker?.isAlive == true && !stopRequested,
            state = state,
            indexedImages = indexedImages,
            candidateImages = candidateImages,
            pendingImages = pendingImages,
            failedImages = failedImages,
            ocrIndexedImages = ocrIndexedImages,
            labeledImages = labeledImages,
            lastBatchUpdatedImages = lastBatchUpdatedImages,
            lastBatchReusedImages = lastBatchReusedImages,
            lastBatchAtMs = lastBatchAtMs,
            lastError = lastError
        )

    private fun runLoop() {
        try {
            waitFor(INITIAL_DELAY_MS)

            while (!stopRequested) {
                if (!canRunSafely()) {
                    state = STATE_PAUSED
                    waitFor(PAUSED_RECHECK_MS)
                    continue
                }

                state = STATE_INDEXING

                val result =
                    try {
                        engine.indexBackgroundBatch(
                            maxNewImages =
                                AyanaImageContentIndexEngine.BACKGROUND_NEW_IMAGE_BUDGET,
                            shouldContinue = {
                                !stopRequested && canRunSafely()
                            }
                        )
                    } catch (error: Exception) {
                        lastError =
                            (error.message ?: error.javaClass.simpleName)
                                .take(240)
                        state = STATE_ERROR
                        waitFor(ERROR_BACKOFF_MS)
                        continue
                    }

                indexedImages = result.indexedImages
                candidateImages = result.candidateImages
                pendingImages = result.pendingImages
                failedImages = result.failedImages
                ocrIndexedImages = result.ocrIndexedImages
                labeledImages = result.labeledImages
                lastBatchUpdatedImages = result.updatedImages
                lastBatchReusedImages = result.reusedImages
                lastBatchAtMs = System.currentTimeMillis()
                lastError = ""

                if (stopRequested) {
                    break
                }

                if (result.pendingImages <= 0) {
                    state = STATE_COMPLETE
                    // Poll occasionally so photos added after completion are discovered.
                    waitFor(COMPLETE_RECHECK_MS)
                } else {
                    state = STATE_WAITING
                    waitFor(BETWEEN_BATCHES_MS)
                }
            }
        } finally {
            state = STATE_STOPPED
            synchronized(lifecycleLock) {
                if (Thread.currentThread() === worker) {
                    worker = null
                }
            }
        }
    }

    private fun canRunSafely(): Boolean =
        try {
            canRun()
        } catch (_: Exception) {
            false
        }

    private fun waitFor(delayMs: Long) {
        if (delayMs <= 0L || stopRequested) {
            return
        }

        synchronized(wakeMonitor) {
            if (!stopRequested) {
                try {
                    wakeMonitor.wait(delayMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    companion object {
        const val STATE_STOPPED = "stopped"
        const val STATE_STOPPING = "stopping"
        const val STATE_WAITING = "waiting"
        const val STATE_PAUSED = "paused"
        const val STATE_INDEXING = "indexing"
        const val STATE_COMPLETE = "complete"
        const val STATE_ERROR = "error"

        // Give startup / model initialization priority before local image ML work.
        private const val INITIAL_DELAY_MS = 15_000L

        // Four images per batch are handled by the engine; this gap keeps the
        // continuous wake-word path responsive and avoids sustained CPU pressure.
        private const val BETWEEN_BATCHES_MS = 12_000L
        private const val PAUSED_RECHECK_MS = 3_000L
        private const val ERROR_BACKOFF_MS = 60_000L
        private const val COMPLETE_RECHECK_MS = 5L * 60L * 1000L
    }
}

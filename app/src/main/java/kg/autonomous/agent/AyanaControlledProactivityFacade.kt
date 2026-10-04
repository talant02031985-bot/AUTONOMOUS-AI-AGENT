package kg.autonomous.agent

import android.content.Context

/**
 * Single integration surface for AyanaVoiceService.
 *
 * VoiceService only needs to own one facade, call start()/stop(), and offer
 * commands to tryHandle(). All rule persistence/event evaluation stays outside
 * the already-large service.
 */
class AyanaControlledProactivityFacade(
    context: Context,
    private val historySink: (state: String, details: String) -> Unit = { _, _ -> }
) {
    private val runtime =
        AyanaControlledProactivityRuntime(
            context = context,
            eventSink = { event ->
                historySink(
                    event.state,
                    buildString {
                        if (event.ruleId.isNotBlank()) {
                            append("rule_id=")
                            append(event.ruleId)
                            append("; ")
                        }
                        append(event.details)
                    }.trim().take(1800)
                )
            }
        )

    private val router = AyanaProactivityCommandRouter(runtime)

    fun start(): Boolean = runtime.start()

    fun stop() = runtime.stop()

    fun isCandidate(command: String): Boolean = router.isCandidate(command)

    fun tryHandle(command: String): AyanaProactivityCommandRouter.Result =
        router.handle(command)

    fun compactStatus(): String = runtime.compactStatus()

    fun selfTest(): Boolean =
        runtime.selfTest() &&
            router.isCandidate("предупреди когда пропадет интернет") &&
            router.isCandidate("сообщи когда заряд станет ниже 20 процентов") &&
            !router.isCandidate("открой YouTube")

    companion object {
        const val VERSION = "1.0"
    }
}

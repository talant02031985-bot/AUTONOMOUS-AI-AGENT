package kg.autonomous.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * AYANA Boot Receiver v2.0.
 *
 * Android 14+ does not allow a BOOT_COMPLETED receiver to launch AYANA's
 * microphone foreground service. Therefore boot recovery is intentionally
 * passive: reminders are rescheduled and an interrupted durable goal is marked
 * RECOVERY_PENDING. The goal is resumed only after a user-initiated AYANA start.
 */
class AyanaBootReceiver :
    BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {

        val action =
            intent.action
                ?: return

        if (
            action !=
            Intent.ACTION_BOOT_COMPLETED &&
            action !=
            Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val pendingResult =
            goAsync()

        Thread {

            try {

                val appContext =
                    context.applicationContext

                val reason =
                    if (
                        action ==
                        Intent.ACTION_BOOT_COMPLETED
                    ) {
                        "device_boot"
                    } else {
                        "package_replaced"
                    }

                // Never start AyanaVoiceService from BOOT_COMPLETED here.
                // Preserve the goal and wait for a user-initiated app/service
                // start, which is both safer and compatible with modern Android.
                try {
                    AyanaDurableGoalStore(
                        appContext
                    ).markInterruptedGoals(
                        reason
                    )
                } catch (_: Exception) {
                }

                try {
                    val store =
                        AyanaTaskStore(
                            appContext
                        )

                    val scheduler =
                        AyanaTaskScheduler(
                            appContext
                        )

                    scheduler
                        .rescheduleAll(
                            store
                        )
                } catch (_: Exception) {
                }

            } finally {

                pendingResult
                    .finish()
            }
        }.start()
    }
}

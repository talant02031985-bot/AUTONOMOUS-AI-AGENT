package kg.autonomous.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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

            } finally {

                pendingResult
                    .finish()
            }

        }.start()
    }
}

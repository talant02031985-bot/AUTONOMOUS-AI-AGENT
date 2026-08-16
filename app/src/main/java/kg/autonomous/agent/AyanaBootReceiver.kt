package kg.autonomous.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

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

                val preferences =
                    AyanaPreferences(
                        appContext
                    )

                if (
                    preferences
                        .bootActivationPromptEnabled
                ) {

                    showActivationNotification(
                        appContext
                    )
                }

            } finally {

                pendingResult
                    .finish()
            }

        }.start()
    }

    private fun showActivationNotification(
        context: Context
    ) {

        createChannel(
            context
        )

        val openIntent =
            Intent(
                context,
                MainActivity::class.java
            ).apply {

                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }

        val pendingIntent =
            PendingIntent.getActivity(
                context,
                7711,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val builder =
            if (
                Build.VERSION.SDK_INT >= 26
            ) {

                Notification.Builder(
                    context,
                    CHANNEL_ID
                )

            } else {

                @Suppress("DEPRECATION")
                Notification.Builder(
                    context
                )
            }

        val notification =
            builder
                .setSmallIcon(
                    android.R.drawable
                        .ic_btn_speak_now
                )
                .setContentTitle(
                    "AYANA готова"
                )
                .setContentText(
                    "Нажмите один раз, чтобы снова активировать голосовой режим после перезагрузки."
                )
                .setAutoCancel(
                    true
                )
                .setContentIntent(
                    pendingIntent
                )
                .setCategory(
                    Notification
                        .CATEGORY_SERVICE
                )
                .build()

        context
            .getSystemService(
                NotificationManager::class.java
            )
            .notify(
                NOTIFICATION_ID,
                notification
            )
    }

    private fun createChannel(
        context: Context
    ) {

        if (
            Build.VERSION.SDK_INT < 26
        ) {
            return
        }

        val manager =
            context.getSystemService(
                NotificationManager::class.java
            )

        if (
            manager
                .getNotificationChannel(
                    CHANNEL_ID
                ) != null
        ) {
            return
        }

        manager
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Запуск AYANA",
                    NotificationManager
                        .IMPORTANCE_DEFAULT
                ).apply {

                    description =
                        "Активация голосового режима AYANA после перезагрузки"
                }
            )
    }

    companion object {

        private const val CHANNEL_ID =
            "ayana_boot_activation"

        private const val NOTIFICATION_ID =
            7711
    }
}

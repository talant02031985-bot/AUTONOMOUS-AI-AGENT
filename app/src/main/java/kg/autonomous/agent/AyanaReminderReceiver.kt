package kg.autonomous.agent

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

class AyanaReminderReceiver :
    BroadcastReceiver() {

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {

        val taskId =
            intent.getStringExtra(
                EXTRA_TASK_ID
            )
                ?.trim()
                .orEmpty()

        if (taskId.isBlank()) {
            return
        }

        val store =
            AyanaTaskStore(
                context.applicationContext
            )

        val task =
            store.getTask(
                taskId
            )
                ?: return

        if (!task.enabled) {
            return
        }

        showReminderNotification(
            context,
            task
        )

        scheduleNextIfRecurring(
            context,
            store,
            task
        )
    }

    private fun showReminderNotification(
        context: Context,
        task: AyanaTaskStore.TaskItem
    ) {

        createNotificationChannel(
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

        val openPendingIntent =
            PendingIntent.getActivity(
                context,
                task.id.hashCode(),
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
                    android.R.drawable.ic_popup_reminder
                )
                .setContentTitle(
                    task.title
                )
                .setContentText(
                    task.message
                )
                .setStyle(
                    Notification.BigTextStyle()
                        .bigText(
                            task.message
                        )
                )
                .setAutoCancel(
                    true
                )
                .setContentIntent(
                    openPendingIntent
                )
                .setCategory(
                    Notification.CATEGORY_REMINDER
                )
                .setOnlyAlertOnce(
                    false
                )
                .build()

        val manager =
            context.getSystemService(
                NotificationManager::class.java
            )

        manager.notify(
            notificationId(
                task.id
            ),
            notification
        )
    }

    private fun createNotificationChannel(
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
            manager.getNotificationChannel(
                CHANNEL_ID
            ) != null
        ) {
            return
        }

        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Напоминания AYANA",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {

                description =
                    "Напоминания и задачи AYANA AI"

                enableVibration(
                    true
                )
            }

        manager.createNotificationChannel(
            channel
        )
    }

    private fun scheduleNextIfRecurring(
        context: Context,
        store: AyanaTaskStore,
        task: AyanaTaskStore.TaskItem
    ) {

        val nextTrigger =
            calculateNextTrigger(
                task.triggerAtMillis,
                task.recurrence
            )

        if (nextTrigger == null) {

            store.updateTask(
                id =
                    task.id,
                enabled =
                    false
            )

            return
        }

        val updated =
            store.updateTask(
                id =
                    task.id,
                triggerAtMillis =
                    nextTrigger,
                enabled =
                    true
            )
                ?: return

        scheduleAlarm(
            context,
            updated
        )
    }

    private fun calculateNextTrigger(
        previousTrigger: Long,
        recurrence: String
    ): Long? {

        val calendar =
            Calendar.getInstance().apply {
                timeInMillis =
                    previousTrigger
            }

        when (recurrence) {

            AyanaTaskStore
                .RECURRENCE_DAILY -> {

                calendar.add(
                    Calendar.DAY_OF_YEAR,
                    1
                )
            }

            AyanaTaskStore
                .RECURRENCE_WEEKLY -> {

                calendar.add(
                    Calendar.WEEK_OF_YEAR,
                    1
                )
            }

            AyanaTaskStore
                .RECURRENCE_MONTHLY -> {

                calendar.add(
                    Calendar.MONTH,
                    1
                )
            }

            else ->
                return null
        }

        val now =
            System.currentTimeMillis()

        while (
            calendar.timeInMillis <= now
        ) {

            when (recurrence) {

                AyanaTaskStore
                    .RECURRENCE_DAILY -> {

                    calendar.add(
                        Calendar.DAY_OF_YEAR,
                        1
                    )
                }

                AyanaTaskStore
                    .RECURRENCE_WEEKLY -> {

                    calendar.add(
                        Calendar.WEEK_OF_YEAR,
                        1
                    )
                }

                AyanaTaskStore
                    .RECURRENCE_MONTHLY -> {

                    calendar.add(
                        Calendar.MONTH,
                        1
                    )
                }

                else ->
                    return null
            }
        }

        return calendar
            .timeInMillis
    }

    private fun scheduleAlarm(
        context: Context,
        task: AyanaTaskStore.TaskItem
    ) {

        val alarmManager =
            context.getSystemService(
                AlarmManager::class.java
            )

        val pendingIntent =
            createReminderPendingIntent(
                context,
                task
            )

        val triggerAt =
            task.triggerAtMillis

        if (
            Build.VERSION.SDK_INT >= 31 &&
            alarmManager
                .canScheduleExactAlarms()
        ) {

            try {

                alarmManager
                    .setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )

                return

            } catch (_: SecurityException) {
            }
        }

        if (
            Build.VERSION.SDK_INT < 31
        ) {

            try {

                alarmManager
                    .setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )

                return

            } catch (_: SecurityException) {
            }
        }

        alarmManager
            .setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                pendingIntent
            )
    }

    private fun createReminderPendingIntent(
        context: Context,
        task: AyanaTaskStore.TaskItem
    ): PendingIntent {

        val intent =
            Intent(
                context,
                AyanaReminderReceiver::class.java
            ).apply {

                putExtra(
                    EXTRA_TASK_ID,
                    task.id
                )
            }

        return PendingIntent.getBroadcast(
            context,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notificationId(
        taskId: String
    ): Int {

        return (
            taskId.hashCode() and
                0x7fffffff
            )
            .coerceAtLeast(
                1000
            )
    }

    companion object {

        const val EXTRA_TASK_ID =
            "ayana_task_id"

        private const val CHANNEL_ID =
            "ayana_reminders"
    }
}

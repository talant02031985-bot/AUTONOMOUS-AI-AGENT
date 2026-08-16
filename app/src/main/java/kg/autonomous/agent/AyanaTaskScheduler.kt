package kg.autonomous.agent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

class AyanaTaskScheduler(
    context: Context
) {

    data class ScheduleResult(
        val success: Boolean,
        val exact: Boolean,
        val message: String
    )

    private val appContext =
        context.applicationContext

    private val alarmManager =
        appContext.getSystemService(
            AlarmManager::class.java
        )

    fun schedule(
        task: AyanaTaskStore.TaskItem
    ): ScheduleResult {

        if (!task.enabled) {

            return ScheduleResult(
                success = false,
                exact = false,
                message =
                    "Задача отключена."
            )
        }

        if (
            task.triggerAtMillis <=
            System.currentTimeMillis()
        ) {

            return ScheduleResult(
                success = false,
                exact = false,
                message =
                    "Время напоминания уже прошло."
            )
        }

        val pendingIntent =
            reminderPendingIntent(
                task.id
            )

        return try {

            if (
                canScheduleExact()
            ) {

                alarmManager
                    .setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        task.triggerAtMillis,
                        pendingIntent
                    )

                ScheduleResult(
                    success = true,
                    exact = true,
                    message =
                        "Точное напоминание установлено."
                )

            } else {

                alarmManager
                    .setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        task.triggerAtMillis,
                        pendingIntent
                    )

                ScheduleResult(
                    success = true,
                    exact = false,
                    message =
                        "Напоминание установлено. Для точного времени нужно разрешить точные будильники AYANA."
                )
            }

        } catch (
            securityException:
            SecurityException
        ) {

            try {

                alarmManager
                    .setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        task.triggerAtMillis,
                        pendingIntent
                    )

                ScheduleResult(
                    success = true,
                    exact = false,
                    message =
                        "Напоминание установлено приблизительно. Android не разрешил точный будильник."
                )

            } catch (
                exception:
                Exception
            ) {

                ScheduleResult(
                    success = false,
                    exact = false,
                    message =
                        exception.message
                            ?: "Не удалось установить напоминание."
                )
            }

        } catch (
            exception:
            Exception
        ) {

            ScheduleResult(
                success = false,
                exact = false,
                message =
                    exception.message
                        ?: "Не удалось установить напоминание."
            )
        }
    }

    fun cancel(
        task: AyanaTaskStore.TaskItem
    ): Boolean {

        return cancelById(
            task.id
        )
    }

    fun cancelById(
        taskId: String
    ): Boolean {

        if (taskId.isBlank()) {
            return false
        }

        return try {

            alarmManager.cancel(
                reminderPendingIntent(
                    taskId
                )
            )

            true

        } catch (_: Exception) {

            false
        }
    }

    fun rescheduleAll(
        store: AyanaTaskStore
    ): Int {

        var scheduledCount =
            0

        val now =
            System.currentTimeMillis()

        val tasks =
            store
                .getAll(
                    includeDisabled = false
                )
                .filter {
                    it.triggerAtMillis >
                        now
                }

        for (task in tasks) {

            val result =
                schedule(
                    task
                )

            if (result.success) {
                scheduledCount++
            }
        }

        return scheduledCount
    }

    fun canScheduleExact():
        Boolean {

        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {

            alarmManager
                .canScheduleExactAlarms()

        } else {

            true
        }
    }

    fun createExactAlarmPermissionIntent():
        Intent? {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.S
        ) {
            return null
        }

        if (
            canScheduleExact()
        ) {
            return null
        }

        val packageUri =
            Uri.parse(
                "package:${appContext.packageName}"
            )

        return Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            packageUri
        ).apply {

            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
            )
        }
    }

    fun openExactAlarmPermissionScreen():
        Boolean {

        val intent =
            createExactAlarmPermissionIntent()
                ?: return true

        return try {

            appContext
                .startActivity(
                    intent
                )

            true

        } catch (_: Exception) {

            try {

                val fallback =
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse(
                            "package:${appContext.packageName}"
                        )
                    ).apply {

                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    }

                appContext
                    .startActivity(
                        fallback
                    )

                true

            } catch (_: Exception) {

                false
            }
        }
    }

    private fun reminderPendingIntent(
        taskId: String
    ): PendingIntent {

        val intent =
            Intent(
                appContext,
                AyanaReminderReceiver::class.java
            ).apply {

                putExtra(
                    AyanaReminderReceiver.EXTRA_TASK_ID,
                    taskId
                )
            }

        return PendingIntent
            .getBroadcast(
                appContext,
                taskId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )
    }
}

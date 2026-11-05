package app.lynx.prm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.lynx.prm.store.Reminder
import java.util.concurrent.TimeUnit

/**
 * Keeps WorkManager in step with the open reminders. What was scheduled is saved on disk,
 * because scheduled work outlives the process: a reminder done on another device has to
 * be cancelled here even after a restart.
 */
class ReminderScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val scheduled = appContext.getSharedPreferences("lynx_reminders", Context.MODE_PRIVATE)

    fun sync(reminders: List<Reminder>, contactName: (String) -> String) {
        val manager = WorkManager.getInstance(appContext)
        val changes = scheduled.edit()
        for (id in scheduled.all.keys - reminders.map { it.id }.toSet()) {
            manager.cancelUniqueWork(workName(id))
            changes.remove(id)
        }
        for (reminder in reminders) {
            val title = contactName(reminder.contactId)
            val signature = "${reminder.dueAt}:${reminder.contactId}:${reminder.body}:$title"
            if (scheduled.getString(reminder.id, null) == signature) continue
            val delay = (reminder.dueAt - System.currentTimeMillis()).coerceAtLeast(0L)
            val data = Data.Builder()
                .putString(ReminderWorker.TITLE, title)
                .putString(ReminderWorker.BODY, reminder.body)
                .putString(ReminderWorker.CONTACT, reminder.contactId)
                .putString(ReminderWorker.REMINDER, reminder.id)
                .build()
            val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .build()
            manager.enqueueUniqueWork(workName(reminder.id), ExistingWorkPolicy.REPLACE, request)
            changes.putString(reminder.id, signature)
        }
        changes.apply()
    }

    private companion object {
        fun workName(id: String) = "lynx-reminder-$id"
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val title = inputData.getString(TITLE) ?: return Result.success()
        val body = inputData.getString(BODY) ?: return Result.success()
        val contactId = inputData.getString(CONTACT) ?: return Result.success()
        val reminderId = inputData.getString(REMINDER) ?: return Result.success()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }
        val channelId = "lynx_reminders"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Reminders", NotificationManager.IMPORTANCE_DEFAULT)
            val system = applicationContext.getSystemService(NotificationManager::class.java)
            system.createNotificationChannel(channel)
        }
        val open = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_CONTACT, contactId)
        }
        val pending = PendingIntent.getActivity(
            applicationContext,
            reminderId.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_lynx)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(reminderId.hashCode(), notification)
        return Result.success()
    }

    companion object {
        const val TITLE = "title"
        const val BODY = "body"
        const val CONTACT = "contactId"
        const val REMINDER = "reminderId"
    }
}

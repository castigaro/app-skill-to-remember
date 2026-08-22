package de.skilltoremember.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import de.skilltoremember.app.api.DeviceActions
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/**
 * Empfängt von der Uhr weitergereichte Aktionen (Kalender-Termin,
 * E-Mail-Entwurf) und zeigt sie als antippbare Benachrichtigung — Play
 * Services weckt diesen Dienst auch, wenn die App gerade nicht läuft.
 * Direkt eine Activity zu starten wäre aus dem Hintergrund ohnehin
 * verboten; die Benachrichtigung ist der saubere Weg und wartet geduldig,
 * bis der Nutzer das Handy in die Hand nimmt.
 */
class WearActionListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != DeviceActions.ACTION_PATH) return
        val payload = runCatching { JSONObject(String(event.data, Charsets.UTF_8)) }.getOrNull() ?: return
        val intent = DeviceActions.intentFor(payload) ?: return
        showNotification(payload, intent)
    }

    private fun showNotification(payload: JSONObject, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return // Nutzer hat Benachrichtigungen abgelehnt — dann bleibt nur der Gedächtnis-Weg.
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.watch_action_channel), NotificationManager.IMPORTANCE_HIGH),
        )

        val isCalendar = payload.optString("type") == "calendar"
        val title = getString(if (isCalendar) R.string.watch_action_calendar_title else R.string.watch_action_email_title)
        val text = if (isCalendar) {
            val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(Date(payload.optLong("startMillis")))
            "${payload.optString("title")} · $time"
        } else {
            payload.optString("subject")
        }

        val pending = PendingIntent.getActivity(
            this,
            (System.currentTimeMillis() and 0xFFFFFF).toInt(), // eindeutiger requestCode je Benachrichtigung
            intent,
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(this)
            .notify((System.currentTimeMillis() and 0x7FFFFF).toInt(), notification)
    }

    companion object {
        private const val CHANNEL_ID = "wear_actions"
    }
}

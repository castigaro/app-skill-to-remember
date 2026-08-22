package de.skilltoremember.app.api

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Übergaben an die System-Apps (Kalender, E-Mail): Die KI füllt nur vor, der
 * Nutzer prüft und bestätigt in der jeweiligen App. Braucht keinerlei
 * Berechtigung, und gespeichert/gesendet wird nie automatisch — die
 * Bestätigung durch den Nutzer ist bewusst Teil des Ablaufs.
 *
 * Am Handy öffnet sich die Ziel-App direkt. Auf der Uhr gibt es keine
 * ausfüllbaren Kalender-/Mail-Dialoge — dort wird der Wunsch stattdessen per
 * Data-Layer-Nachricht ans Handy geschickt und erscheint da als antippbare
 * Benachrichtigung (siehe WearActionListenerService in der Handy-App).
 */
object DeviceActions {

    /** Nachrichten-Pfad Uhr -> Handy für weitergereichte Aktionen. */
    const val ACTION_PATH = "/skilltoremember/action"

    private const val RELAY_TIMEOUT_SECONDS = 5L

    fun available(context: Context): Boolean = true // Handy: direkt · Uhr: Weiterleitung

    private fun isWatch(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)

    // ---- Intents (gemeinsam für direkten Start und Benachrichtigung) ----

    internal fun calendarIntent(title: String, startMillis: Long, endMillis: Long?, location: String?, notes: String?): Intent {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        endMillis?.let { intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
        location?.takeIf { it.isNotBlank() }?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        notes?.takeIf { it.isNotBlank() }?.let { intent.putExtra(CalendarContract.Events.DESCRIPTION, it) }
        return intent
    }

    internal fun emailIntent(to: String?, subject: String, body: String): Intent {
        // mailto-URI statt App-spezifischem Intent: So öffnet die Standard-Mail-App
        // des Nutzers (FairEmail, K-9, Gmail, …). Betreff/Text stehen doppelt drin —
        // als URI-Parameter (mailto-Standard) und als Extras — weil Clients
        // unterschiedlich lesen.
        val recipient = to?.trim().orEmpty()
        val uri = Uri.parse(
            "mailto:" + Uri.encode(recipient) +
                "?subject=" + Uri.encode(subject) +
                "&body=" + Uri.encode(body),
        )
        val intent = Intent(Intent.ACTION_SENDTO, uri)
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (recipient.isNotBlank()) intent.putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
        return intent
    }

    /** Baut aus einer von der Uhr weitergereichten Nachricht den passenden Intent — null bei unbekanntem Typ. */
    fun intentFor(payload: JSONObject): Intent? = when (payload.optString("type")) {
        "calendar" -> calendarIntent(
            title = payload.optString("title"),
            startMillis = payload.optLong("startMillis"),
            endMillis = if (payload.has("endMillis")) payload.optLong("endMillis") else null,
            location = payload.optString("location").ifBlank { null },
            notes = payload.optString("notes").ifBlank { null },
        )
        "email" -> emailIntent(
            to = payload.optString("to").ifBlank { null },
            subject = payload.optString("subject"),
            body = payload.optString("body"),
        )
        else -> null
    }

    // ---- Ausführung ----

    fun openCalendarInsert(
        context: Context,
        title: String,
        startMillis: Long,
        endMillis: Long?,
        location: String?,
        notes: String?,
    ): String {
        if (isWatch(context)) {
            val payload = JSONObject().put("type", "calendar").put("title", title).put("startMillis", startMillis)
            endMillis?.let { payload.put("endMillis", it) }
            location?.takeIf { it.isNotBlank() }?.let { payload.put("location", it) }
            notes?.takeIf { it.isNotBlank() }?.let { payload.put("notes", it) }
            return relayToPhone(context, payload)
        }
        return launch(
            context, calendarIntent(title, startMillis, endMillis, location, notes),
            "Kalender-App mit dem vorausgefüllten Termin geöffnet — der Nutzer prüft und speichert selbst.",
        )
    }

    fun openEmailDraft(context: Context, to: String?, subject: String, body: String): String {
        if (isWatch(context)) {
            val payload = JSONObject().put("type", "email").put("subject", subject).put("body", body)
            to?.takeIf { it.isNotBlank() }?.let { payload.put("to", it.trim()) }
            return relayToPhone(context, payload)
        }
        return launch(
            context, emailIntent(to, subject, body),
            "Standard-Mail-App mit dem Entwurf geöffnet — der Nutzer prüft und sendet selbst.",
        )
    }

    private fun launch(context: Context, intent: Intent, success: String): String =
        try {
            context.startActivity(intent)
            success
        } catch (e: ActivityNotFoundException) {
            "Keine passende App auf diesem Gerät installiert."
        } catch (e: Exception) {
            "Konnte die App nicht öffnen: ${e.message}"
        }

    /** Schickt die Aktion ans gekoppelte Handy; läuft auf einem IO-Thread, darf also blockieren. */
    private fun relayToPhone(context: Context, payload: JSONObject): String {
        return try {
            val nodes = Tasks.await(
                Wearable.getNodeClient(context).connectedNodes,
                RELAY_TIMEOUT_SECONDS, TimeUnit.SECONDS,
            )
            val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
                ?: return "Kein Handy verbunden — bitte den Wunsch stattdessen als Erinnerung speichern."
            Tasks.await(
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, ACTION_PATH, payload.toString().toByteArray(Charsets.UTF_8)),
                RELAY_TIMEOUT_SECONDS, TimeUnit.SECONDS,
            )
            "An das Handy geschickt — dort wartet jetzt eine Benachrichtigung, die der Nutzer antippt und bestätigt."
        } catch (e: Exception) {
            "Handy gerade nicht erreichbar — bitte den Wunsch stattdessen als Erinnerung speichern."
        }
    }
}

package de.skilltoremember.app.api

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract

/**
 * Übergaben an die System-Apps (Kalender, E-Mail): Die KI füllt nur vor, der
 * Nutzer prüft und bestätigt in der jeweiligen App. Braucht keinerlei
 * Berechtigung, und gespeichert/gesendet wird nie automatisch — die
 * Bestätigung durch den Nutzer ist bewusst Teil des Ablaufs.
 */
object DeviceActions {

    /** Auf der Uhr gibt es keine ausfüllbaren Kalender-/Mail-Dialoge — die Werkzeuge nur am Handy anbieten. */
    fun available(context: Context): Boolean =
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)

    fun openCalendarInsert(
        context: Context,
        title: String,
        startMillis: Long,
        endMillis: Long?,
        location: String?,
        notes: String?,
    ): String {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        endMillis?.let { intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
        location?.takeIf { it.isNotBlank() }?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        notes?.takeIf { it.isNotBlank() }?.let { intent.putExtra(CalendarContract.Events.DESCRIPTION, it) }
        return launch(context, intent, "Kalender-App mit dem vorausgefüllten Termin geöffnet — der Nutzer prüft und speichert selbst.")
    }

    fun openEmailDraft(context: Context, to: String?, subject: String, body: String): String {
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
        return launch(context, intent, "Standard-Mail-App mit dem Entwurf geöffnet — der Nutzer prüft und sendet selbst.")
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
}

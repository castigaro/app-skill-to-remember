package de.skilltoremember.app.update

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Prüft gegen das von der CI veröffentlichte Versions-Manifest
 * (`skilltoremember-version.json` im GitHub-Release `latest`), ob eine
 * neuere Version existiert. Schlägt der Abruf fehl (offline, Repo privat),
 * bleibt die App still — Updates sind ein Angebot, keine Pflicht.
 */
object UpdateChecker {

    const val VERSION_JSON_URL =
        "https://github.com/castigaro/app-skill-to-remember/releases/download/latest/skilltoremember-version.json"

    data class UpdateInfo(val versionCode: Long, val versionName: String, val apkUrl: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Nur für Tests überschreibbar. */
    internal var versionJsonUrl: String = VERSION_JSON_URL

    fun parse(json: String): UpdateInfo? = runCatching {
        val obj = JSONObject(json)
        UpdateInfo(
            versionCode = obj.getLong("versionCode"),
            versionName = obj.getString("versionName"),
            apkUrl = obj.getString("apkUrl"),
        )
    }.getOrNull()

    fun installedVersionCode(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrDefault(Long.MAX_VALUE) // im Zweifel lieber kein Update anbieten

    /** Liefert die neuere Version — oder null, wenn es keine gibt oder der Abruf scheitert. */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(versionJsonUrl).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val info = parse(response.body?.string().orEmpty()) ?: return@runCatching null
                if (info.versionCode > installedVersionCode(context)) info else null
            }
        }.getOrNull()
    }

    /** Lädt die APK nach [target]; true bei Erfolg. */
    suspend fun download(url: String, target: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching false
                val body = response.body ?: return@runCatching false
                target.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                true
            }
        }.getOrDefault(false)
    }
}

package de.skilltoremember.app.api

import android.content.Context
import android.content.SharedPreferences

/**
 * KI-Provider- und API-Key-Einstellungen. Je Anbieter (Anthropic/OpenAI)
 * werden Key, Modell, Aktiv-Schalter und ein Kostenzähler gespeichert. Der
 * "primäre" Anbieter ist der einzige, den die App nutzt — der andere ruht.
 * Ist der primäre Key leer oder deaktiviert, verhält sich die App wie
 * unkonfiguriert.
 */
object ProviderSettings {

    const val PROVIDER_ANTHROPIC = "anthropic"
    const val PROVIDER_OPENAI = "openai"

    const val DEFAULT_MODEL_ANTHROPIC = "claude-sonnet-5"
    const val DEFAULT_MODEL_OPENAI = "gpt-4o-mini"

    /** Aktive Konfiguration für API-Aufrufe. */
    data class ActiveConfig(val provider: String, val apiKey: String, val model: String)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun getPrimaryProvider(context: Context): String =
        prefs(context).getString("primaryProvider", PROVIDER_ANTHROPIC)!!

    fun getKey(context: Context, provider: String): String =
        prefs(context).getString("${provider}Key", "")!!

    fun getModel(context: Context, provider: String): String {
        val stored = prefs(context).getString("${provider}Model", "")!!
        if (stored.isNotBlank()) return stored
        return defaultModel(provider)
    }

    fun isEnabled(context: Context, provider: String): Boolean =
        prefs(context).getBoolean("${provider}Enabled", true)

    fun defaultModel(provider: String): String =
        if (provider == PROVIDER_OPENAI) DEFAULT_MODEL_OPENAI else DEFAULT_MODEL_ANTHROPIC

    fun save(
        context: Context,
        primaryProvider: String,
        anthropicKey: String,
        anthropicModel: String,
        anthropicEnabled: Boolean,
        openaiKey: String,
        openaiModel: String,
        openaiEnabled: Boolean,
    ) {
        prefs(context).edit()
            .putString("primaryProvider", primaryProvider)
            .putString("anthropicKey", anthropicKey.trim())
            .putString("anthropicModel", anthropicModel.trim())
            .putBoolean("anthropicEnabled", anthropicEnabled)
            .putString("openaiKey", openaiKey.trim())
            .putString("openaiModel", openaiModel.trim())
            .putBoolean("openaiEnabled", openaiEnabled)
            .apply()
    }

    /**
     * Löscht den Key eines Anbieters samt Kostenzähler. Hat der andere
     * Anbieter noch einen Key, wird dieser automatisch primär.
     */
    fun deleteKey(context: Context, provider: String) {
        val p = prefs(context)
        val other = otherProvider(provider)
        val edit = p.edit()
            .remove("${provider}Key")
            .remove("${provider}CostMicros")
        if (p.getString("${other}Key", "")!!.isNotBlank()) {
            edit.putString("primaryProvider", other)
        }
        edit.apply()
    }

    fun otherProvider(provider: String): String =
        if (provider == PROVIDER_ANTHROPIC) PROVIDER_OPENAI else PROVIDER_ANTHROPIC

    // ---- Websuche (serverseitiges Anthropic-Tool, kostet extra — Default aus) ----

    fun isWebSearchEnabled(context: Context): Boolean =
        prefs(context).getBoolean("webSearchEnabled", false)

    fun setWebSearchEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("webSearchEnabled", enabled).apply()
    }

    // ---- Kostenzähler (Mikro-Dollar, geschätzt) ----

    fun getCostMicros(context: Context, provider: String): Long =
        prefs(context).getLong("${provider}CostMicros", 0L)

    fun addCostMicros(context: Context, provider: String, micros: Long) {
        if (micros <= 0) return
        val p = prefs(context)
        p.edit().putLong("${provider}CostMicros", p.getLong("${provider}CostMicros", 0L) + micros).apply()
    }

    fun resetCost(context: Context, provider: String) {
        prefs(context).edit().remove("${provider}CostMicros").apply()
    }

    // ---- Aktive Konfiguration ----

    /**
     * Liefert Key/Modell des primären Anbieters — oder null, wenn dessen Key
     * fehlt oder deaktiviert ist. Der jeweils andere Anbieter ruht immer
     * (bewusst kein Fallback).
     */
    fun activeConfig(context: Context): ActiveConfig? {
        val provider = getPrimaryProvider(context)
        val key = getKey(context, provider)
        if (key.isBlank() || !isEnabled(context, provider)) return null
        return ActiveConfig(provider, key, getModel(context, provider))
    }

    fun isConfigured(context: Context): Boolean = activeConfig(context) != null
}

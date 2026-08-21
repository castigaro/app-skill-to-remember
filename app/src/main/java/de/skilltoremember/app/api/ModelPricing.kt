package de.skilltoremember.app.api

/**
 * Feste Preistabelle zur Kostenschätzung (USD pro Million Token, Stand
 * dieses Releases). Der Zähler in den Einstellungen ist ein unverbindlicher
 * Schätzwert; verbindlich ist allein die Abrechnung des jeweiligen Anbieters.
 */
object ModelPricing {

    /** Vorschlagslisten für das Modell-Dropdown, erster Eintrag = Default. */
    val SUGGESTED_ANTHROPIC = listOf("claude-sonnet-5", "claude-opus-4-8", "claude-haiku-4-5")
    val SUGGESTED_OPENAI = listOf("gpt-4o-mini", "gpt-4o")

    /** USD pro Million Token (input, output). */
    private data class Price(val inputUsd: Double, val outputUsd: Double)

    private val PRICES = mapOf(
        "claude-opus-4-8" to Price(5.00, 25.00),
        "claude-sonnet-5" to Price(3.00, 15.00),
        "claude-haiku-4-5" to Price(1.00, 5.00),
        "gpt-4o" to Price(2.50, 10.00),
        "gpt-4o-mini" to Price(0.15, 0.60),
    )

    /**
     * Schätzt die Kosten eines Aufrufs in Mikro-Dollar. Unbekannte Modelle
     * werden mit den Preisen des Provider-Standardmodells geschätzt — die
     * Anzeige trägt ohnehin ein "~".
     */
    fun estimateCostMicros(provider: String, model: String, inputTokens: Long, outputTokens: Long): Long {
        val price = PRICES[model]
            ?: PRICES[ProviderSettings.defaultModel(provider)]
            ?: return 0L
        val usd = (inputTokens * price.inputUsd + outputTokens * price.outputUsd) / 1_000_000.0
        return (usd * 1_000_000.0).toLong()
    }
}

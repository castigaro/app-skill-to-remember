package de.skilltoremember.app.wear

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import de.skilltoremember.app.VoiceSettings
import de.skilltoremember.app.api.ChatApi
import de.skilltoremember.app.api.ProviderSettings
import de.skilltoremember.app.data.Chat
import de.skilltoremember.app.data.ChatStore
import de.skilltoremember.app.data.Message
import de.skilltoremember.app.data.Skill
import de.skilltoremember.app.data.SkillStore
import de.skilltoremember.app.data.memory.GitHubMemorySync
import de.skilltoremember.app.data.memory.MemoryClock
import de.skilltoremember.app.data.memory.MemoryEngine
import de.skilltoremember.app.data.memory.MemorySettings
import de.skilltoremember.app.update.UpdateChecker
import de.skilltoremember.app.wear.databinding.ActivityWearBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Locale

/**
 * Reiner Sprach-Client fürs Handgelenk: tippen, sprechen, Antwort hören,
 * automatisch wieder zuhören. Einstellungen (API-Key, Gedächtnis-Zugang,
 * Stimme) kommen per Data-Layer von der Handy-App — auf der Uhr wird nichts
 * eingetippt. Antworten laufen immer im Knapp-Modus (concise), damit die
 * Sprachausgabe kurz bleibt.
 */
class MainActivity :
    ComponentActivity(),
    DataClient.OnDataChangedListener,
    MenuItem.OnMenuItemClickListener {

    private lateinit var binding: ActivityWearBinding

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var busy = false

    /** Nach einer vorgelesenen Antwort automatisch wieder zuhören. */
    private var autoListen = false

    /** Wird angestoßen, sobald die Standort-Einstellung vom Handy ankommt — Uhr hat ja keine eigene Einstellungsseite. */
    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, R.string.wear_location_denied, Toast.LENGTH_LONG).show()
    }

    private val speechInput = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        } else {
            ""
        }
        if (spoken.isNotBlank()) {
            send(spoken)
        } else {
            autoListen = false // Abbrechen beendet die Gesprächsschleife
            updateStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWearBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.micButton.setOnClickListener {
            if (!busy) {
                autoListen = true
                startListening()
            }
        }

        binding.actionDrawer.setOnMenuItemClickListener(this)
        // Der Wisch von unten gehört auf Wear OS 3 dem System (Schnell-
        // einstellungen) — auf der Galaxy Watch nachgemessen: Der Drawer
        // bekommt die Geste nie zu sehen. Deshalb zwei eigene Wege:
        // ein antippbarer Peek-Balken am unteren Rand und ein langer Druck
        // aufs Mikrofon.
        binding.actionDrawer.controller.peekDrawer()
        binding.micButton.setOnLongClickListener {
            binding.actionDrawer.controller.openDrawer()
            true
        }

        tts = TextToSpeech(this) { status ->
            runOnUiThread {
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                    ttsReady = true
                } else {
                    tts = null
                    Toast.makeText(this, R.string.wear_tts_missing, Toast.LENGTH_LONG).show()
                }
            }
        }

        updateStatus()
        loadStoredConfig()
        // Falls ein früherer Wiederherstellungs-Versuch scheiterte (Netz weg,
        // App zwischendurch beendet): bei jedem Start erneut probieren.
        restoreMemoryIfNeeded()
        checkForUpdate()

        // "Sofort zuhören": App öffnen = sprechen, ohne Mikrofon-Tipp — z. B.
        // per Doppeldruck auf die Home-Taste der Uhr oder "Hey Google, öffne …".
        if (savedInstanceState == null && VoiceSettings.isAutoListenEnabled(this) && ProviderSettings.isConfigured(this)) {
            autoListen = true
            startListening()
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        binding.actionDrawer.controller.closeDrawer()
        when (item.itemId) {
            R.id.action_new_chat -> neuerChat()
            R.id.action_chats -> startActivity(ChatsActivity.starten(this))
            R.id.action_sync -> gedaechtnisJetztAbgleichen()
            R.id.action_quit -> beenden()
            else -> return false
        }
        return true
    }

    /** Ein frisches Gespräch: Der alte Verlauf bleibt erhalten, ist nur nicht mehr aktiv. */
    private fun neuerChat() {
        ChatsActivity.setzeAktivenChat(this, null)
        binding.replyText.text = ""
        Toast.makeText(this, R.string.wear_chat_new_done, Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    /**
     * Gedächtnis von Hand abgleichen — der Weg an der Fünf-Minuten-Drossel
     * vorbei, wenn man am Handy gerade etwas eingetragen hat und es sofort
     * auf der Uhr braucht.
     */
    private fun gedaechtnisJetztAbgleichen() {
        if (!MemorySettings.isConfigured(this) || !MemorySettings.store(this).exists()) {
            Toast.makeText(this, R.string.wear_memory_not_ready, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, R.string.wear_sync_running, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val ergebnis = withContext(Dispatchers.IO) {
                runCatching {
                    val store = MemorySettings.store(this@MainActivity)
                    val config = MemorySettings.config(this@MainActivity)
                    GitHubMemorySync.sync(store, config, "memory: ${MemoryClock.isoNow()}")
                    // Der Pull schreibt index.json nicht — ohne Reindex bliebe
                    // der Digest der nächsten Antwort auf dem alten Stand.
                    MemoryEngine.reindex(store, store.loadEntries(), store.meta(), MemoryClock.now())
                    MemorySettings.setLastSync(this@MainActivity, MemoryClock.isoNow())
                }
            }
            if (beendetOderWeg()) return@launch
            ergebnis.fold(
                onSuccess = {
                    Toast.makeText(this@MainActivity, R.string.wear_sync_done, Toast.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.wear_sync_error, e.message ?: "?"),
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    private fun beendetOderWeg(): Boolean = isFinishing || isDestroyed

    /**
     * Beenden: Sprachausgabe stoppen und die App schließen. Es wird nichts
     * gelöscht — Gedächtnis, Zugangsdaten und Gespräche bleiben, und ein
     * laufender Hintergrund-Abgleich läuft in seinem eigenen Geltungsbereich
     * zu Ende.
     */
    private fun beenden() {
        autoListen = false
        tts?.stop()
        finishAffinity()
    }

    /** Installieren geht auf der Uhr nur per adb — hier gibt es deshalb nur den Hinweis. */
    private fun checkForUpdate() {
        lifecycleScope.launch {
            UpdateChecker.check(applicationContext)?.let { update ->
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.wear_update_available, update.versionName),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
        // Nach der Rückkehr aus der Gesprächsliste soll der Hinweis auf das
        // Menü wieder da sein — und der gewechselte Chat gilt ab sofort.
        binding.actionDrawer.controller.peekDrawer()
        updateStatus()
    }

    override fun onPause() {
        Wearable.getDataClient(this).removeListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    // ---- Einstellungen vom Handy (Data Layer) ----

    /** Liest eine bereits übertragene Konfiguration — Data Items bleiben im Data Layer gespeichert. */
    private fun loadStoredConfig() {
        Wearable.getDataClient(this).dataItems
            .addOnSuccessListener { buffer ->
                for (item in buffer) {
                    if (item.uri.path == CONFIG_PATH) {
                        applyConfig(DataMapItem.fromDataItem(item).dataMap)
                    }
                }
                buffer.release()
                updateStatus()
            }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == CONFIG_PATH) {
                val map = DataMapItem.fromDataItem(event.dataItem).dataMap
                runOnUiThread {
                    applyConfig(map)
                    Toast.makeText(this, R.string.wear_config_received, Toast.LENGTH_SHORT).show()
                    updateStatus()
                }
            }
        }
    }

    private fun applyConfig(map: DataMap) {
        ProviderSettings.save(
            this,
            primaryProvider = map.getString("primaryProvider") ?: ProviderSettings.PROVIDER_ANTHROPIC,
            anthropicKey = map.getString("anthropicKey") ?: "",
            anthropicModel = map.getString("anthropicModel") ?: "",
            anthropicEnabled = map.getBoolean("anthropicEnabled", true),
            openaiKey = map.getString("openaiKey") ?: "",
            openaiModel = map.getString("openaiModel") ?: "",
            openaiEnabled = map.getBoolean("openaiEnabled", true),
        )
        ProviderSettings.setWebSearchEnabled(this, map.getBoolean("webSearch", false))
        ProviderSettings.setLocationEnabled(this, map.getBoolean("location", false))
        MemorySettings.save(
            this,
            owner = map.getString("memOwner") ?: "",
            repo = map.getString("memRepo") ?: "",
            branch = map.getString("memBranch") ?: "main",
            token = map.getString("memToken") ?: "",
        )
        VoiceSettings.setRate(this, map.getFloat("rate", VoiceSettings.DEFAULT_RATE))
        VoiceSettings.setPitch(this, map.getFloat("pitch", VoiceSettings.DEFAULT_PITCH))
        VoiceSettings.setAutoListenEnabled(this, map.getBoolean("autoListen", false))
        // Importierte Skills des Handys spiegeln; ohne "skills"-Feld (älteres
        // Handy) bleibt der Bestand der Uhr unverändert.
        map.getString("skills")?.let { json ->
            runCatching {
                val arr = JSONArray(json)
                val imported = (0 until arr.length()).map { Skill.fromJson(arr.getJSONObject(it)) }
                SkillStore.replaceImported(this, imported)
            }
        }
        ensureLocationPermission()
        restoreMemoryIfNeeded()
    }

    /** Fragt die Standort-Berechtigung ab, wenn das Handy die Funktion aktiviert hat und sie hier noch fehlt. */
    private fun ensureLocationPermission() {
        if (!ProviderSettings.isLocationEnabled(this)) return
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) return
        runCatching { locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
    }

    /** Holt ein bestehendes Gedächtnis vom Repo, falls lokal noch keins liegt — mit sichtbarem Ergebnis. */
    private fun restoreMemoryIfNeeded() {
        if (!MemorySettings.isConfigured(this) || MemorySettings.store(this).exists()) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val store = MemorySettings.store(this@MainActivity)
                    val config = MemorySettings.config(this@MainActivity)
                    val remoteMeta = GitHubMemorySync.readRemoteMeta(config)
                        ?: throw IllegalStateException("Repo leer oder nicht erreichbar")
                    store.saveMeta(remoteMeta)
                    GitHubMemorySync.pull(store, config)
                    MemoryEngine.reindex(store, store.loadEntries(), store.meta(), MemoryClock.now())
                }
            }
            result.fold(
                onSuccess = {
                    Toast.makeText(this@MainActivity, R.string.wear_memory_ready, Toast.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.wear_memory_failed, e.message ?: "?"),
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    // ---- Gesprächsschleife ----

    private fun updateStatus() {
        binding.statusText.setText(
            when {
                !ProviderSettings.isConfigured(this) -> R.string.wear_need_config
                busy -> R.string.wear_thinking
                else -> R.string.wear_tap_to_talk
            }
        )
        binding.micButton.isEnabled = !busy && ProviderSettings.isConfigured(this)
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        runCatching { speechInput.launch(intent) }
            .onFailure { Toast.makeText(this, R.string.wear_speech_unavailable, Toast.LENGTH_LONG).show() }
    }

    /** Ein fortlaufender Uhr-Chat — landet ganz normal im gemeinsamen ChatStore. */
    private fun ensureChat(): Chat {
        ChatsActivity.aktiverChat(this)?.let { ChatStore.get(this, it) }?.let { return it }
        val chat = Chat(title = getString(R.string.wear_chat_title))
        ChatStore.add(this, chat)
        ChatsActivity.setzeAktivenChat(this, chat.id)
        return chat
    }

    private fun send(text: String) {
        busy = true
        updateStatus()
        binding.replyText.text = text // zeigt das Verstandene, bis die Antwort da ist
        lifecycleScope.launch {
            try {
                val chat = withContext(Dispatchers.IO) { ensureChat() }
                // Die erste Äußerung gibt den Titel — sonst hieße in der
                // Chatliste jedes Gespräch „Am Handgelenk".
                if (chat.messages.isEmpty()) chat.title = text.take(40)
                chat.messages.add(Message(Message.ROLE_USER, text))
                val reply = ChatApi.reply(applicationContext, chat, concise = true)
                chat.messages.add(Message(Message.ROLE_ASSISTANT, reply))
                withContext(Dispatchers.IO) { ChatStore.save(applicationContext) }
                binding.replyText.text = reply
                binding.replyScroll.scrollTo(0, 0)
                // Ein hängender Abgleich soll auffallen, nicht nur im Logcat
                // stehen — auf der Uhr gibt es sonst keinen Blick in die Sync-Welt.
                MemorySettings.getLastSyncError(applicationContext)?.let { fehler ->
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.wear_sync_error, fehler),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                speak(reply)
            } catch (e: Exception) {
                binding.replyText.text = getString(R.string.wear_error, e.message ?: "?")
                autoListen = false
            } finally {
                busy = false
                updateStatus()
            }
        }
    }

    private fun speak(text: String) {
        val engine = tts
        if (engine == null || !ttsReady) {
            autoListen = false // ohne Sprachausgabe keine Gesprächsschleife
            return
        }
        VoiceSettings.apply(this, engine)
        val cleaned = VoiceSettings.textForSpeech(text)
        if (cleaned.isBlank()) return
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                runOnUiThread { if (autoListen && !busy && !isFinishing) startListening() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread { autoListen = false }
            }
        })
        engine.speak(cleaned, TextToSpeech.QUEUE_FLUSH, null, "wear-reply")
    }

    companion object {
        const val CONFIG_PATH = "/skilltoremember/config"
    }
}

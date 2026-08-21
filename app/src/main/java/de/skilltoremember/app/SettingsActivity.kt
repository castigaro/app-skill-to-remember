package de.skilltoremember.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import de.skilltoremember.app.api.ModelPricing
import de.skilltoremember.app.api.ProviderSettings
import de.skilltoremember.app.data.memory.GitHubMemorySync
import de.skilltoremember.app.data.memory.MemoryClock
import de.skilltoremember.app.data.memory.MemoryEngine
import de.skilltoremember.app.data.memory.MemorySettings
import de.skilltoremember.app.databinding.ActivitySettingsBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** KI-Provider-Key und die Verbindung zum Gedächtnis-Repo (GitHub). */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** Vom Nutzer eingeblendete Zweit-Sektion (Zustand B beim Provider), nicht persistent. */
    private var showSecondProvider = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setUpProviderSection()
        setUpMemorySection()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ======================================================================
    // KI-Provider (Anthropic/OpenAI) — Zustand A: kein Key, B: ein Key,
    // C: beide Keys mit Primär-Umschalter.
    // ======================================================================

    private fun setUpProviderSection() {
        binding.inputKeyAnthropic.setText(ProviderSettings.getKey(this, ProviderSettings.PROVIDER_ANTHROPIC))
        binding.inputModelAnthropic.setText(ProviderSettings.getModel(this, ProviderSettings.PROVIDER_ANTHROPIC))
        binding.switchEnabledAnthropic.isChecked = ProviderSettings.isEnabled(this, ProviderSettings.PROVIDER_ANTHROPIC)
        binding.inputKeyOpenai.setText(ProviderSettings.getKey(this, ProviderSettings.PROVIDER_OPENAI))
        binding.inputModelOpenai.setText(ProviderSettings.getModel(this, ProviderSettings.PROVIDER_OPENAI))
        binding.switchEnabledOpenai.isChecked = ProviderSettings.isEnabled(this, ProviderSettings.PROVIDER_OPENAI)

        binding.inputModelAnthropic.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, ModelPricing.SUGGESTED_ANTHROPIC))
        binding.inputModelOpenai.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, ModelPricing.SUGGESTED_OPENAI))

        if (ProviderSettings.getPrimaryProvider(this) == ProviderSettings.PROVIDER_OPENAI) {
            binding.providerToggle.check(binding.toggleOpenai.id)
        } else {
            binding.providerToggle.check(binding.toggleAnthropic.id)
        }

        binding.providerToggle.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) refreshProviderUi() }
        binding.inputKeyAnthropic.afterTextChanged { refreshRegisterLinks() }
        binding.inputKeyOpenai.afterTextChanged { refreshRegisterLinks() }
        binding.registerLinkAnthropic.setOnClickListener { openUrl(URL_REGISTER_ANTHROPIC) }
        binding.registerLinkOpenai.setOnClickListener { openUrl(URL_REGISTER_OPENAI) }
        binding.buttonAddSecond.setOnClickListener { showSecondProvider = true; refreshProviderUi() }
        binding.buttonResetCostAnthropic.setOnClickListener { resetCost(ProviderSettings.PROVIDER_ANTHROPIC) }
        binding.buttonResetCostOpenai.setOnClickListener { resetCost(ProviderSettings.PROVIDER_OPENAI) }
        binding.buttonDeleteAnthropic.setOnClickListener { deleteProviderKey(ProviderSettings.PROVIDER_ANTHROPIC) }
        binding.buttonDeleteOpenai.setOnClickListener { deleteProviderKey(ProviderSettings.PROVIDER_OPENAI) }
        binding.buttonSaveSettings.setOnClickListener { saveProviderSettings() }

        refreshProviderUi()
    }

    private fun refreshProviderUi() {
        val hasAnthropic = ProviderSettings.getKey(this, ProviderSettings.PROVIDER_ANTHROPIC).isNotBlank()
        val hasOpenai = ProviderSettings.getKey(this, ProviderSettings.PROVIDER_OPENAI).isNotBlank()
        val toggledOpenai = binding.providerToggle.checkedButtonId == binding.toggleOpenai.id

        when {
            !hasAnthropic && !hasOpenai -> {
                binding.providerToggleLabel.text = getString(R.string.provider_choose_label)
                binding.providerToggleLabel.visibility = View.VISIBLE
                binding.providerToggle.visibility = View.VISIBLE
                binding.sectionAnthropic.visibility = if (toggledOpenai) View.GONE else View.VISIBLE
                binding.sectionOpenai.visibility = if (toggledOpenai) View.VISIBLE else View.GONE
                binding.buttonAddSecond.visibility = View.GONE
                showSecondProvider = false
            }
            hasAnthropic && hasOpenai -> {
                binding.providerToggleLabel.text = getString(R.string.primary_provider_label)
                binding.providerToggleLabel.visibility = View.VISIBLE
                binding.providerToggle.visibility = View.VISIBLE
                binding.sectionAnthropic.visibility = View.VISIBLE
                binding.sectionOpenai.visibility = View.VISIBLE
                binding.buttonAddSecond.visibility = View.GONE
            }
            else -> {
                binding.providerToggleLabel.visibility = View.GONE
                binding.providerToggle.visibility = View.GONE
                binding.sectionAnthropic.visibility = if (hasAnthropic || showSecondProvider) View.VISIBLE else View.GONE
                binding.sectionOpenai.visibility = if (hasOpenai || showSecondProvider) View.VISIBLE else View.GONE
                binding.buttonAddSecond.visibility = if (showSecondProvider) View.GONE else View.VISIBLE
                binding.buttonAddSecond.text = getString(
                    R.string.add_second_provider,
                    getString(if (hasAnthropic) R.string.provider_openai else R.string.provider_anthropic),
                )
            }
        }
        refreshRegisterLinks()
        refreshCosts()
    }

    private fun refreshRegisterLinks() {
        binding.registerLinkAnthropic.visibility = if (binding.inputKeyAnthropic.text.isNullOrBlank()) View.VISIBLE else View.GONE
        binding.registerLinkOpenai.visibility = if (binding.inputKeyOpenai.text.isNullOrBlank()) View.VISIBLE else View.GONE
    }

    private fun refreshCosts() {
        binding.costAnthropic.text = costText(ProviderSettings.PROVIDER_ANTHROPIC)
        binding.costOpenai.text = costText(ProviderSettings.PROVIDER_OPENAI)
    }

    private fun costText(provider: String): String {
        val usd = ProviderSettings.getCostMicros(this, provider) / 1_000_000.0
        return getString(R.string.api_costs, String.format(Locale.GERMANY, "%.4f", usd))
    }

    private fun saveProviderSettings() {
        val anthropicKey = binding.inputKeyAnthropic.text.toString().trim()
        val openaiKey = binding.inputKeyOpenai.text.toString().trim()

        val toggled = if (binding.providerToggle.checkedButtonId == binding.toggleOpenai.id) {
            ProviderSettings.PROVIDER_OPENAI
        } else {
            ProviderSettings.PROVIDER_ANTHROPIC
        }
        val toggledKey = if (toggled == ProviderSettings.PROVIDER_OPENAI) openaiKey else anthropicKey
        val otherKey = if (toggled == ProviderSettings.PROVIDER_OPENAI) anthropicKey else openaiKey
        val primary = if (toggledKey.isBlank() && otherKey.isNotBlank()) ProviderSettings.otherProvider(toggled) else toggled

        ProviderSettings.save(
            this,
            primaryProvider = primary,
            anthropicKey = anthropicKey,
            anthropicModel = binding.inputModelAnthropic.text.toString(),
            anthropicEnabled = binding.switchEnabledAnthropic.isChecked,
            openaiKey = openaiKey,
            openaiModel = binding.inputModelOpenai.text.toString(),
            openaiEnabled = binding.switchEnabledOpenai.isChecked,
        )
        refreshProviderUi()
        Snackbar.make(binding.root, R.string.provider_settings_saved, Snackbar.LENGTH_SHORT).show()
    }

    private fun deleteProviderKey(provider: String) {
        ProviderSettings.deleteKey(this, provider)
        if (provider == ProviderSettings.PROVIDER_ANTHROPIC) binding.inputKeyAnthropic.setText("") else binding.inputKeyOpenai.setText("")
        if (ProviderSettings.getPrimaryProvider(this) == ProviderSettings.PROVIDER_OPENAI) {
            binding.providerToggle.check(binding.toggleOpenai.id)
        } else {
            binding.providerToggle.check(binding.toggleAnthropic.id)
        }
        showSecondProvider = false
        refreshProviderUi()
        Snackbar.make(binding.root, R.string.key_deleted, Snackbar.LENGTH_SHORT).show()
    }

    private fun resetCost(provider: String) {
        ProviderSettings.resetCost(this, provider)
        refreshCosts()
        Snackbar.make(binding.root, R.string.api_costs_reset_done, Snackbar.LENGTH_SHORT).show()
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Snackbar.make(binding.root, R.string.no_browser, Snackbar.LENGTH_LONG).show() }
    }

    // ======================================================================
    // Gedächtnis (GitHub-Repo für den humanoid-behavior-Skill)
    // ======================================================================

    private fun setUpMemorySection() {
        binding.inputMemoryOwner.setText(MemorySettings.getOwner(this))
        binding.inputMemoryRepo.setText(MemorySettings.getRepo(this))
        binding.inputMemoryBranch.setText(MemorySettings.getBranch(this))
        binding.inputMemoryToken.setText(MemorySettings.getToken(this))

        binding.buttonMemoryConnect.setOnClickListener { connectMemory() }
        binding.buttonMemorySync.setOnClickListener { syncMemoryNow() }

        refreshMemoryStatus()
    }

    private fun refreshMemoryStatus() {
        val store = MemorySettings.store(this)
        val connected = store.exists()
        binding.buttonMemorySync.isEnabled = connected

        binding.textMemoryStatus.text = when {
            !connected -> getString(R.string.memory_status_not_connected)
            MemorySettings.getLastSync(this) != null -> getString(R.string.memory_status_synced, MemorySettings.getLastSync(this))
            else -> getString(R.string.memory_status_connected_no_sync)
        }
    }

    private fun setMemoryBusy(busy: Boolean) {
        binding.progressMemory.visibility = if (busy) View.VISIBLE else View.GONE
        binding.buttonMemoryConnect.isEnabled = !busy
        binding.buttonMemorySync.isEnabled = !busy && MemorySettings.store(this).exists()
    }

    private fun connectMemory() {
        val owner = binding.inputMemoryOwner.text.toString().trim()
        val repo = binding.inputMemoryRepo.text.toString().trim()
        val branch = binding.inputMemoryBranch.text.toString().trim().ifBlank { "main" }
        val token = binding.inputMemoryToken.text.toString().trim()
        if (owner.isBlank() || repo.isBlank() || token.isBlank()) {
            Snackbar.make(binding.root, R.string.memory_fields_required, Snackbar.LENGTH_LONG).show()
            return
        }
        MemorySettings.save(this, owner, repo, branch, token)

        setMemoryBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val store = MemorySettings.store(this@SettingsActivity)
                    val config = MemorySettings.config(this@SettingsActivity)
                    val remoteMeta = GitHubMemorySync.readRemoteMeta(config)
                    if (remoteMeta != null) {
                        store.saveMeta(remoteMeta)
                        GitHubMemorySync.pull(store, config)
                        MemoryEngine.reindex(store, store.loadEntries(), store.meta(), MemoryClock.now())
                        "restored"
                    } else {
                        MemoryEngine.createFresh(store, config.url, config.branch)
                        GitHubMemorySync.push(store, config, "memory: init")
                        "ready"
                    }
                }
            }
            setMemoryBusy(false)
            result.fold(
                onSuccess = { outcome ->
                    MemorySettings.setLastSync(this@SettingsActivity, MemoryClock.isoNow())
                    refreshMemoryStatus()
                    val message = if (outcome == "restored") R.string.memory_connect_restored else R.string.memory_connect_ready
                    Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    Snackbar.make(binding.root, getString(R.string.memory_error, e.message ?: "?"), Snackbar.LENGTH_LONG).show()
                },
            )
        }
    }

    private fun syncMemoryNow() {
        setMemoryBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val store = MemorySettings.store(this@SettingsActivity)
                    val config = MemorySettings.config(this@SettingsActivity)
                    GitHubMemorySync.pull(store, config)
                    GitHubMemorySync.push(store, config, "memory: ${MemoryClock.isoNow()}")
                    val meta = store.meta()
                    meta.put("dirty", false)
                    meta.put("last_sync", MemoryClock.isoNow())
                    store.saveMeta(meta)
                }
            }
            setMemoryBusy(false)
            result.fold(
                onSuccess = {
                    MemorySettings.setLastSync(this@SettingsActivity, MemoryClock.isoNow())
                    refreshMemoryStatus()
                    Snackbar.make(binding.root, R.string.memory_sync_done, Snackbar.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    Snackbar.make(binding.root, getString(R.string.memory_error, e.message ?: "?"), Snackbar.LENGTH_LONG).show()
                },
            )
        }
    }

    companion object {
        private const val URL_REGISTER_ANTHROPIC = "https://console.anthropic.com/settings/keys"
        private const val URL_REGISTER_OPENAI = "https://platform.openai.com/api-keys"
    }
}

/** Kleiner Helfer, damit TextWatcher nicht drei leere Methoden braucht. */
private fun EditText.afterTextChanged(action: () -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = action()
    })
}

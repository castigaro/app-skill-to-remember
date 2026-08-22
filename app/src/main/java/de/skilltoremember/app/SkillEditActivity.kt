package de.skilltoremember.app

import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import de.skilltoremember.app.api.ChatApi
import de.skilltoremember.app.data.Skill
import de.skilltoremember.app.data.SkillMarkdown
import de.skilltoremember.app.data.SkillStore
import de.skilltoremember.app.databinding.ActivitySkillEditBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Anlegen oder Bearbeiten eines einzelnen Skills (Name, Beschreibung,
 * Anleitung) — auf Wunsch entwirft die KI den Skill aus einer formlosen Idee,
 * und fertige Skills lassen sich als ZIP exportieren (z. B. zum Teilen oder
 * fürs Skills-Repo).
 */
class SkillEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySkillEditBinding
    private var skill: Skill? = null

    private val exportZip = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> if (uri != null) writeExportZip(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySkillEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val existing = intent.getStringExtra(EXTRA_SKILL_ID)?.let { SkillStore.get(this, it) }
        skill = existing
        supportActionBar?.title = getString(
            if (existing != null) R.string.edit_skill_title else R.string.new_skill_title
        )

        if (existing != null) {
            binding.inputName.setText(existing.name)
            binding.inputDescription.setText(existing.description)
            binding.inputBody.setText(existing.body)
            if (existing.builtIn) {
                // Das Herzstück der App: nur ansehen, nicht verändern.
                supportActionBar?.title = getString(R.string.view_skill_title)
                binding.builtInNote.visibility = View.VISIBLE
                binding.buttonAiDraft.visibility = View.GONE
                binding.buttonSaveSkill.visibility = View.GONE
                binding.inputName.isEnabled = false
                binding.inputDescription.isEnabled = false
                binding.inputBody.isEnabled = false
            }
        }

        binding.buttonAiDraft.setOnClickListener { showAiDraftDialog() }
        binding.buttonSaveSkill.setOnClickListener { save() }
    }

    private fun save() {
        if (skill?.builtIn == true) return // nur ansehen — der Knopf ist ohnehin ausgeblendet
        val name = binding.inputName.text?.toString()?.trim().orEmpty()
        val description = binding.inputDescription.text?.toString()?.trim().orEmpty()
        val body = binding.inputBody.text?.toString()?.trim().orEmpty()

        if (name.isBlank() || description.isBlank() || body.isBlank()) {
            Snackbar.make(binding.root, R.string.skill_fields_required, Snackbar.LENGTH_LONG).show()
            return
        }

        val current = skill
        if (current != null) {
            current.name = name
            current.description = description
            current.body = body
            SkillStore.save(this)
        } else {
            SkillStore.add(this, Skill(name = name, description = description, body = body))
        }
        finish()
    }

    // ---- KI-Entwurf: Idee eintippen, saubere SKILL.md zurückbekommen ----

    private fun showAiDraftDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.skill_ai_idea_hint)
            minLines = 3
        }
        val container = FrameLayout(this).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.skill_ai_draft)
            .setMessage(R.string.skill_ai_intro)
            .setView(container)
            .setPositiveButton(R.string.skill_ai_generate) { _, _ ->
                val idea = input.text?.toString()?.trim().orEmpty()
                if (idea.isNotBlank()) generateDraft(idea)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun generateDraft(idea: String) {
        binding.buttonAiDraft.isEnabled = false
        Snackbar.make(binding.root, R.string.skill_ai_working, Snackbar.LENGTH_LONG).show()
        lifecycleScope.launch {
            val result = runCatching { ChatApi.generateSkill(applicationContext, idea) }
            binding.buttonAiDraft.isEnabled = true
            result.fold(
                onSuccess = { draft ->
                    binding.inputName.setText(draft.name)
                    binding.inputDescription.setText(draft.description)
                    binding.inputBody.setText(draft.body)
                    Snackbar.make(binding.root, R.string.skill_ai_done, Snackbar.LENGTH_LONG).show()
                },
                onFailure = { e ->
                    Snackbar.make(binding.root, getString(R.string.skill_ai_failed, e.message ?: "?"), Snackbar.LENGTH_LONG).show()
                },
            )
        }
    }

    // ---- Export als ZIP (Format der Skill-Bibliothek: <name>/SKILL.md) ----

    private fun exportName(): String =
        binding.inputName.text?.toString()?.trim().orEmpty().ifBlank { "skill" }

    private fun writeExportZip(uri: Uri) {
        val exported = Skill(
            name = exportName(),
            description = binding.inputDescription.text?.toString()?.trim().orEmpty(),
            body = binding.inputBody.text?.toString()?.trim().orEmpty(),
        )
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val out = contentResolver.openOutputStream(uri) ?: throw IOException("Stream ist null")
                    out.use { stream ->
                        ZipOutputStream(stream).use { zip ->
                            zip.putNextEntry(ZipEntry("${exported.name}/SKILL.md"))
                            zip.write(SkillMarkdown.render(exported).toByteArray(Charsets.UTF_8))
                            zip.closeEntry()
                        }
                    }
                }
            }
            result.fold(
                onSuccess = { Snackbar.make(binding.root, R.string.skill_export_done, Snackbar.LENGTH_SHORT).show() },
                onFailure = { e ->
                    Snackbar.make(binding.root, getString(R.string.skill_export_failed, e.message ?: "?"), Snackbar.LENGTH_LONG).show()
                },
            )
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val current = skill
        if (current?.builtIn != true) {
            menuInflater.inflate(R.menu.menu_skill_edit, menu)
            // Löschen gibt es nur für bereits gespeicherte Skills; Export auch für ungespeicherte Entwürfe.
            menu.findItem(R.id.action_delete_skill)?.isVisible = current != null
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_export_zip -> {
            runCatching { exportZip.launch("${exportName()}.zip") }
                .onFailure { Snackbar.make(binding.root, R.string.no_browser, Snackbar.LENGTH_LONG).show() }
            true
        }
        R.id.action_delete_skill -> {
            val current = skill
            if (current != null) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.delete_skill)
                    .setMessage(getString(R.string.delete_skill_message, current.name))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        SkillStore.delete(this, current)
                        finish()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_SKILL_ID = "skillId"
    }
}

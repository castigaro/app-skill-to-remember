package de.skilltoremember.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import de.skilltoremember.app.data.Skill
import de.skilltoremember.app.data.SkillLibrary
import de.skilltoremember.app.data.SkillMarkdown
import de.skilltoremember.app.data.SkillStore
import de.skilltoremember.app.data.SkillZip
import de.skilltoremember.app.databinding.ActivitySkillsBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Verwaltung der Skills: anlegen, bearbeiten, aktivieren/deaktivieren,
 * löschen, oder importieren — als einzelne SKILL.md-Datei oder aus einer
 * ZIP-Datei (z. B. GitHubs "Download ZIP"-Export), jeweils kompatibel zum
 * Agent-Skills-Standard (SKILL.md mit Frontmatter).
 */
class SkillsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySkillsBinding
    private lateinit var adapter: SkillAdapter

    private val importFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importSkillMd(uri) }

    private val importZip = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importSkillZip(uri) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySkillsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = SkillAdapter(
            onClick = { skill -> openEdit(skill) },
            onLongClick = { skill -> confirmDelete(skill) },
            onToggle = { skill, enabled ->
                skill.enabled = enabled
                SkillStore.save(this)
            },
        )
        binding.skillList.layoutManager = LinearLayoutManager(this)
        binding.skillList.adapter = adapter

        binding.fabNewSkill.setOnClickListener { showAddChoices() }
    }

    // ---- "+"-Auswahl: Bibliothek, ZIP, eigener Entwurf ----

    private fun showAddChoices() {
        val options = arrayOf(
            getString(R.string.add_from_library),
            getString(R.string.add_from_zip),
            getString(R.string.add_own_skill),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.add_skill_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showLibraryPicker()
                    1 -> importZip.launch(arrayOf("*/*"))
                    else -> openEdit(null)
                }
            }
            .show()
    }

    /** Katalog laden und einen Skill direkt aus dem Release installieren — ohne Browser-Umweg. */
    private fun showLibraryPicker() {
        Snackbar.make(binding.root, R.string.library_loading, Snackbar.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = runCatching { SkillLibrary.fetchIndex() }
            result.fold(
                onSuccess = { entries ->
                    if (entries.isEmpty()) {
                        Snackbar.make(binding.root, R.string.import_none_found, Snackbar.LENGTH_LONG).show()
                        return@fold
                    }
                    val installed = SkillStore.getAll(this@SkillsActivity).map { it.name.lowercase() }.toSet()
                    // Eigene Zeilen (Name fett, Beschreibung darunter) statt setItems —
                    // mehrzeilige Texte sähen dort wie eine Textwand aus, nicht wie eine Liste.
                    val adapter = object : ArrayAdapter<SkillLibrary.Entry>(this@SkillsActivity, 0, entries) {
                        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                            val row = convertView ?: layoutInflater.inflate(R.layout.row_library_skill, parent, false)
                            val entry = entries[position]
                            val marker = if (entry.name.lowercase() in installed) " ✓" else ""
                            row.findViewById<TextView>(R.id.libraryName).text = "${entry.name}$marker"
                            row.findViewById<TextView>(R.id.libraryDescription).text = entry.description
                            return row
                        }
                    }
                    AlertDialog.Builder(this@SkillsActivity)
                        .setTitle(R.string.library_pick_title)
                        .setAdapter(adapter) { _, which -> installFromLibrary(entries[which]) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                },
                onFailure = {
                    Snackbar.make(binding.root, R.string.library_unreachable, Snackbar.LENGTH_LONG).show()
                },
            )
        }
    }

    private fun installFromLibrary(entry: SkillLibrary.Entry) {
        lifecycleScope.launch {
            val result = runCatching { SkillLibrary.fetchSkills(entry.zipUrl, getString(R.string.untitled_skill)) }
            result.fold(
                onSuccess = { skills ->
                    if (skills.isEmpty()) {
                        Snackbar.make(binding.root, R.string.import_none_found, Snackbar.LENGTH_LONG).show()
                        return@fold
                    }
                    skills.forEach { installOrUpdate(it) }
                    refresh()
                    Snackbar.make(binding.root, getString(R.string.import_done, entry.name), Snackbar.LENGTH_SHORT).show()
                },
                onFailure = { e -> showImportError(e) },
            )
        }
    }

    /** Gleichnamiger Bibliotheks-Skill wird aktualisiert statt dupliziert. */
    private fun installOrUpdate(skill: Skill) {
        val existing = SkillStore.getAll(this).firstOrNull { !it.builtIn && it.name.equals(skill.name, ignoreCase = true) }
        if (existing != null) {
            existing.description = skill.description
            existing.body = skill.body
            SkillStore.save(this)
        } else {
            SkillStore.add(this, skill)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val skills = SkillStore.getAll(this)
        adapter.submit(skills)
        binding.emptyState.visibility = if (skills.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openEdit(skill: Skill?) {
        val intent = Intent(this, SkillEditActivity::class.java)
        if (skill != null) intent.putExtra(SkillEditActivity.EXTRA_SKILL_ID, skill.id)
        startActivity(intent)
    }

    private fun confirmDelete(skill: Skill) {
        if (skill.builtIn) {
            Snackbar.make(binding.root, R.string.skill_built_in_locked, Snackbar.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_skill)
            .setMessage(getString(R.string.delete_skill_message, skill.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                SkillStore.delete(this, skill)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- Einzelne SKILL.md ----

    private fun importSkillMd(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = contentResolver.openInputStream(uri)?.use { SkillZip.readCapped(it) }
                        ?: throw IOException("Stream ist null")
                    val fallbackName = queryDisplayName(uri)?.removeSuffix(".md") ?: getString(R.string.untitled_skill)
                    SkillMarkdown.parse(text, fallbackName)
                }
            }
            result.fold(
                onSuccess = { skill ->
                    SkillStore.add(this@SkillsActivity, skill)
                    refresh()
                    Snackbar.make(binding.root, getString(R.string.import_done, skill.name), Snackbar.LENGTH_SHORT).show()
                },
                onFailure = { e -> showImportError(e) },
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: uri.lastPathSegment

    // ---- ZIP-Datei (z. B. "Download ZIP" eines GitHub-Repos) ----

    private fun importSkillZip(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val stream = contentResolver.openInputStream(uri) ?: throw IOException("Stream ist null")
                    stream.use { SkillZip.parse(it, getString(R.string.untitled_skill)) }
                }
            }
            result.fold(
                onSuccess = { found ->
                    if (found.isEmpty()) {
                        Snackbar.make(binding.root, R.string.import_none_found, Snackbar.LENGTH_LONG).show()
                        return@fold
                    }
                    found.forEach { SkillStore.add(this@SkillsActivity, it) }
                    refresh()
                    Snackbar.make(binding.root, getString(R.string.import_multi_done, found.size), Snackbar.LENGTH_SHORT).show()
                },
                onFailure = { e -> showImportError(e) },
            )
        }
    }

    private fun showImportError(e: Throwable) {
        Snackbar.make(binding.root, getString(R.string.import_error, e.localizedMessage ?: ""), Snackbar.LENGTH_LONG).show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_skills, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_import_file -> {
            // .md-Dateien werden je nach Provider unterschiedlich gemeldet, daher */*.
            importFile.launch(arrayOf("*/*"))
            true
        }
        R.id.action_import_zip -> {
            importZip.launch(arrayOf("*/*"))
            true
        }
        R.id.action_skill_library -> {
            // Fertige Skills zum Herunterladen — ZIP speichern, dann hier über "ZIP importieren" einspielen.
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SKILL_LIBRARY_URL))) }
                .onFailure { Snackbar.make(binding.root, R.string.no_browser, Snackbar.LENGTH_LONG).show() }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        private const val SKILL_LIBRARY_URL = "https://appsonar.de/apps/skilltoremember.html#skill-bibliothek"
    }
}

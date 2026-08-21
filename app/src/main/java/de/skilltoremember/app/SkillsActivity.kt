package de.skilltoremember.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import de.skilltoremember.app.data.Skill
import de.skilltoremember.app.data.SkillMarkdown
import de.skilltoremember.app.data.SkillStore
import de.skilltoremember.app.databinding.ActivitySkillsBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

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

        binding.fabNewSkill.setOnClickListener { openEdit(null) }
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
                    val text = contentResolver.openInputStream(uri)?.use { readCapped(it) }
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
                    val found = mutableListOf<Skill>()
                    val stream = contentResolver.openInputStream(uri) ?: throw IOException("Stream ist null")
                    stream.use { input ->
                        ZipInputStream(input).use { zip ->
                            var entry = zip.nextEntry
                            while (entry != null) {
                                if (!entry.isDirectory && entry.name.substringAfterLast('/').equals("SKILL.md", ignoreCase = true)) {
                                    // Übergroße Einträge (Zip-Bomb, versehentlich gepackte
                                    // Binärdatei) still überspringen statt sie in den Speicher zu lesen.
                                    val text = runCatching { readCapped(zip) }.getOrNull()
                                    if (text != null) {
                                        val fallback = SkillMarkdown.fallbackNameFromPath(entry.name) ?: getString(R.string.untitled_skill)
                                        found.add(SkillMarkdown.parse(text, fallback))
                                    }
                                }
                                zip.closeEntry()
                                entry = zip.nextEntry
                            }
                        }
                    }
                    found
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

    /** Liest höchstens [MAX_SKILL_MD_BYTES]; mehr ist keine SKILL.md. Schließt den Stream nicht. */
    private fun readCapped(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > MAX_SKILL_MD_BYTES) throw IOException(getString(R.string.import_too_large))
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
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
        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        private const val MAX_SKILL_MD_BYTES = 2 * 1024 * 1024
    }
}

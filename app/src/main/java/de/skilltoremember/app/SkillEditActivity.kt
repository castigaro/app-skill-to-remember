package de.skilltoremember.app

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import de.skilltoremember.app.data.Skill
import de.skilltoremember.app.data.SkillStore
import de.skilltoremember.app.databinding.ActivitySkillEditBinding
import com.google.android.material.snackbar.Snackbar

/** Anlegen oder Bearbeiten eines einzelnen Skills (Name, Beschreibung, Anleitung). */
class SkillEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySkillEditBinding
    private var skill: Skill? = null

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
                binding.builtInNote.visibility = android.view.View.VISIBLE
            }
        }

        binding.buttonSaveSkill.setOnClickListener { save() }
    }

    private fun save() {
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val current = skill
        if (current != null && !current.builtIn) menuInflater.inflate(R.menu.menu_skill_edit, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
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

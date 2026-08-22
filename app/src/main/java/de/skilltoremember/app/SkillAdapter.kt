package de.skilltoremember.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import de.skilltoremember.app.data.Skill
import de.skilltoremember.app.databinding.RowSkillBinding

class SkillAdapter(
    private val onClick: (Skill) -> Unit,
    private val onLongClick: (Skill) -> Unit,
    private val onToggle: (Skill, Boolean) -> Unit,
) : RecyclerView.Adapter<SkillAdapter.Holder>() {

    private val skills = mutableListOf<Skill>()

    fun submit(newSkills: List<Skill>) {
        skills.clear()
        skills.addAll(newSkills)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = RowSkillBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = skills.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(skills[position])
    }

    inner class Holder(private val binding: RowSkillBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(skill: Skill) {
            val name = skill.name.ifBlank { binding.root.context.getString(R.string.untitled_skill) }
            binding.skillName.text = if (skill.builtIn) "$name ★" else name
            binding.skillDescription.text = skill.description

            // Listener kurz lösen, sonst feuert er beim programmatischen Setzen erneut.
            binding.skillEnabled.setOnCheckedChangeListener(null)
            if (skill.builtIn) {
                // Das Herzstück der App ist immer aktiv — kein Schalter.
                binding.skillEnabled.visibility = View.GONE
            } else {
                binding.skillEnabled.visibility = View.VISIBLE
                binding.skillEnabled.isChecked = skill.enabled
                binding.skillEnabled.setOnCheckedChangeListener { _, checked -> onToggle(skill, checked) }
            }

            binding.root.setOnClickListener { onClick(skill) }
            binding.root.setOnLongClickListener { onLongClick(skill); true }
        }
    }
}

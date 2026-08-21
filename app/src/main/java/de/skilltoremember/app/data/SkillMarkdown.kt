package de.skilltoremember.app.data

/**
 * Liest und schreibt Skills im SKILL.md-Format des Agent-Skills-Standards
 * (agentskills.io): ein YAML-Frontmatter-Block mit `name` und `description`,
 * gefolgt vom eigentlichen Anleitungstext.
 *
 * Es wird bewusst kein vollständiger YAML-Parser gebraucht — dieser Standard
 * nutzt im Frontmatter nur einzeilige `key: value`-Felder.
 */
object SkillMarkdown {

    private val FRONTMATTER = Regex("^---\\r?\\n(.*?)\\r?\\n---\\r?\\n?(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val FIELD = Regex("^(\\w+):\\s*(.*)$")

    /** Parst SKILL.md-Text. [fallbackName] greift, wenn kein `name`-Feld vorhanden ist (z. B. Dateiname). */
    fun parse(text: String, fallbackName: String = "Neuer Skill"): Skill {
        val match = FRONTMATTER.find(text.trim())
            ?: return Skill(name = fallbackName, description = "", body = text.trim())

        val (frontmatter, body) = match.destructured
        var name = ""
        var description = ""
        for (line in frontmatter.lines()) {
            val fieldMatch = FIELD.find(line.trim()) ?: continue
            val (key, value) = fieldMatch.destructured
            when (key) {
                "name" -> name = value.trim().trim('"', '\'')
                "description" -> description = value.trim().trim('"', '\'')
            }
        }
        return Skill(
            name = name.ifBlank { fallbackName },
            description = description,
            body = body.trim(),
        )
    }

    /** Schreibt einen Skill zurück ins SKILL.md-Format (für den Export). */
    fun render(skill: Skill): String = buildString {
        append("---\n")
        append("name: ${skill.name}\n")
        append("description: ${skill.description}\n")
        append("---\n\n")
        append(skill.body.trim())
        append('\n')
    }

    /**
     * Leitet aus dem Pfad einer SKILL.md (ZIP-Eintrag, z. B.
     * `my-ai-skills-main/skills/n8n-workflow-bootstrap/SKILL.md`) einen
     * Fallback-Namen ab — den Namen des Ordners, der die Datei direkt enthält.
     * Greift nur, wenn die Datei selbst kein `name`-Feld im Frontmatter hat.
     */
    fun fallbackNameFromPath(path: String): String? {
        val segments = path.trim('/').split('/').filter { it.isNotBlank() }
        val withoutFile = if (segments.lastOrNull()?.equals("SKILL.md", ignoreCase = true) == true) {
            segments.dropLast(1)
        } else {
            segments
        }
        return withoutFile.lastOrNull()
    }
}

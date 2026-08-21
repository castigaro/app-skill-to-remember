package de.skilltoremember.app.data

import java.io.File

/** Gemeinsame Datei-Helfer für die JSON-Speicher (ChatStore, SkillStore, MemoryStore). */
internal object FileIo {

    /**
     * Schreibt atomar über eine temporäre Datei + rename — ein Prozess-Tod mitten
     * im Schreiben hinterlässt so nie eine halb geschriebene Zieldatei.
     */
    fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.${System.nanoTime()}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /**
     * Sichert eine nicht mehr parsebare Datei als `<name>.corrupt` weg. Ohne das
     * würde der nächste Speichervorgang die noch reparierbare Datei überschreiben.
     */
    fun quarantine(file: File) {
        val backup = File(file.parentFile, "${file.name}.corrupt")
        backup.delete()
        if (!file.renameTo(backup)) file.delete()
    }
}

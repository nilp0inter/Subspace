package dev.nilp0inter.subspace.channel

import java.io.File

class JournalEntryDiscovery(
    private val metadataStore: JournalMetadataStore = JournalMetadataStore(),
) {
    fun findMetadataForDay(dayDirectory: File): List<Pair<File, JournalEntryMetadata>> {
        val entriesDir = File(dayDirectory, "entries")
        if (!entriesDir.isDirectory) return emptyList()
        val entryDirs = entriesDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        val result = mutableListOf<Pair<File, JournalEntryMetadata>>()
        for (entryDir in entryDirs) {
            val metaFile = entryDir.listFiles()
                ?.firstOrNull { it.name.endsWith(".metadata.json") }
            if (metaFile != null && metaFile.isFile) {
                metadataStore.read(metaFile)?.let { result.add(metaFile to it) }
            }
        }
        return result
    }

    fun findAllMetadataFiles(baseDirectory: File): List<Pair<File, JournalEntryMetadata>> {
        if (!baseDirectory.isDirectory) return emptyList()
        val result = mutableListOf<Pair<File, JournalEntryMetadata>>()
        val yearDirs = baseDirectory.listFiles { f -> f.isDirectory && f.name.matches(Regex("\\d{4}")) }
        for (yearDir in yearDirs.orEmpty()) {
            val monthDirs = yearDir.listFiles { f -> f.isDirectory && f.name.matches(Regex("\\d{4}-\\d{2}")) }
            for (monthDir in monthDirs.orEmpty()) {
                val dayDirs = monthDir.listFiles { f -> f.isDirectory && f.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                for (dayDir in dayDirs.orEmpty()) {
                    result.addAll(findMetadataForDay(dayDir))
                }
            }
        }
        return result
    }
}

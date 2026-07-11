package dev.nilp0inter.subspace.model

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class ChannelCatalogueFileStore(
    private val file: File
) {
    fun load(): ChannelCatalogueSnapshot? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val jsonStr = file.readText()
            ChannelCatalogueCodec.fromJson(jsonStr)
        } catch (e: Exception) {
            throw IOException("Failed to load channel catalogue", e)
        }
    }

    @Synchronized
    fun save(snapshot: ChannelCatalogueSnapshot) {
        ChannelCatalogueValidator.validate(snapshot)
        file.parentFile?.mkdirs()
        val jsonStr = ChannelCatalogueCodec.toJson(snapshot)
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        try {
            tempFile.writeText(jsonStr)
            Files.move(
                tempFile.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (e: Exception) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw IOException("Failed to save channel catalogue atomically", e)
        }
    }
}

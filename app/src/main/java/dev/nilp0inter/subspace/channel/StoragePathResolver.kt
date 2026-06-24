package dev.nilp0inter.subspace.channel

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object StoragePathResolver {
    fun resolveTreeUri(uri: Uri): String? {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        val decoded = URLDecoder.decode(documentId, StandardCharsets.UTF_8.name())
        val parts = decoded.split(':', limit = 2)
        val volume = parts.getOrNull(0).orEmpty()
        val relative = parts.getOrNull(1).orEmpty().trim('/')
        val root = when {
            volume.equals("primary", ignoreCase = true) -> Environment.getExternalStorageDirectory()
            volume.isNotBlank() -> File("/storage", volume)
            else -> return null
        }
        return if (relative.isBlank()) root.absolutePath else File(root, relative).absolutePath
    }
}

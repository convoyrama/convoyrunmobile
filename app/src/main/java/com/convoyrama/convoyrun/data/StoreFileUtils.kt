package com.convoyrama.convoyrun.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun quarantineCorruptStore(file: File, label: String) {
    if (!file.exists()) return
    val backup = File(
        file.parentFile,
        "${file.name}.corrupt-$label-${System.currentTimeMillis()}"
    )
    runCatching { file.renameTo(backup) }
}

internal fun replaceStoreFile(tmpFile: File, targetFile: File) {
    val source = tmpFile.toPath()
    val target = targetFile.toPath()
    try {
        Files.move(
            source,
            target,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        )
        return
    } catch (_: Exception) {
        // Fall back to a non-atomic move only if the filesystem does not support it.
    }
    try {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        return
    } catch (_: Exception) {
        // Final fallback for runtimes where java.nio move is unavailable.
    }
    if (targetFile.exists()) {
        targetFile.delete()
    }
    check(tmpFile.renameTo(targetFile)) { "Failed to rename ${tmpFile.name} to ${targetFile.name}" }
}

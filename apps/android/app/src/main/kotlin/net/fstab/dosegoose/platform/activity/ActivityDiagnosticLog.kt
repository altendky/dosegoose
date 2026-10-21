package net.fstab.dosegoose.platform.activity

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.JsonObject

/** Bounded, app-private JSONL history, independent of medication state. */
class ActivityDiagnosticLog(
    private val directory: File,
    private val maxFileBytes: Long = 1_048_576,
    private val fileCount: Int = 4,
) {
    init {
        require(maxFileBytes > 0)
        require(fileCount > 0)
    }

    @Synchronized
    fun append(record: JsonObject) {
        val bytes = "$record\n".toByteArray(Charsets.UTF_8)
        if (bytes.size > maxFileBytes) throw IOException("Activity diagnostic record exceeds file limit")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create activity diagnostic directory")
        }
        if (file(0).length() + bytes.size > maxFileBytes) {
            Files.deleteIfExists(file(fileCount - 1).toPath())
            for (index in fileCount - 2 downTo 0) {
                val source = file(index).toPath()
                if (Files.exists(source)) {
                    Files.move(source, file(index + 1).toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        FileOutputStream(file(0), true).use { it.write(bytes) }
    }

    private fun file(index: Int): File = File(directory, "transitions.$index.jsonl")
}

package net.fstab.dosegoose.platform.activity

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ActivityDiagnosticLogTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `history survives recreation and retains enter and exit records`() {
        val directory = File(temporaryFolder.root, "diagnostics")
        val enter = record("ENTER", 100)
        val exit = record("EXIT", 200)

        ActivityDiagnosticLog(directory).append(enter)
        ActivityDiagnosticLog(directory).append(exit)

        assertEquals(
            listOf(enter, exit),
            File(directory, "transitions.0.jsonl").readLines().map(Json::parseToJsonElement),
        )
    }

    @Test
    fun `rotation retains newest records within the configured storage bound`() {
        val directory = File(temporaryFolder.root, "diagnostics")
        val records = (100L..105L).map { record("EXIT", it) }
        val fileBytes = (records.first().toString() + "\n").toByteArray().size.toLong()
        val log = ActivityDiagnosticLog(directory, maxFileBytes = fileBytes, fileCount = 3)

        records.forEach(log::append)

        val files = directory.listFiles()!!.sortedByDescending { it.name }
        assertEquals(3, files.size)
        assertTrue(files.sumOf { it.length() } <= 3 * fileBytes)
        assertEquals(
            records.takeLast(3),
            files.flatMap { it.readLines() }.map(Json::parseToJsonElement),
        )
    }

    @Test
    fun `oversized record is rejected without deleting existing evidence`() {
        val directory = temporaryFolder.root
        val log = ActivityDiagnosticLog(directory, maxFileBytes = 100)
        val first = record("ENTER", 100)
        log.append(first)

        val failure = runCatching {
            log.append(buildJsonObject { put("oversized", "x".repeat(101)) })
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("$first\n", File(directory, "transitions.0.jsonl").readText())
    }

    private fun record(transition: String, elapsed: Long) = buildJsonObject {
        put("transition", transition)
        put("event_elapsed_realtime_nanos", elapsed)
    }
}

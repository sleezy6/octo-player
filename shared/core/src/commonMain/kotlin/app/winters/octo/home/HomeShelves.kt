package app.winters.octo.home

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

// Home's server shelves (its stations, Made for you lists and chart) as
// each server last gave them, one file per server in `folder`, so Home can
// show them at once at start and on switching back, before the server
// answers. `server` is the key of the kept server; a blank one keeps
// nothing. A file that is missing or cannot be read counts as none.
class HomeShelfStore<T : Any>(private val folder: File, private val serializer: KSerializer<T>) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    // Read through NIO, which lets a save replace the file while it is
    // read: on Windows, a file read through java.io cannot be replaced or
    // removed until the reader lets go, and the save was lost.
    fun read(server: String): T? {
        val file = fileFor(server) ?: return null
        return runCatching {
            val text = try {
                patiently { String(Files.readAllBytes(file.toPath()), Charsets.UTF_8) }
            } catch (e: NoSuchFileException) {
                return null
            }
            json.decodeFromString(serializer, text)
        }.getOrNull()
    }

    // Written beside the old file first, then moved over it in one step, so
    // a reader never sees half of one. Something else holding the file
    // open (a virus scanner, a backup) is waited out a while; a write that
    // still fails leaves the last one in place.
    @Synchronized
    fun write(server: String, shelves: T) {
        val file = fileFor(server) ?: return
        runCatching {
            folder.mkdirs()
            val next = File(folder, file.name + ".tmp").toPath()
            Files.write(next, json.encodeToString(serializer, shelves).toByteArray(Charsets.UTF_8))
            patiently {
                try {
                    Files.move(next, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (e: AtomicMoveNotSupportedException) {
                    Files.move(next, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    // Runs `step`, trying again for a while when the system refuses it (on
    // Windows, while something else has the file open). A missing file is
    // an answer, not a refusal.
    private fun <R> patiently(step: () -> R): R {
        val giveUpAt = System.nanoTime() + BUSY_FILE_WAIT_MS * 1_000_000
        while (true) {
            try {
                return step()
            } catch (e: IOException) {
                if (e is NoSuchFileException || System.nanoTime() > giveUpAt) throw e
                Thread.sleep(10)
            }
        }
    }

    // The server's key is hashed for the file name, so no name or address
    // shows in it and any key makes a valid one.
    private fun fileFor(server: String): File? =
        server.takeIf { it.isNotBlank() }?.let { File(folder, "${hash(it)}.json") }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }
}

// How long a file that something else has open is waited for.
private const val BUSY_FILE_WAIT_MS = 2_000L

// What a shelf shows once the server answers: what it already shows when
// the answer is equal (the same items in the same order), so nothing on
// screen changes or moves; otherwise the answer.
fun <T> answered(shown: T, answer: T): T = if (shown == answer) shown else answer

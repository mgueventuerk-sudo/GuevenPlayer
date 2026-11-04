package com.mg.player.data.importer

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * Sammelt Einträge und schreibt sie blockweise in die Datenbank.
 * Jeder Block ist ein Room-Insert in einer einzigen Transaktion.
 *
 * Die Schreibzeit jedes Blocks fließt in [ImportClock] (Protokoll „Import-Phasen“). Lesen und Schreiben zu
 * überlappen wurde in 3.25 gemessen und verworfen: kein Gewinn, SQLite schreibt ohnehin nur aus einem Thread.
 */
class BatchWriter<T>(private val batchSize: Int = 1_000, private val flush: suspend (List<T>) -> Unit) {
    private val buffer = ArrayList<T>(batchSize)
    var count: Int = 0
        private set

    suspend fun add(item: T) {
        buffer += item
        count++
        if (buffer.size >= batchSize) flushNow()
    }

    suspend fun finish() {
        if (buffer.isNotEmpty()) flushNow()
    }

    private suspend fun flushNow() {
        val batch = ArrayList(buffer)
        buffer.clear()
        val start = SystemClock.elapsedRealtime()
        flush(batch)
        ImportClock.writeMs.addAndGet(SystemClock.elapsedRealtime() - start)
    }
}

/** Messpunkte des Imports fürs Protokoll (Plan B4): Schreibzeit aller Blöcke seit dem letzten [reset]. */
internal object ImportClock {
    val writeMs = AtomicLong(0)

    fun reset() = writeMs.set(0)
}

/** Stabile, kurze IDs aus beliebigen Strings (z. B. Stream-URLs aus M3U-Listen). */
internal fun stableHash(vararg parts: String): String {
    var h1 = 0x811C9DC5.toInt()
    var h2 = 17
    for (part in parts) {
        for (ch in part) {
            h1 = (h1 xor ch.code) * 0x01000193
            h2 = 31 * h2 + ch.code
        }
        h1 = (h1 xor '|'.code) * 0x01000193
        h2 = 31 * h2 + '|'.code
    }
    return "%08x%08x".format(h1, h2)
}

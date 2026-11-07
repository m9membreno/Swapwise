package dev.swapwise.backend.catalog

import java.io.Reader

/**
 * Minimal RFC 4180 CSV reader: quoted fields, commas and newlines inside quotes, and "" as an escaped
 * quote. Agribalyse product names contain commas, so splitting on the delimiter is not enough.
 * Small enough to read in one sitting, which is why it is not a library dependency.
 */
object CsvReader {
    fun read(reader: Reader, delimiter: Char = ','): Sequence<List<String>> = sequence {
        val text = reader.buffered()
        val field = StringBuilder()
        var row = ArrayList<String>()
        var inQuotes = false
        var sawAnything = false
        while (true) {
            val code = text.read()
            if (code == -1) break
            val c = code.toChar()
            sawAnything = true
            when {
                inQuotes && c == '"' -> {
                    text.mark(1)
                    if (text.read() == '"'.code) {
                        field.append('"')
                    } else {
                        inQuotes = false
                        text.reset()
                    }
                }
                inQuotes -> field.append(c)
                c == '"' && field.isEmpty() -> inQuotes = true
                c == delimiter -> {
                    row.add(field.toString())
                    field.setLength(0)
                }
                c == '\r' -> Unit // handle \r\n by acting on \n only
                c == '\n' -> {
                    row.add(field.toString())
                    field.setLength(0)
                    yield(row)
                    row = ArrayList()
                    sawAnything = false
                }
                else -> field.append(c)
            }
        }
        require(!inQuotes) { "Unterminated quoted field" }
        if (sawAnything) {
            row.add(field.toString())
            yield(row)
        }
    }
}

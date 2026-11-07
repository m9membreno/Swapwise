package dev.swapwise.backend.parsing

import java.security.MessageDigest

/**
 * Cache key for one receipt line: SHA-256 of the prompt version plus the normalized line.
 *
 * - Only the hash is stored, never the line itself.
 * - Including the prompt version means a prompt change automatically misses the old cache entries, so
 *   results from an older, possibly worse prompt are never served.
 */
object ParseCacheKey {
    fun of(promptVersion: String, normalizedLine: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$promptVersion\u0000$normalizedLine".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

package com.mazharansari.streamhub.data

import com.mazharansari.streamhub.BuildConfig
import java.security.MessageDigest

/**
 * 18+ gate (BUILD-CONTRACT.md).
 * The key itself is NEVER stored or hardcoded anywhere: the check compares
 * SHA-256(input) against the hash supplied at build time via the
 * `ADULT_KEY_HASH` BuildConfig field. An empty hash means locked for
 * everyone. The user provides the key separately; it is not invented here.
 */
object AdultGate {

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun isValidKey(input: String): Boolean {
        val expectedHash = BuildConfig.ADULT_KEY_HASH
        if (expectedHash.isBlank()) return false
        if (input.isEmpty()) return false
        return sha256Hex(input).equals(expectedHash.trim(), ignoreCase = true)
    }
}

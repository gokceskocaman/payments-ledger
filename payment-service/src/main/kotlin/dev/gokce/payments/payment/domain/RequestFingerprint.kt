package dev.gokce.payments.payment.domain

import java.security.MessageDigest

/**
 * Hashes the *meaning* of a request, not its bytes.
 *
 * Comparing raw JSON would report a conflict for a reformatted body or reordered keys, and storing
 * the body would keep payment details around for no reason. Hashing the parsed fields in a fixed
 * order makes "same request" mean what a caller would expect it to mean.
 */
object RequestFingerprint {

    fun of(fromAccountId: Long, toAccountId: Long, amount: Long, currency: String): String {
        val canonical = "$fromAccountId|$toAccountId|$amount|${currency.uppercase()}"
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

package dev.gokce.payments.account.domain

/** Which side of a movement an entry records. Amounts are always positive; this carries the sign. */
enum class Direction {
    DEBIT,
    CREDIT,
}

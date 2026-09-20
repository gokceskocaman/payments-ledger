package dev.gokce.payments.account.domain

enum class AccountType {
    /** A real account owned by a person. Its balance may never go negative. */
    CUSTOMER,

    /**
     * Internal counterparty that lets money enter or leave the ledger while keeping every
     * movement balanced. Its balance mirrors everything funded so far, so it is expected
     * to be negative.
     */
    SYSTEM,
}

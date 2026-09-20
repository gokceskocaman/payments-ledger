package dev.gokce.payments.payment.domain

enum class PaymentStatus {
    /**
     * Submitted, outcome not known. Either the transfer has not been attempted yet, or it was
     * attempted and the answer never arrived -- those two are indistinguishable from here, which is
     * exactly why PENDING exists rather than defaulting to FAILED.
     */
    PENDING,

    /** account-service confirmed the movement. Terminal. */
    COMPLETED,

    /** account-service refused the movement, definitively, having written nothing. Terminal. */
    FAILED,
    ;

    val isTerminal: Boolean get() = this != PENDING
}

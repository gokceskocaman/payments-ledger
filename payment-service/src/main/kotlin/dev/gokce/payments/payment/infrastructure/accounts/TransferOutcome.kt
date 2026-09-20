package dev.gokce.payments.payment.infrastructure.accounts

/**
 * Three outcomes, not two. The whole correctness of this service rests on keeping the third one
 * distinct from the second.
 */
sealed interface TransferOutcome {

    /** account-service confirmed the movement. The money has moved exactly once. */
    data object Posted : TransferOutcome

    /**
     * account-service made a decision and declined: insufficient funds, unknown account, currency
     * mismatch. It wrote nothing, and it will answer the same way if asked again, so the payment can
     * safely be called FAILED.
     */
    data class Refused(val reason: String) : TransferOutcome

    /**
     * No answer, or an answer that says nothing about what happened: a read timeout, a dropped
     * connection, a 5xx. The money may or may not have moved -- and from here the two are
     * indistinguishable. The only safe conclusion is "not known yet".
     */
    data class Indeterminate(val reason: String) : TransferOutcome
}

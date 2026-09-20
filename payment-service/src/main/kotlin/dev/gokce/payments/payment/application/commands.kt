package dev.gokce.payments.payment.application

import dev.gokce.payments.payment.domain.Payment

data class SubmitPaymentCommand(
    val idempotencyKey: String,
    val fromAccountId: Long,
    val toAccountId: Long,
    val amount: Long,
    val currency: String,
)

/**
 * [replayed] is false only when this request created the payment. It drives the status code: a
 * caller that created something gets 201, a caller retrying gets 200, and a caller whose payment is
 * still unresolved gets 202.
 */
data class SubmittedPayment(val payment: Payment, val replayed: Boolean)

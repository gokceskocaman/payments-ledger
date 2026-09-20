package dev.gokce.payments.payment.domain

import java.util.UUID

class PaymentNotFoundException(val paymentId: UUID) :
    RuntimeException("Payment $paymentId does not exist")

/**
 * The Idempotency-Key has already been used for a payment with a different body. Answering with the
 * stored payment would silently ignore what this caller actually asked for, so the request is
 * refused instead.
 */
class IdempotencyKeyReusedException(val idempotencyKey: String) :
    RuntimeException("Idempotency-Key '$idempotencyKey' was already used for a different payment")

class IllegalPaymentTransitionException(val from: PaymentStatus, val to: PaymentStatus) :
    RuntimeException("A payment cannot move from $from to $to")

package dev.gokce.payments.payment.domain

enum class PaymentEventType {
    /** The payment was accepted and recorded. Says nothing about whether money moved. */
    PaymentCreated,

    /** account-service confirmed the movement. */
    PaymentCompleted,

    /** account-service definitively refused the movement. */
    PaymentFailed,
}

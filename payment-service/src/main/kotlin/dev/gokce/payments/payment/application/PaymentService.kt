package dev.gokce.payments.payment.application

import dev.gokce.payments.payment.domain.IdempotencyKeyReusedException
import dev.gokce.payments.payment.domain.Payment
import dev.gokce.payments.payment.domain.PaymentEventType
import dev.gokce.payments.payment.domain.PaymentNotFoundException
import dev.gokce.payments.payment.domain.RequestFingerprint
import dev.gokce.payments.payment.infrastructure.PaymentRepository
import dev.gokce.payments.payment.infrastructure.accounts.AccountServiceClient
import dev.gokce.payments.payment.infrastructure.accounts.TransferOutcome
import dev.gokce.payments.payment.infrastructure.outbox.OutboxWriter
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Transaction boundaries here are explicit rather than annotated, because where they *end* is the
 * point of the design: the call to account-service must happen with no database transaction open.
 *
 * A transaction spanning the HTTP call would hold a connection for the whole read timeout, so a slow
 * account-service would drain the connection pool and take this service down with it. Worse, the
 * payment row would not exist until the call returned -- and a crash mid-call would then leave no
 * record that a transfer might have been posted.
 *
 * So: commit the PENDING payment, *then* call, then commit the outcome. The durable PENDING row is
 * what makes the in-doubt case recoverable.
 */
@Service
class PaymentService(
    private val payments: PaymentRepository,
    private val accountService: AccountServiceClient,
    private val outbox: OutboxWriter,
    private val transactions: TransactionTemplate,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun submit(command: SubmitPaymentCommand): SubmittedPayment {
        val requestHash = RequestFingerprint.of(
            fromAccountId = command.fromAccountId,
            toAccountId = command.toAccountId,
            amount = command.amount,
            currency = command.currency,
        )

        val registration = register(command, requestHash)
        val payment = registration.payment

        // Already resolved: a retry must return the stored outcome, not attempt anything again.
        if (payment.status.isTerminal) return registration

        // Still PENDING -- either brand new, or a previous attempt whose answer never arrived. Both
        // are driven forward the same way, and re-sending is safe because the transferId is the
        // payment id: account-service will replay its own movement rather than make a second one.
        val paymentId = checkNotNull(payment.id) { "A registered payment must have an id" }
        val outcome = accountService.transfer(
            transferId = paymentId,
            fromAccountId = payment.fromAccountId,
            toAccountId = payment.toAccountId,
            amount = payment.amount,
            currency = payment.currency,
        )

        return SubmittedPayment(resolve(paymentId, outcome), replayed = registration.replayed)
    }

    fun get(paymentId: UUID): Payment =
        payments.findById(paymentId).orElseThrow { PaymentNotFoundException(paymentId) }

    /** Transaction 1: find the payment this key already names, or create it. */
    private fun register(command: SubmitPaymentCommand, requestHash: String): SubmittedPayment = try {
        requireNotNull(
            transactions.execute {
                payments.findByIdempotencyKey(command.idempotencyKey)
                    ?.let { return@execute SubmittedPayment(matching(it, requestHash), replayed = true) }

                // saveAndFlush, not save: a losing race must raise here, inside the try, rather
                // than at commit time somewhere further out.
                val created = payments.saveAndFlush(
                    Payment(
                        idempotencyKey = command.idempotencyKey,
                        requestHash = requestHash,
                        fromAccountId = command.fromAccountId,
                        toAccountId = command.toAccountId,
                        amount = command.amount,
                        currency = command.currency,
                    ),
                )
                // Same transaction as the INSERT: a payment that exists always has its
                // PaymentCreated event, and an event never describes a payment that was rolled back.
                outbox.record(created, PaymentEventType.PaymentCreated)
                SubmittedPayment(created, replayed = false)
            },
        )
    } catch (e: DataIntegrityViolationException) {
        // Another request with the same key inserted first. The unique constraint -- not this code --
        // is what guarantees one payment per key; read back whatever the winner created.
        log.debug("Idempotency-Key '{}' was claimed concurrently: {}", command.idempotencyKey, e.message)
        requireNotNull(
            transactions.execute {
                val existing = payments.findByIdempotencyKey(command.idempotencyKey)
                    ?: throw IllegalStateException("Idempotency-Key '${command.idempotencyKey}' vanished after a unique violation")
                SubmittedPayment(matching(existing, requestHash), replayed = true)
            },
        )
    }

    /** Transaction 2: record what account-service said. */
    private fun resolve(paymentId: UUID, outcome: TransferOutcome): Payment = requireNotNull(
        transactions.execute {
            val payment = payments.findByIdForUpdate(paymentId) ?: throw PaymentNotFoundException(paymentId)
            if (payment.status.isTerminal) return@execute payment

            when (outcome) {
                is TransferOutcome.Posted -> {
                    payment.complete()
                    outbox.record(payment, PaymentEventType.PaymentCompleted)
                }

                is TransferOutcome.Refused -> {
                    payment.fail(outcome.reason)
                    outbox.record(payment, PaymentEventType.PaymentFailed)
                }

                // No event: nothing terminal has happened yet, and an event saying otherwise
                // would be a claim this service cannot support.
                is TransferOutcome.Indeterminate -> log.warn(
                    "Payment {} left PENDING: {}. The transfer may or may not have been posted; " +
                        "re-sending it with the same transferId is the only way to find out.",
                    paymentId,
                    outcome.reason,
                )
            }
            payment
        },
    )

    private fun matching(payment: Payment, requestHash: String): Payment =
        payment.takeIf { it.matches(requestHash) }
            ?: throw IdempotencyKeyReusedException(payment.idempotencyKey)
}

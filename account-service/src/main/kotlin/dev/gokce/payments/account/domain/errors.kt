package dev.gokce.payments.account.domain

import java.util.UUID

class AccountNotFoundException(val accountId: Long) :
    RuntimeException("Account $accountId does not exist")

class InsufficientFundsException(val accountId: Long?, val balance: Long, val requested: Long) :
    RuntimeException("Account $accountId holds $balance, which cannot cover a debit of $requested")

class CurrencyMismatchException(val expected: String, val actual: String) :
    RuntimeException("Account is held in $expected but the request was in $actual")

class CurrencyNotFundableException(val currency: String) :
    RuntimeException("No system funding account exists for $currency")

class SystemAccountNotAllowedException(val accountId: Long, val operation: String) :
    RuntimeException("Account $accountId is a system account and cannot take part in $operation")

class SameAccountTransferException(val accountId: Long) :
    RuntimeException("Account $accountId cannot transfer to itself")

/**
 * The transferId has already been posted, but with different details. Returning the original
 * movement would silently swallow the caller's intent, so the request is refused instead.
 */
class TransferConflictException(val transferId: UUID) :
    RuntimeException("Transfer $transferId was already posted with different details")

package dev.gokce.payments.account.domain

class AccountNotFoundException(val accountId: Long) :
    RuntimeException("Account $accountId does not exist")

class InsufficientFundsException(val accountId: Long?, val balance: Long, val requested: Long) :
    RuntimeException("Account $accountId holds $balance, which cannot cover a debit of $requested")

class CurrencyMismatchException(val expected: String, val actual: String) :
    RuntimeException("Account is held in $expected but the request was in $actual")

class CurrencyNotFundableException(val currency: String) :
    RuntimeException("No system funding account exists for $currency")

class DepositNotAllowedException(val accountId: Long) :
    RuntimeException("Account $accountId is a system account and cannot be deposited into")

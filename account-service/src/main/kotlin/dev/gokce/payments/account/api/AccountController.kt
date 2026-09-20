package dev.gokce.payments.account.api

import dev.gokce.payments.account.application.AccountService
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/accounts")
class AccountController(private val accountService: AccountService) {

    @PostMapping
    fun create(@Valid @RequestBody request: CreateAccountRequest): ResponseEntity<AccountResponse> {
        val account = accountService.createAccount(request.ownerName, request.currency)
        val body = AccountResponse.from(account)
        return ResponseEntity.created(URI.create("/accounts/${body.id}")).body(body)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): AccountResponse =
        AccountResponse.from(accountService.getAccount(id))

    @GetMapping("/{id}/entries")
    fun entries(
        @PathVariable id: Long,
        @PageableDefault(size = 20, sort = ["id"], direction = Sort.Direction.DESC) pageable: Pageable,
    ): PageResponse<LedgerEntryResponse> =
        PageResponse.of(accountService.listEntries(id, pageable), LedgerEntryResponse::from)

    /**
     * Test helper that funds an account from the system account. 200 rather than 201: the deposit
     * is idempotent, so a replay must be able to answer identically, and the useful thing to return
     * is the resulting account rather than a link to the entries it wrote.
     */
    @PostMapping("/{id}/deposits")
    fun deposit(
        @PathVariable id: Long,
        @Valid @RequestBody request: DepositRequest,
    ): AccountResponse = AccountResponse.from(
        accountService.deposit(
            accountId = id,
            transferId = request.transferId,
            amount = request.amount,
            currency = request.currency,
        ),
    )
}

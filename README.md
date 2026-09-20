# payments-ledger

Two Kotlin / Spring Boot services that move money between accounts safely. `payment-service` accepts
payment requests and is idempotent on an `Idempotency-Key`; `account-service` owns a double-entry
ledger where every movement writes exactly one DEBIT and one CREDIT row in a single transaction.
Money is always `Long` minor units, balances can never go negative, concurrent writers are serialised
with ordered row locks, and a payment whose outcome is unknown stays `PENDING` rather than lying in
either direction. Each service owns its own Postgres; they talk over HTTP, and payment events reach
Kafka through a transactional outbox rather than a dual write.

## Architecture

```
            POST /payments
         (+ Idempotency-Key)
                  |
                  v
   +--------------------------+                    +--------------------------+
   |   payment-service :8082  |  POST /internal/   |   account-service :8081  |
   |                          |      transfers     |                          |
   |  payments table          | -----------------> |  accounts                |
   |  idempotency_key UNIQUE  |   transferId =     |  ledger_entries          |
   |  request_hash            |   payment id       |  DEBIT + CREDIT, 1 tx    |
   |  PENDING -> COMPLETED    | <----------------- |  SELECT ... FOR UPDATE   |
   |            -> FAILED     |   200 / 4xx / --   |  ascending id order      |
   +--------------------------+                    +--------------------------+
                  |                                             |
                  v                                             v
       +--------------------+                        +--------------------+
       |  postgres-payment  |                        |  postgres-account  |
       |  payments          |                        |  accounts          |
       |  outbox_events     |                        |  ledger_entries    |
       |       :5434        |                        |       :5433        |
       +--------------------+                        +--------------------+
                  |
                  |  relay: claim (SKIP LOCKED) -> send -> mark sent
                  v
       +--------------------+
       |   Kafka (KRaft)    |  topic payments.events, key = paymentId
       |       :9092        |  PaymentCreated / Completed / Failed
       +--------------------+
```

One database per service; neither reads the other's tables. `transferId` is the payment's own id, so
retrying a transfer at any layer converges on one movement.

| Module | Port | Database | Responsibility |
|---|---|---|---|
| `account-service` | 8081 | `account` @ `localhost:5433` | accounts, balances, ledger entries, transfers |
| `payment-service` | 8082 | `payment` @ `localhost:5434` | payment requests, idempotency, state machine |

## Running locally

Needs **JDK 21** (the build pins a toolchain of 21 and does not auto-provision one — 17 or 25 fail
with "No matching toolchains found") and a **Docker engine**. Gradle is not needed; the committed
wrapper provides it.

```bash
docker compose up -d
```

```bash
./gradlew build
```

Then run each service in its own shell:

```bash
./gradlew :account-service:bootRun
```

```bash
./gradlew :payment-service:bootRun
```

```bash
curl -s localhost:8081/actuator/health && curl -s localhost:8082/actuator/health
```

`"status":"UP"` with a `db` component means Flyway ran and the pool reached Postgres. Tests need only
Docker — `./gradlew test` starts its own Postgres via Testcontainers and a WireMock stand-in for
account-service.

## Example calls

Create two accounts. On a fresh database they come back as ids **2 and 3** — id 1 is the EUR funding
account the migration seeds, and it is not a customer account:

```bash
curl -s -X POST localhost:8081/accounts -H 'Content-Type: application/json' \
  -d '{"ownerName":"Carol","currency":"EUR"}'
```

Fund one. The deposit debits the funding account and credits Carol, so double entry still holds:

```bash
curl -s -X POST localhost:8081/accounts/2/deposits -H 'Content-Type: application/json' \
  -d '{"transferId":"11111111-1111-1111-1111-111111111111","amount":50000,"currency":"EUR"}'
```

Make a payment. `amount` is minor units, so 12000 is EUR 120.00:

```bash
curl -s -X POST localhost:8082/payments -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: order-4711' \
  -d '{"fromAccountId":2,"toAccountId":3,"amount":12000,"currency":"EUR"}'
```

```json
{"id":"f48631d1-f92d-4f5b-b657-fe882ab7fdc9","status":"COMPLETED","fromAccountId":2,
 "toAccountId":3,"amount":12000,"currency":"EUR","failureReason":null,"createdAt":"..."}
```

Send it again with the same key and body and you get **200** with the identical payment. Send the
same key with a different body and you get **422 idempotency-key-reused**. Overdraw the source and
the payment is created with `"status":"FAILED"` and a reason, while the ledger stays untouched.

Read a payment, an account, and its ledger entries:

```bash
curl -s localhost:8082/payments/f48631d1-f92d-4f5b-b657-fe882ab7fdc9
```

```bash
curl -s localhost:8081/accounts/2 && curl -s 'localhost:8081/accounts/2/entries?page=0&size=20'
```

Errors are RFC 7807 `application/problem+json` throughout, with field-level detail on validation
failures.

## Design decisions

**Money is `Long` minor units plus an ISO 4217 code.** 25000 EUR means 250.00. No `Double`, no
`BigDecimal`: binary floating point cannot represent 0.10, and a ledger that loses a cent to rounding
is worthless. A single currency per movement — no conversion, mismatches are rejected.

**Double-entry ledger, and the balance is derived from it.** Every movement writes exactly two
`ledger_entries` rows sharing a `transfer_id`. Amounts are always positive and the sign lives in
`direction`, so a balance is unambiguously `sum(credits) - sum(debits)`. `accounts.balance` is a
*cache* of that sum written in the same transaction — entries decide, the column remembers — and a
test asserts the two always agree. The table is append-only, enforced by a trigger: corrections are
reversing entries, because an audit trail you can edit is not an audit trail. A CHECK constraint
refuses a negative customer balance even if the service has a bug.

**Pessimistic locking, in ascending id order.** Writers take `SELECT ... FOR UPDATE` per account, one
statement per row, lowest id first. Chosen over optimistic `@Version` + retry because the critical
section is read-check-write ("is the balance enough? then subtract") and must be atomic — optimistic
locking only tells you afterwards that you lost, and retrying a payment under contention throws away
work and makes latency a function of luck. Ordering makes deadlock impossible: a transaction can only
ever wait on a *higher* id than it already holds, so the wait-for graph is acyclic and contention
becomes a queue instead of an aborted transaction. Tested with 50 concurrent transfers out of one
account and with simultaneous transfers in both directions.

**Idempotency is a database constraint, at both layers.** In account-service,
`UNIQUE (transfer_id, account_id, direction)` makes a double post impossible. In payment-service,
`UNIQUE (idempotency_key)` means one key names exactly one payment even under concurrent submission,
and a stored SHA-256 of the canonical request (`from|to|amount|currency`, not the raw bytes) separates
a retry from a reused key. Critically, **the payment id is the `transferId`** — so if the transfer call
times out, the payment stays `PENDING` rather than being guessed as FAILED, and simply asking again
either posts the movement or replays the one already posted. `FAILED` is reserved for a definitive
refusal; a timeout is an absence of information, not a decision.

**Events go through a transactional outbox, not a dual write.** A payment changes a Postgres row and
should produce a Kafka message, and no transaction spans both. Writing the row then publishing loses
the event on a crash; publishing then writing announces a payment that never existed. So the event is
inserted into `outbox_events` in the *same transaction* as the state change -- one commit, nothing to
get out of step -- and a scheduled relay claims rows with `FOR UPDATE SKIP LOCKED`, publishes them,
then marks them sent. That order gives **at-least-once**: a crash between send and mark resends the
event, never drops it. Consumers must therefore deduplicate, and every event carries an `eventId` in
its payload and in an `event-id` header so they can. Messages are keyed by payment id, so one
payment's events share a partition and `PaymentCreated` always precedes its terminal event.

The full reasoning, including the options that were rejected, is in
[docs/design-notes.md](docs/design-notes.md).

## Roadmap

**Done**

- Multi-module Gradle build (Kotlin 2.1, Spring Boot 3.5, JDK 21 toolchain), Docker Compose with two
  Postgres instances and Kafka in KRaft mode
- account-service: accounts, double-entry ledger, paginated entries, funded deposits, idempotent
  `POST /internal/transfers` with ordered pessimistic locking
- payment-service: idempotent `POST /payments`, `PENDING -> COMPLETED | FAILED` state machine,
  RestClient with explicit timeouts, three-way outcome handling for the in-doubt case
- Transactional outbox in payment-service: `PaymentCreated` / `PaymentCompleted` / `PaymentFailed`
  written with the state change, relayed to Kafka by a scheduled poller, keyed by payment id
- RFC 7807 errors and Bean Validation across both services
- 43 tests: Testcontainers Postgres and Kafka, WireMock for account-service, concurrency, timeout and
  outbox cases

**Next**

- **A consumer, and outbox housekeeping** — nothing subscribes to `payments.events` yet, so the
  idempotent-consumer half of the story is still theory. `outbox_events` also keeps every published
  row and needs an archival job.
- **Reconciliation job** — sweep `payments WHERE status = 'PENDING'` past a grace period and re-send
  the transfer. Today an in-doubt payment is only resolved when the caller retries; the partial index
  is already in the schema and the logic is the existing retry path.
- **Security** — OAuth2 resource server with JWT, `/internal/**` restricted to service-to-service
  callers rather than merely named internal.
- **CI** — GitHub Actions running `./gradlew build` with Testcontainers, plus ktlint or detekt.
- **API docs** — springdoc-openapi on both services.
- **AWS** — Dockerfiles, ECS Fargate or EKS, RDS for Postgres, MSK for Kafka, secrets out of
  `application.yml` and into Parameter Store.

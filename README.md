# payments-ledger

Two Kotlin / Spring Boot services that move money between accounts safely: no double payments,
no negative balances, every movement traceable in a double-entry ledger.

## Architecture

```
                 POST /payments
              (+ Idempotency-Key)
                       |
                       v
        +------------------------------+          +------------------------+
        |       payment-service :8082  |  HTTP    |  account-service :8081 |
        |  payment requests, idempo-   | -------> |  accounts, balances,   |
        |  tency, state machine        |          |  double-entry ledger   |
        +------------------------------+          +------------------------+
                  |        |                            |          |
          outbox  |        | JPA                        | JPA      | outbox
                  v        v                            v          v
             +---------+  +------------------+   +------------------+
             |  Kafka  |  | postgres-payment |   | postgres-account |
             | (KRaft) |  |      :5434       |   |      :5433       |
             +---------+  +------------------+   +------------------+
```

One database per service. Services never read each other's tables; they talk over HTTP
and over Kafka events published through a transactional outbox.

## Modules

| Module            | Port | Database                      | Responsibility                          |
|-------------------|------|-------------------------------|-----------------------------------------|
| `account-service` | 8081 | `account` @ `localhost:5433`  | accounts, balances, ledger entries      |
| `payment-service` | 8082 | `payment` @ `localhost:5434`  | payment requests, idempotency, state    |

## Prerequisites

- **JDK 21** — the build pins a Java toolchain of 21 and does not auto-provision one, so 17 or 25
  will fail with "No matching toolchains found". On macOS: `brew install openjdk@21`, then point
  `JAVA_HOME` at `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.
- **A Docker engine** for Postgres and Kafka.
- Gradle itself is not needed — the committed wrapper (`./gradlew`, Gradle 8.14) provides it.

## Running locally

Start the infrastructure:

```bash
docker compose up -d
```

Run each service in its own shell:

```bash
./gradlew :account-service:bootRun
```

```bash
./gradlew :payment-service:bootRun
```

Check they are up and connected to their databases:

```bash
curl -s localhost:8081/actuator/health | jq
```

```bash
curl -s localhost:8082/actuator/health | jq
```

`status: UP` with a `db` component means Flyway ran and the connection pool reached Postgres.

## The ledger schema

```
accounts                             ledger_entries
+--------------+----------------+    +-------------+------------------------------+
| id           | bigint  PK     |<-+ | id          | bigint  PK                   |
| owner_name   | varchar(200)   |  +-| account_id  | bigint  FK -> accounts(id)   |
| currency     | varchar(3)     |    | transfer_id | uuid    groups the two sides |
| account_type | CUSTOMER|SYSTEM|    | direction   | DEBIT | CREDIT              |
| balance      | bigint  cache  |    | amount      | bigint  always > 0           |
| created_at   | timestamptz    |    | currency    | varchar(3)                   |
+--------------+----------------+    | created_at  | timestamptz                  |
                                     +-------------+------------------------------+
                                     UNIQUE (transfer_id, account_id, direction)
```

Money is always `bigint` minor units plus an ISO 4217 code -- 25000 EUR means 250.00 EUR. There is
no decimal type anywhere, because binary floating point cannot represent 0.10 and a ledger that
loses a cent per rounding is worthless.

**Amounts are always positive; the sign lives in `direction`.** Storing -25000 for a debit would
mean every query has to remember which convention it is in, and one wrong sign silently creates
money. With a positive amount and an explicit side, a balance is `sum(credits) - sum(debits)` and
nothing is ambiguous.

**`transfer_id` groups the two sides of one movement**, and `UNIQUE (transfer_id, account_id,
direction)` is what makes a movement replay-safe: a retried deposit or transfer cannot post a
second time, even if two requests arrive at once. Idempotency is a database constraint here, not a
convention the application is trusted to remember.

Three invariants are enforced by the database rather than by the service, so that a bug in Kotlin
still cannot corrupt the ledger:

| Constraint | What it prevents |
|---|---|
| `CHECK (account_type = 'SYSTEM' OR balance >= 0)` | an overdrawn customer account |
| `CHECK (amount > 0)` | a zero or negative entry |
| `BEFORE UPDATE OR DELETE` trigger on `ledger_entries` | rewriting history |

The last one makes the ledger append-only: a mistake is corrected by posting a reversing entry, not
by editing the original. An audit trail you can edit is not an audit trail.

### Why the balance is derived from the entries

`ledger_entries` is the source of truth. The balance of an account is, by definition:

```sql
select coalesce(sum(case when direction = 'CREDIT' then amount else -amount end), 0)
from ledger_entries
where account_id = ?
```

`accounts.balance` is a **cache of that sum**, written in the same transaction as the entries -- never
on its own, and never from a later job. Two reasons for deriving rather than storing:

1. **A stored balance can be wrong; a derived one cannot.** If the balance were the primary record,
   a crash between "write the entry" and "update the balance" would leave money that exists in one
   place and not the other, with no way to tell which is right. With the entries as truth, the
   balance can always be recomputed and compared -- and
   [the integration test asserts exactly that](account-service/src/test/kotlin/dev/gokce/payments/account/AccountLedgerIntegrationTest.kt),
   that the cached value equals the derived one.
2. **Every movement stays explainable.** "Why is this balance 250.00?" is answerable by listing the
   entries that produced it. A bare number answers nothing, which is useless in a dispute.

The cache exists because reading a balance must not cost an aggregate over an account's entire
history, and because `SELECT ... FOR UPDATE` on one `accounts` row is what serialises concurrent
writers. So: entries decide, `balance` remembers.

### Funding, and where money comes from

Double entry means every credit needs a counterparty, so a deposit cannot simply invent money.
`POST /accounts/{id}/deposits` debits a **SYSTEM** account and credits the customer, two rows in one
transaction. The system account's balance is therefore negative by design -- it measures how much
money has entered the ledger from outside -- which is why the non-negative CHECK exempts it. There is
one system account per currency, found by `(account_type, currency)` rather than a hardcoded id.

## Design decisions

- **Multi-module Gradle build with a thin root project.** The root `build.gradle.kts` owns the
  plugins, the JDK 21 toolchain and the settings both services must share; each service's build
  file only lists its own dependencies. Versions live in `gradle/libs.versions.toml` so there is
  one place to bump Spring Boot or Kotlin.
- **Java toolchain instead of "whatever JDK is on PATH".** Gradle resolves a JDK 21 installation
  for compilation, so a local build and CI produce the same bytecode whatever JDK launched Gradle.
  No auto-provisioning resolver is configured, so a JDK 21 must actually be installed.
- **One database per service, separate credentials and ports.** Isolation is enforced by the
  infrastructure, not by convention; neither service can accidentally query the other's tables.
- **Flyway owns the schema, Hibernate validates it.** `ddl-auto: validate` means the schema only
  ever changes through a reviewed migration — the auditable path you want for a ledger.
- **`open-in-view: false`.** Lazy loading in the view layer hides queries and keeps transactions
  open longer than intended; every query must happen inside an explicit service transaction.
- **Kafka in KRaft mode.** Single node, no ZooKeeper — fewer moving parts locally, and it matches
  how Kafka is deployed today. Two listeners: `localhost:9092` for services on the host,
  `kafka:19092` for containers on the compose network.
- **Auto topic creation disabled.** Topics will be declared explicitly, so a typo in a topic name
  fails loudly instead of silently creating a new topic.
- **The Gradle wrapper is committed and pinned to 8.14.** Anyone cloning the repo builds with the
  exact same Gradle, without installing one. 8.14 rather than the current 9.x because Kotlin
  2.1.21 and Spring Boot 3.5.0 do not support Gradle 9 yet; moving to 9 means moving all three.
- **The services run on the host, not in Compose.** Compose provides only infrastructure, which
  keeps the edit/restart/debug loop fast. Dockerfiles can be added later for CI or a demo.
- **Pessimistic row locks, taken in ascending id order.** A write takes
  `SELECT ... FOR UPDATE` on each account row, one statement at a time, lowest id first. Chosen over
  optimistic `@Version` + retry because the read-check-write sequence around a balance has to be
  serialised anyway: under contention on a hot account, optimistic retries burn work and give
  unpredictable latency, and a payment that must not double-post is the wrong place for a race that
  is resolved by trying again. Ascending order means two movements touching the same pair of accounts
  queue instead of deadlocking. (`ORDER BY id ... FOR UPDATE` would not be enough -- Postgres may
  lock rows before sorting them, so the locks are taken one row per statement.)
- **Preconditions are checked against projections, not entities.** A plain read followed by a locked
  read of the same row returns the copy already in the persistence context, so a balance computed
  after it would be built on data that went stale the moment another transaction committed. The
  deposit path reads only `currency` and `id` before locking, then works exclusively from the locked
  entities.
- **Idempotency is a unique constraint, not a lookup.** `UNIQUE (transfer_id, account_id, direction)`
  makes a double post impossible at the storage layer; the `exists` check in the service is only
  there to answer a replay with the original result instead of an error.
- **Errors are RFC 7807 `ProblemDetail`,** shaped in one `@RestControllerAdvice` that extends
  `ResponseEntityExceptionHandler` so framework errors (unreadable body, wrong method) and domain
  errors come out of the same place. Business-rule refusals are 422, a missing account is 404, and a
  validation failure is a 400 that lists every offending field at once.
- **An explicit `PageResponse` envelope** instead of serialising Spring's `Page`: `PageImpl`'s JSON
  shape is an implementation detail that Spring itself warns about serialising, and the page size is
  capped at 100 so `?size=1000000` cannot be used as a denial-of-service request.
- **JPA entities are the domain model.** `Account` owns its invariants -- `balance` has a private
  setter and only `credit` / `debit` can move it, and `debit` is what refuses to overdraw a customer
  account. A separate persistence model with mappers would double the code to describe the same
  shape; it becomes worth it only once the domain model and the table diverge.
- **`ledger_entries` references its account by id, not `@ManyToOne`.** The ledger is read as a flat
  stream, and an association would put a join or a lazy-loading trap on every page of entries. The
  foreign key still exists in the database.
- **The Docker API version is pinned for tests.** Testcontainers' bundled docker-java negotiates API
  1.32, which Docker Engine 29 rejects outright. The test tasks ask for 1.40 -- the oldest version
  modern engines accept, supported since Docker 19.03 -- so the suite runs on old and new daemons alike.

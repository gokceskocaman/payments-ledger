# Design notes

The long-form reasoning behind [payments-ledger](../README.md). The README has the short version of
each of these; this is the argument in full, including the trade-offs that were rejected.

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
   [the integration test asserts exactly that](../account-service/src/test/kotlin/dev/gokce/payments/account/AccountLedgerIntegrationTest.kt),
   that the cached value equals the derived one.
2. **Every movement stays explainable.** "Why is this balance 250.00?" is answerable by listing the
   entries that produced it. A bare number answers nothing, which is useless in a dispute.

The cache exists because reading a balance must not cost an aggregate over an account's entire
history, and because `SELECT ... FOR UPDATE` on one `accounts` row is what serialises concurrent
writers. So: entries decide, `balance` remembers.

### Locking: pessimistic, and why the order matters

`POST /internal/transfers` moves money between two accounts, which means reading two balances,
checking one of them, and writing four rows -- and doing all of it as if nothing else were running.
Two strategies were available.

**Optimistic locking** (`@Version` on the account row, retry on conflict) lets writers proceed without
blocking and detects a collision at commit time: if the version changed underneath you, your
transaction is rejected and you try again. It is the right default for data that is read far more than
it is written, and for conflicts that are genuinely rare.

**Pessimistic locking** (`SELECT ... FOR UPDATE`) takes the row lock up front. Other writers of the
same row wait; readers are unaffected. This project uses it, for three reasons:

1. **The critical section is read-check-write, and it has to be atomic.** "Is the balance at least
   1000? Then subtract 1000." Optimistic locking does not prevent two transactions from both reading
   5000 and both deciding they can afford it -- it only ensures one of them fails at commit. That is
   fine when the answer is "retry", and not fine when the retry has to re-run a business decision that
   may now have a different outcome.
2. **Retry is the wrong failure mode for a payment.** Under contention, optimistic locking converts
   contention into *work thrown away*: with n writers on a hot account, n-1 transactions do their
   reads, their checks and their inserts, then discard all of it. Latency becomes a function of how
   unlucky you are, and the tail gets ugly exactly when the system is busiest. A row lock converts
   contention into *queueing* instead: everyone waits their turn once, and the work is done once.
3. **A blocked writer is easier to reason about than a rolled-back one.** With the lock held, the
   balance we read cannot change before we write it, so the invariant "a customer balance never goes
   negative" is enforced in one place instead of being re-derived on every retry path.

The cost is real and worth stating: writers to one account are serialised, so a single very hot
account becomes a throughput ceiling, and a transaction that holds a lock too long blocks everyone
behind it. That is an acceptable trade for a ledger, where correctness is not negotiable and the
per-transaction work is a handful of small writes. It also means transactions must stay short, which
is one more reason `open-in-view` is off.

**Why the locks are taken in ascending id order.** A deadlock needs a cycle: two transactions each
holding something the other wants. Transfer 7 -> 12 and transfer 12 -> 7 arriving at the same moment
is exactly that shape:

```
           unordered locking                      ordered locking (lowest id first)
  T1: lock 7  ........ wait for 12  \         T1: lock 7 ....... lock 12 -> commit
  T2: lock 12 ........ wait for 7   /  cycle  T2: wait for 7 ................ lock 7, lock 12
           -> Postgres aborts one                      -> no cycle, T2 simply queues
```

If every transaction takes its locks in the same global order, a cycle is impossible: a transaction
can only ever wait on a *higher* id than the ones it already holds, so the wait-for graph is acyclic
by construction. Ordering does not reduce contention -- T2 still waits -- it converts a deadlock
(one side aborted, work lost) into a queue (both sides succeed, one goes second).

Two implementation details this depends on:

- **One `SELECT ... FOR UPDATE` per row, not one statement for both.**
  `where id in (7, 12) order by id for update` looks equivalent but guarantees nothing: Postgres is
  free to lock rows as the plan produces them, which may be before the sort. The only way to control
  lock order is to issue the locks in that order.
- **No lock timeout.** Contention here is expected and benign, so waiting is the desired behaviour;
  a `lock_timeout` would turn a queue back into failures for no gain. The
  [50-concurrent-transfers test](../account-service/src/test/kotlin/dev/gokce/payments/account/InternalTransferIntegrationTest.kt)
  asserts exactly that -- no request fails for any reason other than insufficient funds.

Both claims are tested rather than asserted in prose: 50 simultaneous transfers out of one account
funded for 20 of them end with 20 successes, 30 refusals and a balance of exactly zero, and 50
transfers running in both directions between the same pair of accounts all succeed with money
conserved.

### Funding, and where money comes from

Double entry means every credit needs a counterparty, so a deposit cannot simply invent money.
`POST /accounts/{id}/deposits` debits a **SYSTEM** account and credits the customer, two rows in one
transaction. The system account's balance is therefore negative by design -- it measures how much
money has entered the ledger from outside -- which is why the non-negative CHECK exempts it. There is
one system account per currency, found by `(account_type, currency)` rather than a hardcoded id.

## The in-doubt payment

The hardest question this system has to answer is not "did the payment work?" but **"what do I do
when I do not know whether it worked?"**

`payment-service` calls `account-service` over HTTP. If that call times out, three things could have
happened, and from the caller's side they are indistinguishable:

1. the request never arrived -- no money moved;
2. the request arrived, was refused, and the answer was lost -- no money moved;
3. **the request arrived, the money moved, and the answer was lost.**

Marking the payment FAILED is the tempting move and the wrong one: in case 3 the customer has been
told their payment failed while their money is gone. Marking it COMPLETED is worse in cases 1 and 2.
Either way the record disagrees with reality, silently, forever.

So the outcome is modelled with three values, not two:

| Signal from account-service | Outcome | Payment status | HTTP |
|---|---|---|---|
| 2xx | `Posted` | COMPLETED | 201 |
| 400 / 404 / 422 with a problem document | `Refused` | FAILED | 201 |
| read timeout, connection reset, 5xx | `Indeterminate` | **stays PENDING** | 202 |

FAILED is reserved for a *definitive* refusal: account-service looked at the request, declined it,
and wrote nothing. A timeout is not a refusal, it is an absence of information, and PENDING is the
honest name for that.

### Why PENDING is recoverable rather than stuck

Because **the payment's own id is the `transferId`** sent to account-service. The identifier belongs
to the payment, not to the attempt, so asking again is not asking for a second transfer -- it is
asking about the same one:

```
POST /payments  (Idempotency-Key: k)  -> payment 9f2c...  PENDING
    POST /internal/transfers {transferId: 9f2c...}  -> timeout, outcome unknown

POST /payments  (Idempotency-Key: k)  -> same payment 9f2c...
    POST /internal/transfers {transferId: 9f2c...}  -> 200 {replayed: true}
                                          -> payment 9f2c...  COMPLETED
```

If the money never moved, the retry moves it. If it did move, account-service recognises the
`transferId` and replays the original movement instead of making a second one. Either way the payment
reaches a correct terminal state, and the ledger holds exactly one movement. That is the entire
payoff of making idempotency a database constraint two services apart: the ambiguity is resolved by
*asking again*, which is the only thing a caller in doubt can safely do.

Two consequences worth stating plainly:

- **A retry of a PENDING payment re-attempts the transfer**; a retry of a COMPLETED or FAILED payment
  returns the stored outcome without calling anyone. Terminal means terminal.
- **Nothing here resolves a PENDING payment on its own yet.** Today it is resolved by the caller
  retrying. A reconciliation job that walks
  `payments WHERE status = 'PENDING' AND created_at < now() - interval` and re-sends each transfer is
  the missing piece -- the index for it is already in the schema, and it needs no new logic, because
  re-sending is exactly what the retry path already does.

### Why the HTTP call is outside the transaction

The PENDING row is committed *before* account-service is called, and the outcome is written in a
second transaction afterwards. Both halves matter:

- A transaction held open across the network call would hold its database connection for the whole
  read timeout. A slow account-service would then drain the connection pool, and payment-service
  would fall over because its dependency was merely slow.
- If the payment were written in the same transaction as the outcome, a crash mid-call would roll the
  payment back entirely -- leaving no record that a transfer with that id might have been posted. The
  durable PENDING row *is* the recovery mechanism.

## The outbox, and the dual-write problem

A payment changes two things: a row in Postgres and a message on Kafka. There is no transaction that
spans both, and that is not an implementation gap -- it is the nature of two independent systems.
Publishing from the request thread means picking which one to do first, and both orders are broken:

```
  write DB, then publish                     publish, then write DB
  ----------------------                     ----------------------
  COMMIT payment = COMPLETED  ok             send PaymentCompleted   ok
  send PaymentCompleted       CRASH          COMMIT payment          CRASH / rollback
  -> money moved, nobody told               -> the world was told about a payment
     downstream; the ledger and                 that does not exist; refunds and
     the read model drift apart                 ledgers are built on a lie
```

Retrying inside the request does not fix it, it narrows the window. Neither does a `try/catch`: the
crash you care about is the one that takes the process out between the two statements. And "publish
in an `@TransactionalEventListener(AFTER_COMMIT)`" has exactly the same hole -- after commit, the
publish can still fail and nobody will ever retry it.

**The outbox removes the second system from the critical path.** The event is written to
`outbox_events` in the *same transaction* as the state change:

```sql
BEGIN;
  UPDATE payments SET status = 'COMPLETED' WHERE id = ...;
  INSERT INTO outbox_events (...) VALUES (...);
COMMIT;                     -- one commit, one atomic fact
```

Now there is only one write, so there is nothing to get out of step. Either the payment is completed
and its event is queued, or neither happened. A separate relay moves queued rows to Kafka afterwards,
and it can crash, retry, or run late without ever threatening that invariant -- the worst it can do
is deliver an event twice or deliver it a second later than you would like.

### At-least-once, and why not exactly-once

The relay does three things in this order: **claim the row, publish, mark it published.**

```
claim (FOR UPDATE SKIP LOCKED)  ->  send to Kafka, wait for ack  ->  UPDATE published_at  ->  COMMIT
                                            ^                                    ^
                                     crash here: the row is                crash here: the row is
                                     untouched, so the next tick           still unpublished, so
                                     sends it -- no loss                   the next tick sends it
                                                                           AGAIN -- a duplicate
```

That is **at-least-once**: an event is never lost, and may be delivered more than once. The
alternative ordering -- mark published, then send -- gives at-most-once, where a crash loses the event
silently. For a payments system that is the worse trade: a duplicate is something a consumer can
detect and discard, while a missing `PaymentCompleted` is invisible until someone notices the money.

Exactly-once across Postgres and Kafka is not available here. It would need either a distributed
transaction over both (XA -- slow, and a coordinator whose own failure modes are worse than the
problem), or Kafka transactions, which can only make writes *within Kafka* atomic and cannot include
a Postgres commit. So the honest design is at-least-once plus deduplication at the edge.

**That obligation is pushed to consumers, and the events carry what they need to meet it.** Each
event has an `eventId` -- the outbox row id -- in both the payload and an `event-id` Kafka header. A
consumer records the ids it has processed and drops repeats. This is the same shape as the
`transferId` in account-service and the `Idempotency-Key` at the edge: at every boundary, the
identifier belongs to the *thing* rather than to the *attempt*, so a repeat is recognisable.

### Ordering, and why the key is the payment id

Kafka orders records within a partition, not within a topic, and the key chooses the partition. Every
event for one payment is keyed by that payment's id, so `PaymentCreated` always reaches a consumer
before that payment's `PaymentCompleted`. Events for *different* payments have no ordering guarantee
relative to each other, which is fine -- nothing about payment A depends on payment B.

The relay reinforces this: it processes a claimed batch in `occurred_at` order and **stops at the
first failure** rather than skipping past it, so a failed send cannot let a later event for the same
payment overtake an earlier one.

### Running more than one instance

The claim query is `SELECT ... FOR UPDATE SKIP LOCKED`. Two relays polling at once each lock a
disjoint set of rows and step straight over what the other is holding, instead of queueing behind it
or -- worse -- both publishing the same row. The row lock is held for the length of the send, which is
why the batch is bounded and the send has its own timeout.

### What this costs

- **Latency.** An event is visible on Kafka up to one poll interval after the commit, not instantly.
- **A table that grows.** `outbox_events` keeps every published row; it needs an archival job, which
  is not written yet. The partial index at least keeps the relay's query proportional to the backlog
  rather than to history.
- **Duplicates are real, not theoretical.** Any consumer that is not idempotent will eventually be
  wrong.

## Consumer offsets, and when a message comes back

An **offset** is a monotonic position within one partition. A consumer group stores, per
`(group, topic, partition)`, the offset it intends to read next -- one number, kept in Kafka's
`__consumer_offsets` topic. It is not a per-message acknowledgement: committing offset 105 asserts
that everything below 105 is done, so offsets only make sense when a partition is processed in order
by one consumer at a time. That is why Kafka gives a partition to exactly one member of a group.

**Reading and committing are separate writes**, and everything interesting follows from the order
they happen in:

```
  process then commit  (at-least-once)        commit then process  (at-most-once)
  poll record @105                            poll record @105
  INSERT processed_events; COMMIT             commit offset 106
  commit offset 106                           INSERT processed_events; COMMIT
      ^ crash here -> 105 is read again           ^ crash here -> 105 is never seen again
        = duplicate, which dedup absorbs           = silent loss, which nothing can detect
```

This service does the first. `ack-mode: record` makes the container commit after each record's
handler returns, and `enable-auto-commit: false` keeps a background timer from committing offsets for
records that have only been *read*: a commit has to mean processed, not received.

### When a record is delivered again

Not an exhaustive list, but these are the ones that actually happen:

| Cause | What happens |
|---|---|
| **Handler throws** | The container rolls back, seeks back to the record and retries it in place -- three times here, with capped exponential backoff. |
| **Consumer crashes before committing** | Everything after the last committed offset is read again by whoever takes the partition. |
| **Rebalance** | A member joining or leaving revokes partitions. Anything processed but not yet committed is redelivered to the new owner. |
| **`max.poll.interval.ms` exceeded** | A handler that takes too long looks dead: the group evicts the member, reassigns the partition and redelivers. Slow processing *causes* duplicates. |
| **Producer-side resend** | The outbox relay resends after a crash between publishing and marking the row sent -- a new record carrying an `eventId` that has already been consumed. |
| **`auto-offset-reset` after offset expiry** | A group with no valid committed offset starts at `earliest` here, which replays the retained history. |

Note that most of these are not failures of the consumer. A rebalance is routine, and a slow handler
is a performance problem that quietly becomes a correctness problem without deduplication. This is
why the `processed_events` primary key exists: it makes every one of these rows above a no-op rather
than a second effect.

### Retries and the dead-letter topic

Retrying in place blocks the partition -- every record queued behind the failing one waits. So the
policy has two halves:

- **Retry what might pass:** a database failover, a lock held a moment too long, a blip. Three
  attempts, exponential backoff capped at 2s.
- **Do not retry what cannot pass:** a payload that will not parse is dead-lettered immediately.
  `DefaultErrorHandler.addNotRetryableExceptions(JacksonException, IllegalArgumentException)` says so
  explicitly. Waiting 2s to re-fail on invalid JSON only delays the partition.

Once the attempts are exhausted, `DeadLetterPublishingRecoverer` copies the record to
`payments.events.DLT` with the failure details in headers, the offset is committed, and the partition
moves on. The failure becomes something to inspect, rather than an outage that grows a backlog.

What is deliberately *not* here: nothing reprocesses the DLT. That is an operational decision -- a
dead-lettered payment event needs a human to look at why before it is replayed, and an automatic
DLT-to-source loop is how you build an infinite retry cycle by accident.

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
- **`transferId` is the caller's idempotency key, and a replay returns the original answer.**
  `POST /internal/transfers` answers 200 with the movement as the ledger holds it, including the
  timestamp it was *first* posted at, so a retry after a network timeout is indistinguishable from the
  call it is retrying -- which is the only thing a caller that timed out can safely do. A `replayed`
  flag is there for callers that want to know. The same key with *different* details is a 422 rather
  than a silent success, because returning the original movement would quietly discard what the caller
  actually asked for.
- **No `transfers` table.** A movement *is* its pair of ledger entries, grouped by `transfer_id`. A
  separate table would be a second copy of the same fact, needing its own consistency guarantee
  against the entries; the unique constraint on the entries already provides idempotency, so the table
  would add risk and no information.
- **Transfers are between customer accounts only.** A transfer out of the SYSTEM account would be a
  deposit wearing a disguise, and it would quietly escape the never-go-negative rule that this
  endpoint's whole contract rests on.
- **Balances move before the entries are written.** With IDENTITY primary keys, saving an entry
  inserts immediately, so checking the overdraft first means a refused transfer never writes a row it
  has to roll back.
- **Integration tests share one container and one context.** The container is a started singleton in
  `PostgresTestBase` rather than a JUnit `@Container`: the `@Testcontainers` extension stops a static
  container once its declaring class finishes, which leaves every later test class talking to a closed
  port.
- **The payment id is the transferId.** One identifier, generated once, carried across the service
  boundary -- so a retry from any layer converges on the same movement. See
  [The in-doubt payment](#the-in-doubt-payment).
- **Idempotency is a unique constraint plus a request hash.** `UNIQUE (idempotency_key)` makes one key
  name exactly one payment even under concurrent submission -- the losing insert raises, and the loser
  reads back what the winner created rather than trusting a check-then-insert that has no guarantee.
  The stored SHA-256 of the canonical request (`from|to|amount|currency`, not the raw bytes) is what
  separates a retry from a reused key: same hash replays the stored payment, different hash is a 422.
  Hashing the parsed fields rather than the body means reformatted JSON is still the same request.
- **Explicit `TransactionTemplate` instead of `@Transactional`.** Where the transactions *end* is the
  design here, and annotations hide that -- particularly since a `@Transactional` method called from
  within the same bean is silently not transactional at all.
- **Timeouts are configured, never defaulted.** An HTTP client with no read timeout holds a request
  thread for as long as the far side stays silent, so one stuck dependency takes the whole service
  with it. Connect 500ms, read 2s.
- **Only 400/404/422 count as a refusal.** Any other 4xx is likelier to be a misrouted or malformed
  request than a real decision, and treating that as FAILED would tell a customer their payment was
  declined when nobody ever looked at it.
- **Terminal states are enforced by a trigger.** `PENDING -> COMPLETED | FAILED` and nothing else: once
  a customer has been told an outcome, no code path may quietly rewrite it.
- **WireMock, not a mocked client, for the account-service tests.** The case that matters most -- a read
  timeout -- does not exist above the socket. A mock can be told to throw; only a real server can be
  told to go quiet and let the client's own timeout decide what happens. It runs with h2c disabled, so
  it speaks plaintext HTTP/1.1 exactly like the Tomcat it stands in for.
- **Consumer deduplication is a primary key, not a lookup.** `processed_events.event_id` is the
  primary key and the insert is `ON CONFLICT DO NOTHING`, so claiming an event is one atomic
  statement. Check-then-insert has a window between the two statements that a rebalance can walk
  into, and catching a constraint violation instead would poison the transaction for no gain.
- **`processed_events` lives in `infrastructure.messaging`, not `domain`.** It records what has been
  *consumed*; it says nothing about money. Putting it beside `Account` would imply the ledger cares.
- **The DLT is not reprocessed automatically.** A dead-lettered payment event should be looked at
  before it is replayed; a DLT-to-source loop is an accidental infinite retry.
- **`metadata.max.age.ms` is lowered to 10s on the consumer.** `payments.events` belongs to
  payment-service, so it may not exist when account-service starts, and the five-minute default means
  a consumer that boots first ignores the topic for five minutes after it appears. In a real
  deployment topics are created by infrastructure rather than by whichever service starts first.

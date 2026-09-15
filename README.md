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

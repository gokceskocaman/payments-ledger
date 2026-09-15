rootProject.name = "payments-ledger"

// One database per service, so one Gradle module per service.
include("account-service")
include("payment-service")

dependencyResolutionManagement {
    // Modules must not declare their own repositories; everything resolves from here.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

# Guidelines for AI coding agents

Read this before changing code in this repository.

## Layout
- `core/optimizer/`: pure Kotlin/JVM. No Android or Spring imports, ever. All swap logic lives here.
- `backend/` (separate Gradle build): Spring Boot service for receipt parsing, carbon factors and events.
- Android modules (`app/`, `feature/*`, `core/data/`): the client.
- `docs/adr/`: architecture decision records. Add one for any decision a reviewer would ask "why?" about.

## Build commands
- All checks for the core module: `./gradlew :core:optimizer:check`
- One test class: `./gradlew :core:optimizer:test --tests "dev.swapwise.optimizer.OptimizerTest"`
- Backend checks (needs Docker for Testcontainers): `cd backend && ./gradlew check`
- Run the backend locally: `cd backend && ./gradlew bootRun`
- Format: `./gradlew spotlessApply`

## Backend conventions
- Package by feature: `parsing`, `catalog`, `events`, `security`, `shared`.
- Plain SQL with `JdbcClient`; every schema change is a new Flyway migration, never an edit to an old one.
- Throw the exceptions in `shared/ApiErrors.kt`; Spring renders them as problem details.
- New settings go in `SwapwiseProperties`, not `@Value`.
- Integration tests extend `BaseIT` and use `FakeReceiptLineParser`; they never call a real LLM.

## Rules
- Money is `Long` cents, never `Double`.
- Every optimizer result must be within budget. If you touch `BudgetRepair`, `Nsga2Optimizer` or
  `SuggestionPicker`, keep the property tests that assert this passing.
- Optimizers must be deterministic for a fixed seed.
- Never log receipt text or request bodies (privacy).
- Never put API keys in the Android app.

## Commits and pull requests
- Commit format: `type(scope): description`, with types `feat`, `fix`, `docs`, `refactor`, `test`, `chore`.
  Example: `feat(optimizer): bundle pricier swaps with the swaps that pay for them`.
- Branches: `feat/short-description`, `fix/short-description`.
- A pull request says what changed and why, and all checks pass before merge.

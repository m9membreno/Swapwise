# Swapwise

Scan a grocery receipt, get up to three swaps that cut its carbon footprint without costing more, then
see on your next receipt whether you actually made them.

Research shows that algorithms can find much lower-carbon grocery baskets within a budget
([Asikis, arXiv 2111.05944](https://arxiv.org/abs/2111.05944)), but it stops at simulation: would real
shoppers accept the suggestions? Swapwise measures that with real receipts.

## Status

| Part | State |
| --- | --- |
| `core/optimizer`: NSGA-II, greedy baseline, brute-force oracle, swap cards | Done, 34 tests |
| `backend/`: Spring Boot, receipt parsing, carbon factors, events | Done, unit + Testcontainers integration tests |
| Android app: scan, ledger, swap cards, impact | Next |

## How it works

```
Android app (Kotlin, Jetpack Compose)              Spring Boot backend (Kotlin)
  CameraX + ML Kit text recognition  --OCR lines-->  POST /api/v1/receipts/parse --> hosted LLM
  Room: receipts, carbon factors, events <--ETag--   GET  /api/v1/factors            (key stays here)
  core:optimizer (NSGA-II, on device)                POST /api/v1/events (idempotent) --> Postgres
  WorkManager: retries uploads -------------------->
```

The phone does the heavy work, so the app works offline and receipts stay on the device. Only cleaned
item lines are sent for parsing. The backend exists mainly so the LLM API key never ships in the app.

## The optimizer in one minute

* **Problem:** each receipt line can be kept or swapped. Minimize cost, kg CO2e and disruption (how
  unfamiliar the change is), with the budget as a hard limit.
* **NSGA-II** returns a set of trade-offs. Over-budget children are **repaired** (undo the swap that
  adds the most cost) instead of penalized, so every basket in the search is valid
  ([ADR-001](docs/adr/ADR-001-nsga2-with-budget-repair.md)).
* **Swap cards** are built so that *any combination* the shopper accepts stays within budget: a pricier
  swap is bundled with the cheaper swap that pays for it
  ([ADR-002](docs/adr/ADR-002-budget-safe-suggestion-cards.md)).

## The backend in one minute

| Endpoint | What it does | Failure handling |
| --- | --- | --- |
| `POST /api/v1/receipts/parse` | Cleans lines, serves repeats from a hash cache, sends only new lines to the LLM | 10 s timeout then 503 + `Retry-After`; per-install and global daily quotas (429) |
| `GET /api/v1/factors` | Current carbon factors per food category, with a low-to-high range | ETag = release version, so unchanged factors cost a 304 |
| `POST /api/v1/events` | Shown / accepted / dismissed / verified events | Client-made IDs + `on conflict do nothing`: retries never double count |

Every `/api/` call needs a Firebase App Check token. Errors are RFC 9457 problem details. Receipt text is never
logged or stored ([ADR-003](docs/adr/ADR-003-receipt-parsing-pipeline.md),
[ADR-004](docs/adr/ADR-004-idempotent-events-plain-sql.md)).

Metrics (Prometheus at `/actuator/prometheus`): LLM latency percentiles, lines served from cache vs LLM,
failures by reason, quota rejections.

## Results so far

| Metric | Value | How |
| --- | --- | --- |
| Greedy, % of best possible saving | 83% | 39 random 8-item baskets vs brute force |
| NSGA-II, % of best possible saving | 100% (worst case 100%) | same baskets |
| Optimizer time, desktop JVM | 85 ms p50, 98 ms p95 | warm, 10 to 50 lines |
| Optimizer time, mid-range phone | to measure | target under 300 ms p95 |
| Parser line accuracy | to measure | 100 hand-labeled lines |
| Swap acceptance, verified follow-through | to measure | 10 to 20 person beta |

## Running it

```bash
# Optimizer (JDK 17+)
./gradlew :core:optimizer:check

# Backend (JDK 21+, Docker running)
cd backend
cp .env.example .env            # add an OpenAI key, or point it at a local Ollama
./gradlew check                 # unit + integration tests (Testcontainers starts Postgres)
./gradlew bootRun               # starts Postgres via compose.yml, then the API on :8080

curl -s localhost:8080/api/v1/receipts/parse -H 'Content-Type: application/json' \
  -H "X-Install-Id: $(uuidgen)" -d '{"lines": ["ORG BNNA 2LB 1.99", "TOTAL 1.99"]}'
```

Carbon factors are loaded with a one-off import; see [`backend/data/README.md`](backend/data/README.md).

## Decisions

Architecture decisions and the alternatives considered are in [`docs/adr/`](docs/adr/).

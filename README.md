# Swapwise

Scan a grocery receipt, get up to three swaps that cut its carbon footprint without costing more, then
see on your next receipt whether you actually made them.

Research shows that algorithms can find much lower-carbon grocery baskets within a budget
([Asikis, arXiv 2111.05944](https://arxiv.org/abs/2111.05944)), but it stops at simulation: would real
shoppers accept the suggestions? Swapwise measures that with real receipts.

## Status

| Part | State |
| --- | --- |
| `core/optimizer`: NSGA-II, greedy baseline, brute-force oracle, swap cards | Done, 33 tests passing |
| `backend/`: Spring Boot, receipt parsing, carbon factors, events | Next |
| Android app: scan, ledger, swap cards, impact | After the backend |

## How it works

```
Android app (Kotlin, Jetpack Compose)            Spring Boot backend (Kotlin)
  CameraX + ML Kit text recognition  --OCR lines-->  POST /api/receipts/parse  --> hosted LLM
  Room: receipts, carbon factors, events <--ETag--   GET  /api/factors              (key stays here)
  core:optimizer (NSGA-II, on device)                POST /api/events (idempotent) -> Postgres
  WorkManager: retries uploads ------------------->
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

### Results so far

| Metric | Value | How |
| --- | --- | --- |
| Greedy, % of best possible saving | 83% | 39 random 8-item baskets vs brute force |
| NSGA-II, % of best possible saving | 100% (worst case 100%) | same baskets |
| Optimizer time, desktop JVM | 85 ms p50, 98 ms p95 | warm, 10 to 50 lines |
| Optimizer time, mid-range phone | to measure | target under 300 ms p95 |
| Swap acceptance, verified follow-through | to measure | 10 to 20 person beta |

## Running it

Requires JDK 17+.

```bash
./gradlew :core:optimizer:check   # tests, coverage check (85% minimum)
./gradlew spotlessApply           # format
```

## Decisions

Architecture decisions and the alternatives considered are in [`docs/adr/`](docs/adr/).

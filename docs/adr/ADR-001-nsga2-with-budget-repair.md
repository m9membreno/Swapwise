# ADR-001: Use NSGA-II with a budget repair step to find swaps

* **Status:** Accepted
* **Date:** 2026-11-07

## Context

Each receipt line can be kept or swapped for one of a few alternatives. We want baskets that are low
in carbon, do not cost more than the shopper paid, and do not ask for too much change at once. These
goals conflict: the lowest-carbon basket usually asks for the most change. A receipt with 30 lines and
3 alternatives each has 4^30 (about 10^18) possible baskets, so trying them all is impossible on a phone.

## Decision

Run NSGA-II on the phone with three objectives (cost, kg CO2e, disruption), all minimized, and treat
the budget as a hard constraint.

* **Encoding:** one gene per line, holding the index of the chosen option (0 = keep the original).
* **Constraint handling:** any child over budget is repaired by undoing the swap that adds the most cost,
  repeated until it fits. Because the budget is never below the original cost, repair always succeeds.
* **Seeding:** the starting population includes the original basket and the greedy answer. With
  elitism, this guarantees the result is never worse than greedy on carbon.
* **Determinism:** a fixed random seed, so the same receipt always gets the same suggestions.

Quality is measured against a brute-force oracle on baskets of 8 lines or fewer
(`OptimizerQualityTest`). Current result over 39 random baskets: greedy reaches 83% of the best possible
carbon saving, NSGA-II 100%.

## Consequences

### Positive
* Returns a set of trade-offs (a Pareto front), not one answer, so the app can choose how much change
  to ask for.
* No dependency on a solver library; about 300 lines of plain Kotlin that run offline.
* Every basket the search ever holds is valid, so no effort is wasted on baskets that cannot be shown.

### Negative
* No proof of optimality on large receipts; we rely on the small-basket oracle comparison.
* Runtime grows with population times generations; must be measured on a real mid-range phone
  (target p95 under 300 ms). About 85 ms p95 on a desktop JVM today.

## Alternatives considered

### Integer linear programming
Exact, and the problem fits it well for a single objective. Rejected because it needs a solver library
on the device, gives one answer per run instead of a set of trade-offs, and would need several runs with
different weights to approximate the front.

### Greedy only
Ten lines of code and instant. Rejected as the main method because it never pays a little more on one
line to unlock a bigger saving on another (see `fundedSwapProblem` in the tests). Kept as the baseline.

### Penalizing over-budget baskets instead of repairing them
Simpler to write. Rejected because the search wastes effort on baskets that can never be shown, and the
final answer can still be over budget when the penalty is too weak.

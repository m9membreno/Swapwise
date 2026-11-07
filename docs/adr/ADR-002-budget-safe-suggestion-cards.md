# ADR-002: Every swap card must be within budget on its own

* **Status:** Accepted
* **Date:** 2026-11-07

## Context

The optimizer returns whole baskets, but the app shows up to three swap cards, and the shopper accepts or
dismisses each card separately. A good basket often pays a little more on one line to save more on
another. If those two swaps were shown as separate cards, a shopper who accepts only the pricier one
would end up over budget, breaking the product rule "a suggestion never makes your basket more expensive".

## Decision

Build cards so that any combination of accepted cards stays within budget:

* A swap that does not raise the cost is its own card.
* A swap that raises the cost is bundled with the cheaper swaps that pay for it (up to 3 swaps per card),
  so the card as a whole does not raise the cost.
* If the shopper allowed some extra spend (budget above the original cost), pricier swaps can be shown
  alone, but their total extra cost is capped at that allowance.
* A pricier swap that cannot be paid for either way is not shown.
* Swaps that raise carbon are never shown, even if the optimizer used them to save money.

Candidate swaps come from every basket on the Pareto front: for each receipt line, the swap with the best card
score is kept. Cards are ranked by that score, carbon saved per unit of change (`kgCo2eSaved / (1 + disruption)`).

Why candidates come from the whole front rather than one chosen basket: a set of swaps that makes great cards
can be dominated as a whole. "Chicken instead of beef, plus oat milk" is dominated by "lentils instead of beef"
alone, yet it gives two more acceptable cards. Since cards are accepted one at a time, they are chosen one at a
time. (Found by an end-to-end test; first version picked a single basket.)

`SuggestionPickerTest` checks every subset of the cards for 60 random receipts.

## Consequences

### Positive
* The budget promise holds whatever the shopper accepts.
* Bundles explain themselves: "swap these two together, same price, 9 kg less".

### Negative
* Some savings the optimizer found are not shown when a pricier swap cannot be paid for.
* Bundles are a little more complex to show and to log in analytics (one accept covers several swaps).

## Alternatives considered

### Show swaps separately and warn when the total goes over budget
Simpler cards, but the warning appears after the shopper has already said yes, which feels like a trick.

### Only ever show swaps that do not raise the cost
Simple and safe, but at zero allowance the problem then splits line by line and a per-line greedy choice is
already optimal, which would make the optimizer pointless.

### Make all cards one "accept the whole plan" button
Safe, but the acceptance data could no longer tell which individual swaps people like, and that is the
question this project exists to answer.

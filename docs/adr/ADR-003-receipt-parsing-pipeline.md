# ADR-003: Parse receipts with an LLM behind the backend, with a cache, quotas and a timeout

* **Status:** Accepted
* **Date:** 2026-11-07

## Context

ML Kit gives us raw OCR lines such as `ORG BNNA 2LB 1.99`. Every store abbreviates differently, so rules
break constantly; an LLM handles this well. But an LLM call costs money, takes seconds, sometimes fails, and
needs an API key that must not ship in the app. Receipts are also private.

## Decision

`POST /api/v1/receipts/parse` on the Spring Boot backend:

1. **Clean first.** `LineSanitizer` drops totals, payment and visit lines and masks long digit runs (card,
   phone, loyalty numbers). The app does the same before upload; the server repeats it because it cannot
   trust every client to be current.
2. **Cache by hash.** Key = SHA-256(prompt version + normalized line), stored in Postgres. Only the hash and
   the parsed result are stored, never the line. Changing the prompt version makes old entries miss.
3. **One LLM call per receipt** for the cache misses only, with structured JSON output limited to a fixed
   list of food categories, retried by Spring AI if the JSON is invalid.
4. **Timeout of 10 seconds.** On timeout or error: 503 with `Retry-After`; the app keeps the raw lines and
   WorkManager retries later.
5. **Quotas:** 600 LLM lines per install per day, and 20,000 for everyone together. Cached lines are free.
6. **Only real installs:** a Firebase App Check token (Play Integrity) is required on every `/api/` call.
7. **No receipt text in logs.** Spring AI's logging advisor is deliberately not used.

## Consequences

### Positive
* The API key never leaves the server; the bill has a hard daily ceiling.
* Repeat products (most of a weekly shop) cost nothing after the first time and return instantly.
* A slow or broken LLM never hangs the app.

### Negative
* Quota counters live in memory: correct for one instance, but they reset on restart, and with two or more
  instances each would count separately. Next step at that point: move the counters to Postgres or Redis.
* Parsing needs a network connection; scanning works offline, parsing waits until the app is online.
* Category averages are coarse: "chicken thighs" and "chicken breast" share one factor.

## Alternatives considered

### Call the LLM directly from the app
Simplest, but the API key would be extractable from the APK, and there would be no shared cache or quota.

### Hand-written rules per store
Free and private, but every new store or abbreviation needs new rules. Kept as a possible fallback for the
most common lines if LLM cost becomes a problem.

### On-device model (Gemini Nano through ML Kit)
Best for privacy and offline use, but only on recent devices and still in alpha. Listed as a future step.

### Cache the raw line text
Easier to debug, but stores receipt contents. Hashing gives the same hit rate without keeping the text.

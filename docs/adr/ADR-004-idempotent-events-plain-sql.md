# ADR-004: Idempotent event uploads with a client-made ID and plain SQL

* **Status:** Accepted
* **Date:** 2026-11-07

## Context

The app records when a swap card is shown, accepted, dismissed, or verified by a later receipt. These events
are the project's core data: they answer "do people accept the swaps?". Phones lose connectivity, WorkManager
retries, and a request can succeed on the server while its response is lost (the two generals problem), so the
same batch will sometimes arrive more than once. Counting it twice would inflate the acceptance rate.

## Decision

* The app creates a UUID `eventId` when the event happens and stores it with the event in Room. Every retry
  sends the same ID.
* The server inserts with `insert ... on conflict (event_id) do nothing`. Postgres's primary key decides what is
  a duplicate, so even two retries racing each other cannot both be stored. The first write wins.
* A retried batch gets the same 200 response, with `accepted` and `duplicates` counts. The app deletes events
  from Room only after a 2xx response.
* Persistence uses Spring's `JdbcClient` and plain SQL, with Flyway migrations, instead of JPA.

## Consequences

### Positive
* Exactly-once storage on top of at-least-once delivery, with one line of SQL.
* The SQL that runs is the SQL in the code, which makes behavior like `on conflict` explicit and easy to review.

### Negative
* If a buggy client reused an ID for a different event, the second event would be silently dropped.
* No ORM conveniences (dirty checking, relationships); fine for four small tables.

## Alternatives considered

### Deduplicate in application code ("select, then insert if missing")
Two concurrent retries can both see "missing" and both insert. Only a database constraint closes that race.

### Server-generated IDs
The server cannot tell a retry from a new event, so retries would create duplicates.

### JPA / Hibernate
The default in many Spring apps, but `on conflict do nothing` needs a native query anyway, and the tables are
simple enough that an ORM adds more to learn than it saves.

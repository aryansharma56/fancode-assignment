# Design

## Overview

```
 mock-push-provider ──POST /webhooks/provider-a──►┐
   (Provider A, scripted scenarios)               │
                                                  ▼
                                      ┌───────────────────────┐        ┌────────────┐
                                      │ aggregator            │──SQL──►│ PostgreSQL │
                                      │  IngestionService     │        └────────────┘
                                      │  MatchViewService     │
 mock-poll-provider ◄──GET every 2s───│  ProviderBPoller      │◄── GET /matches/...  (clients)
   (Provider B, scripted timeline)    └───────────────────────┘
```

There is one Spring Boot process and one Postgres database. Both providers write through a single `IngestionService`, so the snapshot and revision rules are implemented once. Reads go through `MatchViewService`, which applies the provider precedence rule.

## Database choice

PostgreSQL. The core rules are "apply only if strictly newer" and "process a delivery once", and both can be expressed as a single atomic statement:

- `INSERT … ON CONFLICT … DO UPDATE … WHERE (stored.sequence, stored.revision) < (new.sequence, new.revision)`
- `INSERT … ON CONFLICT DO NOTHING`

Postgres compares row values lexicographically, so the SQL reads exactly like the spec. It is also durable, which covers the restart requirement, and runs locally in Docker. Access is plain `JdbcTemplate` with explicit SQL rather than JPA, so the correctness-critical statements are visible in one file (`IngestRepository`). Flyway owns the schema.

## Data model

| Table | Key | Purpose |
|---|---|---|
| `matches` | `id` | Client-facing matches (`match-1001`, seeded). |
| `provider_match_map` | `(provider, provider_match_id)` | `provider-a/a-match-1001` and `provider-b/b-match-1001` → `match-1001`. Seed data. |
| `provider_snapshots` | `(match_id, provider)` | The latest applied snapshot per provider: sequence, revision, status, innings, runs, wickets, overs (opaque string), provider time, received time. |
| `commentary` | `(match_id, provider, ball_id)` | One row per logical ball per provider, holding the newest applied revision. Indexed on `(match_id, provider, sequence, ball_id)` for ordered reads. |
| `processed_deliveries` | `(provider, delivery_id)` | Push idempotency markers, with a payload hash. |
| `provider_health` | `(match_id, provider)` | Last poll success and last error. Errors are written here and nowhere else. |

Each provider's data is stored separately, in the same internal shape. The client view is computed at read time. History is not kept: a correction overwrites the row. That is enough for the read API; an append-only event log is discussed under scaling.

## The rules, and where they live

- **Snapshot:** `IngestRepository.applySnapshot`.
  - The update fires only when `(stored.sequence, stored.revision) < (incoming.sequence, incoming.revision)`.
  - Results: `(5,1)` beats `(4,9)`, `(4,2)` beats `(4,1)`, and `(4,1)` does not beat `(4,1)`.
  - `occurredAt` and `asOf` are stored but never compared.
- **Commentary:** `IngestRepository.applyBall`.
  - The ball is inserted if new. If it already exists, the row is replaced only when `stored.revision < incoming.revision`.
  - A late ball is simply inserted. Display order comes from `ORDER BY sequence, ball_id` at read time, so a gap never delays anything, and the newer score is served immediately.
- **Validation:** bean validation on the payload DTOs. It checks required fields, `revision >= 1`, the known status values, and that runs and wickets are not negative. `over` and `ball` are not checked against `overs`.

## Concurrency

All write rules are enforced inside Postgres, so they do not depend on application locks or on a single process.

- **Same snapshot row, concurrent writers.** `ON CONFLICT DO UPDATE` takes a row lock. Under READ COMMITTED, Postgres re-checks the `WHERE` against the latest committed row, so the last writer cannot overwrite a newer tuple. If two writers race on the first insert, the unique index makes one of them wait and then take the update path.
- **Same deliveryId, concurrent deliveries.** The second `INSERT … ON CONFLICT DO NOTHING` blocks on the first transaction's uncommitted index entry. It then sees the conflict (0 rows) if the first commits, or inserts if the first rolls back. Exactly one is applied.
- **Lock order.** Every transaction locks in the same order (delivery marker, then snapshot, then ball), so these writes cannot deadlock against each other.
- **Tests.** `ConcurrencyTest` fires 60 shuffled deliveries, plus 30 copies of one deliveryId, from 16 threads, and asserts the rule-defined end state.
- **Single-instance assumption.** Only the **poller** assumes one process. Two aggregator instances would both poll Provider B. That is harmless for correctness, because the rules are idempotent, but it wastes calls. See scaling.

## Idempotency

- **Push.** `(provider, deliveryId)` is inserted into `processed_deliveries` **in the same transaction** as the snapshot and ball writes. A repeat gets `200 {"result":"duplicate"}` and changes nothing. If a deliveryId is reused with a different body, the first body wins and a warning is logged.
- **Defence in depth.** Even without the marker, re-applying the same event is a no-op, because the tuple and revision checks reject it. The marker matters for a delivery whose snapshot was superseded in the meantime. It also makes "duplicate" visible to the provider.
- **Poll.** There are no delivery ids. Idempotency comes entirely from the tuple and revision rules. Polling an unchanged body updates zero rows, and `PollIngestionTest` compares full table contents before and after.

## Webhook contract (`POST /webhooks/provider-a`)

| Response | Meaning | Provider should |
|---|---|---|
| `200 {"result":"accepted", snapshotApplied, commentaryApplied}` | The delivery and its effects are committed. "Applied: false" means the data was stale. That is not an error. | Stop |
| `200 {"result":"duplicate"}` | This deliveryId was committed earlier. Nothing changed. | Stop |
| `400` | Malformed or invalid payload | Not retry. Fix the payload. |
| `422` | Unknown `providerMatchId` | Not retry |
| `5xx`, timeout, connection reset | Outcome unknown | Retry with the **same** deliveryId, with backoff |

A 200 is written only after the transaction commits. Crash cases:

- **Crash before the commit.** Nothing is stored, and the retry is processed normally.
- **Crash after the commit but before the 200 reaches the provider.** The provider retries, gets `duplicate`, and stops.

Either way the provider ends with exactly-once effects. The mock push generator follows this contract.

## Push vs poll

| | Push (A) | Poll (B) |
|---|---|---|
| Trigger | Provider decides when | We decide (fixed delay, 2s, no overlapping polls) |
| Unit | One event: optional snapshot and optional ball | Full current score; full commentary list |
| Dedupe | deliveryId marker plus tuple/revision rules | Tuple/revision rules only |
| Errors | The provider retries on 5xx | We record the error in `provider_health`, keep the last good state, and try again next tick |
| Gaps | Possible (reordering) and handled by sequence ordering | Usually none, since each poll returns the full list |
| Cost | Cheap per event | The whole list on every poll. Fine for one match; a cursor would be the next step. |

Score and commentary are polled and applied independently, so a 503 on one does not block the other. Malformed or invalid poll bodies are treated like a 503.

## Provider precedence and conflicts

The rule is a **configured primary provider** (`PRIMARY_PROVIDER`, default `provider-a`), then the rest alphabetically.

- **Score:** taken from the highest-ranked provider that has any stored snapshot.
- **Commentary:** taken from the highest-ranked provider that has at least one stored ball.
- **No merging.** Lists from different providers are never combined. Ball ids and sequences are provider-local, so merging would need fuzzy matching by innings, over and ball, and would risk showing the same ball twice.
- **No cross-provider sequence comparison.** Sequence numbers are never compared between providers (`PrecedenceTest` gives B a much larger sequence and A still wins).
- **Source field.** Responses carry a `source` field so a client can see which provider it is reading.
- **Outage behaviour.** If the primary goes quiet or errors, its last good state keeps being served. This is allowed by the spec.
- **Failover (not built).** Automatic failover would track the primary's freshness (`received_at`, `provider_health`). It would switch the score source to the secondary when the primary has been stale for more than N seconds while the secondary keeps advancing, and switch back with hysteresis. Switching sources can make the visible score move backwards, so the UI would need to show the source change.

## Failure recovery

- **Aggregator crash or restart.** All state is in Postgres. On boot, Flyway sees the schema is current and reads continue (`RestartTest` starts the app twice against one database). The poller simply resumes, and Provider A retries anything that did not get a 200.
- **Provider B error or timeout.** Connect and read timeouts are 1s and 2s. The error is recorded and the stored data is untouched (`PollIngestionTest.providerErrorLeavesLastGoodStateInPlace`). `GET /matches/{id}/providers` shows `lastError` and `lastSuccessAt`.
- **Database down.** The webhook returns 5xx, so the provider retries. Polls fail and are retried on the next tick. Reads fail. Nothing is half-written, because each delivery is one transaction.

## Caching

Nothing is cached in-process. Every read is a primary-key lookup or a small indexed range scan (one match, tens of balls), so Postgres serves them in well under a millisecond and there is no invalidation to get wrong. For heavy read traffic, see the scaling section.

## Extra endpoint

`GET /matches/{id}/providers` shows, per provider, the stored tuple, the ball count and the poll health. It exists so a reviewer or operator can see why the client view shows what it shows, for example the primary being stale or B returning 503.

## First bottleneck

- **Read path.** Every client poll of `/score` and `/commentary` hits Postgres, and commentary re-serialises the full list each time. With many viewers of one hot match, database connections and JSON encoding saturate before anything else.
- **Write path.** The single `provider_snapshots` row per match and provider is a hot row. All writes for a match serialise on its lock. That is fine at cricket event rates (a few per minute), but it is the ceiling.

## Hundreds of live matches and heavy reads

- **Reads.**
  - Build the client view once per change, not once per request. After each applied write, publish `match-updated(matchId)` and have one component rebuild the score and commentary JSON into Redis or an in-memory cache.
  - Serve reads from the cache, with a short CDN TTL (1–2s) for `/score`. Add `ETag` and `If-None-Match`.
  - Paginate commentary, using the latest N balls plus `?beforeSequence=` cursors.
  - Push updates to clients over SSE or WebSocket through a fan-out tier, instead of having millions of clients poll.
- **Writes.**
  - Put a durable queue (Kafka or SQS) behind a thin webhook that acks after enqueueing. Partition by match id so one consumer applies a match's events in order, which also removes row-lock contention.
  - Keep the same conditional upserts in the consumer, so correctness does not depend on ordering in the queue.
  - Keep an append-only raw event log for audit, replay and long correction chains.
- **Polling.**
  - Shard pollers by match (consistent hashing or a lease table) so each match is polled by exactly one instance.
  - Use cursors or `If-Modified-Since` so a poll doesn't transfer the whole list.
  - Add jittered backoff and a circuit breaker per provider.
- **Database.** Partition commentary by match or date, and add read replicas for the rebuild jobs. Old completed matches move to cold storage.
- **Operations.** Track metrics for lag (`received_at - occurredAt`), stale-tuple rate, duplicate rate and poll error rate, with alerts when the primary falls behind the secondary. These metrics would also drive automatic failover.

## Assumptions

- The client-facing match id is `match-1001`. It and the provider mapping are seed data (Flyway `V2`).
- Before any snapshot arrives, a match reports `status: "scheduled"` with no score, and commentary is `[]`.
- A commentary correction may also change the ball's `sequence` and its descriptive fields. The newest revision's values are stored.
- Equal sequences between two balls of the same provider are ordered by `ballId`, so the order is deterministic.
- `deliveryId` is unique per provider, not globally.
- The webhook is unauthenticated, as allowed by the assignment. In production it would verify an HMAC signature.
- Correctness of writes does not assume a single process. Running exactly one poller does.

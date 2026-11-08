# Live Sports Match Data Aggregator

This service ingests one live cricket match from two providers and serves one read API:

- **Provider A** pushes webhooks.
- **Provider B** is polled.

It handles retries, duplicates, reordering, delays, corrections and provider errors, and its state survives restarts.

- **Stack:** Java 21, Spring Boot 3.3, PostgreSQL 16, Flyway, Docker Compose.
- **Tests:** JUnit 5 with Testcontainers and WireMock.
- **Design notes:** see [DESIGN.md](DESIGN.md) for the data model, concurrency, idempotency, precedence, failure handling and scaling.

| Module | What it is | Port |
|---|---|---|
| `aggregator` | Webhook receiver, Provider B poller, Postgres storage, client read API | 8080 |
| `mock-poll-provider` | Provider B: poll endpoints serving a scripted, step-controlled match | 8081 |
| `mock-push-provider` | Provider A: a run-once generator that POSTs a scripted scenario to a webhook URL | n/a |

The client-facing match id is **`match-1001`**. The seed data maps `provider-a/a-match-1001` and `provider-b/b-match-1001` to it.

## Prerequisites

- Docker Desktop (or Docker Engine) with Compose v2.
- To run the tests outside Docker: JDK 21. The Maven wrapper is included, so a separate Maven install is not needed. The tests use Testcontainers, so Docker must be running.

## Run everything

```bash
docker compose up --build -d
```

This starts the following containers:

- `postgres`, on host port 5433, with a named volume `pgdata`.
- `mock-poll-provider`, on 8081.
- `aggregator`, on 8080. It polls Provider B every 2s.

Check that it is up:

```bash
curl http://localhost:8080/matches
```

The aggregator primary is **Provider A**. Until A sends anything, the score and commentary shown come from Provider B (the fallback).

### Send Provider A's push scenario

```bash
docker compose run --rm mock-push-provider
```

This runs the `worked-example` scenario, which is the assignment's worked example. The deliveries are:

1. d1
2. d2
3. d2 repeated
4. d4 (out of order)
5. d3 (late, with an older snapshot)
6. d5 (correction of ball b2)
7. d6, a genuine score correction with snapshot `(4,2)`

After it runs, the expected state is score **11/1 (1.1)** and commentary **b1, b2, b3, b4**, with b2 at revision 2.

A longer scenario, with status changes, a snapshot-only event and a commentary-only correction, continues from there:

```bash
docker compose run --rm mock-push-provider --scenario=match-timeline
```

Any JSON file with the same shape can be sent with `--scenario=/path/in/container.json`. Other options are `--target-url=...`, `--delay-ms=...` and `--max-attempts=...`. The generator prints each delivery's note and the aggregator's response.

### Step Provider B's polled match

The mock serves a fixed script and moves forward only when told to. That keeps runs deterministic.

```bash
curl http://localhost:8081/provider/admin/state
```
```bash
curl -X POST http://localhost:8081/provider/admin/step
```
```bash
curl -X PUT http://localhost:8081/provider/admin/step/4
```
```bash
curl -X POST http://localhost:8081/provider/admin/reset
```

The script steps are:

1. initial state
2. unchanged
3. newer score and a new ball
4. **503 on both endpoints**
5. **stale snapshot `(10,1)` with a newer `asOf`**
6. wicket
7. commentary correction
8. gap (ball 5.4 before 5.3)
9. late ball 5.3
10. score correction `(14,2)`
11. abandoned

To auto-advance every 5s instead, start the stack with `MOCK_AUTO_ADVANCE_MS=5000 docker compose up -d`.

Provider B's raw endpoints:

```bash
curl http://localhost:8081/provider/matches/b-match-1001/score
```
```bash
curl http://localhost:8081/provider/matches/b-match-1001/commentary
```

To see Provider B in the client view while A is primary, look at the `/providers` endpoint below. Alternatively, run with `PRIMARY_PROVIDER=provider-b docker compose up -d` (or simply don't run the push scenario).

### Demonstrate a provider error and a restart

1. Jump Provider B to its 503 step:
   ```bash
   curl -X PUT http://localhost:8081/provider/admin/step/4
   ```
2. Check the providers view. `provider-b.health.lastError` shows the 503, and its stored snapshot is unchanged:
   ```bash
   curl http://localhost:8080/matches/match-1001/providers
   ```
3. Restart the aggregator:
   ```bash
   docker compose restart aggregator
   ```
4. Read the score again. The same score, status and commentary are served:
   ```bash
   curl http://localhost:8080/matches/match-1001/score
   ```

### Reset or stop

```bash
docker compose down -v
```

`-v` also wipes the database volume. Leave it off to keep the data.

## Client API

| Method & path | Response |
|---|---|
| `GET /matches` | `[{id, title, status, score: "11/1 (1.1 ov)", source}]`. `score` is omitted until a snapshot exists. |
| `GET /matches/{id}` | `{id, title, status, score: {...}}` |
| `GET /matches/{id}/score` | `{matchId, status, innings, runs, wickets, overs, summary, source, providerSequence, providerRevision, providerTime, updatedAt}`. Before any data: `{matchId, status: "scheduled"}`. |
| `GET /matches/{id}/commentary` | `{matchId, source, items: [{ballId, sequence, revision, innings, over, ball, text, occurredAt, updatedAt}]}`, ordered by sequence. `items` is `[]` before any ball. |
| `GET /matches/{id}/providers` | Debug view: each provider's stored tuple, ball count, and poll health (`lastSuccessAt`, `lastError`). |
| `POST /webhooks/provider-a` | Provider A webhook. Returns `200 {"result":"accepted"\|"duplicate", snapshotApplied, commentaryApplied}`, `400` invalid, `422` unknown match, `5xx` retry. See DESIGN.md. |

An unknown match id returns `404 {"error":"match_not_found"}`.

### curl examples

```bash
curl http://localhost:8080/matches
```
```bash
curl http://localhost:8080/matches/match-1001
```
```bash
curl http://localhost:8080/matches/match-1001/score
```
```bash
curl http://localhost:8080/matches/match-1001/commentary
```
```bash
curl http://localhost:8080/matches/match-1001/providers
```
```bash
curl -i http://localhost:8080/matches/does-not-exist
```

Send a single webhook by hand (bash):

```bash
curl -X POST http://localhost:8080/webhooks/provider-a -H "Content-Type: application/json" -d '{"provider":"provider-a","deliveryId":"manual-1","occurredAt":"2026-10-22T14:03:11.000Z","providerMatchId":"a-match-1001","snapshot":{"sequence":42,"revision":1,"status":"live","innings":1,"runs":98,"wickets":2,"overs":"12.4"},"commentary":{"ballId":"inn1-12.4","sequence":42,"revision":1,"innings":1,"over":12,"ball":4,"text":"Full and straight, driven to long-on for four."}}'
```

Send the same command again and the response is `"result":"duplicate"`.

## Tests

Docker must be running, because the tests start Postgres with Testcontainers.

```bash
./mvnw test
```

On Windows PowerShell, use `.\mvnw.cmd test`.

| Required case | Test |
|---|---|
| Ordered ingestion | `PushIngestionTest.orderedIngestion`, `fullWorkedExampleStepByStep` |
| Duplicate push delivery | `PushIngestionTest.duplicateDeliveryLeavesStateUnchanged`, `ConcurrencyTest.sameDeliveryIdProcessedConcurrentlyIsAppliedOnce` |
| Older snapshot after newer, late ball still in sequence order | `PushIngestionTest.olderSnapshotAfterNewerKeepsScoreButStoresLateBallInOrder` |
| Commentary correction | `PushIngestionTest.commentaryCorrectionReplacesTextAndKeepsOneBall`, `PollIngestionTest.pollCorrectionReplacesBallText` |
| Repeated identical polls | `PollIngestionTest.repeatedIdenticalPollsDoNotDuplicateOrChangeAnything` |
| Stale poll | `PollIngestionTest.stalePollWithNewerTimestampLosesToStoredTuple` |
| Provider error keeps last good state | `PollIngestionTest.providerErrorLeavesLastGoodStateInPlace`, `malformedOrInvalidResponseIsTreatedAsProviderError` |
| Restart against the same database | `RestartTest.storedStateSurvivesRestart` (boots the real app twice against one database) |

The suite also covers:

- score correction `(4,2)`, `(5,1)` beating `(4,9)`, and lower or equal revisions being ignored
- precedence and fallback, `404` responses, and the empty state
- concurrent writers converging on the newest tuple
- payload validation
- the mock timeline's required behaviours
- the push scenarios containing every required delivery pattern

## Running without Docker (optional)

1. Start a Postgres instance with database, user and password all set to `aggregator`. For example:
   ```bash
   docker compose up -d postgres
   ```
   This exposes it on port 5433.
2. Start the poll mock:
   ```bash
   ./mvnw -pl mock-poll-provider spring-boot:run
   ```
3. Start the aggregator, pointing it at that database (bash syntax):
   ```bash
   DB_URL=jdbc:postgresql://localhost:5433/aggregator ./mvnw -pl aggregator spring-boot:run
   ```
4. Run the push generator:
   ```bash
   ./mvnw -pl mock-push-provider spring-boot:run -Dspring-boot.run.arguments=--scenario=worked-example
   ```

## Key decisions (summary)

- **Ordering rules are in SQL.**
  - Snapshots use a conditional upsert: `WHERE (stored.sequence, stored.revision) < (new.sequence, new.revision)`.
  - Balls use a conditional upsert: `WHERE stored.revision < new.revision`.
  - Each rule is atomic and safe under concurrency without application locks.
- **Push idempotency.** The `(provider, deliveryId)` marker is committed in the same transaction as the effects. A 200 response means the delivery is durably stored.
- **Poll path.** It uses the same rules with no delivery ids. Errors are recorded in `provider_health` and never touch stored data.
- **Precedence.** There is a configured primary provider, and the secondary is the fallback while the primary has no data. Lists are never merged, and sequences are never compared across providers.
- **What is not built.** There is no automatic failover, no caching and no multi-instance polling. DESIGN.md explains how each would be added.

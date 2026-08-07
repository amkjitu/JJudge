# CodeArena

[![Build](https://img.shields.io/badge/build-passing-brightgreen)](#testing)
[![Java](https://img.shields.io/badge/Java-17-orange)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-brightgreen)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

A competitive-programming practice platform: browse problems, submit solutions, get
asynchronously judged verdicts, climb a leaderboard — and get told *what to solve next* by a
recommendation engine that models your per-topic proficiency.

> **Status: Phase 3 of 8 complete.** Domain model, migrations, seed data, a versioned REST API
> with RFC 7807 errors and OpenAPI docs, and JWT + OAuth2 authentication with rotating refresh
> tokens and per-user rate limiting — all running in Docker. The build order and what each
> phase adds is in [Roadmap](#roadmap).

<!-- TODO(phase-8): hero screenshot / GIF goes here -->
<!-- TODO(phase-8): live demo link + demo credentials -->

---

## Table of contents

- [Why this project](#why-this-project)
- [Architecture](#architecture)
- [Tech stack](#tech-stack)
- [Data model](#data-model)
- [The recommendation algorithm](#the-recommendation-algorithm)
- [Getting started](#getting-started)
- [API](#api)
- [Security](#security)
- [Testing](#testing)
- [Project structure](#project-structure)
- [Roadmap](#roadmap)
- [License](#license)

---

## Why this project

Most portfolio backends are a CRUD app with a login screen. This one has a genuine algorithm
at its centre — a recommender with a documented scoring function, a bounded-heap top-N
selection, and a topologically sorted prerequisite graph — plus an async event pipeline that
actually needs a message broker rather than having one bolted on for show.

---

## Architecture

```
                    ┌────────────────────┐
   Browser  ───────▶│   arena-api        │  Spring Boot (web + REST)
   (Thymeleaf UI)   │   Security / JWT   │
                    │   JPA  ├─▶ Postgres│  users, problems, submissions, stats
                    │        ├─▶ MongoDB │  statements, editorials, source code
                    │        └─▶ Redis   │  leaderboard cache, rate limits
                    └────────┬───────────┘
                             │ publish  SubmissionCreated
                             ▼
                        ┌─────────┐
                        │  Kafka  │
                        └────┬────┘
                             │ consume
                    ┌────────▼───────────┐
                    │  arena-judge       │  evaluates in a thread pool
                    └────────┬───────────┘
                             │ publish  VerdictAssigned  ──▶ back to arena-api ──▶ SSE
                    ┌────────────────────┐
                    │  arena-ai          │  Spring AI: hints, complexity analysis
                    └────────────────────┘
```

Four Maven modules: `arena-common` (shared enums and event contracts), `arena-api`,
`arena-judge`, `arena-ai`.

---

## Tech stack

| Area | Choice |
|---|---|
| Language / runtime | Java 17, Spring Boot 3.3.5 |
| Build | Multi-module Maven, Maven Wrapper (`./mvnw` — no local Maven needed) |
| Relational data | PostgreSQL 16, Spring Data JPA, Hibernate 6, Flyway migrations |
| Document data | MongoDB *(Phase 7)* |
| Cache / ranking | Redis sorted sets *(Phase 5)* |
| Messaging | Apache Kafka in KRaft mode *(Phase 6)* |
| Security | Spring Security 6, BCrypt, HS256 JWT access tokens, rotating opaque refresh tokens, OAuth2 Google |
| Web UI | Thymeleaf, Bootstrap 5, CodeMirror, Chart.js *(Phase 4)* |
| AI | Spring AI with Ollama, provider-configurable to OpenAI *(Phase 7)* |
| API | Versioned `/api/v1`, MapStruct DTO mapping, Bean Validation, RFC 7807 errors |
| API docs | springdoc-openapi at `/swagger-ui.html` |
| Testing | JUnit 5, AssertJ, Mockito, Testcontainers |
| Infra | Multi-stage Docker builds, Docker Compose, GitHub Actions *(Phase 8)* |

---

## Data model

**PostgreSQL** (owned by Flyway, `arena-api/src/main/resources/db/migration`):

| Table | Purpose |
|---|---|
| `users` | credentials, role, Elo-style `rating` |
| `problems` | title, slug, difficulty bucket, numeric `rating`, limits |
| `tags` | topic taxonomy (30 tags) |
| `problem_tags` | problem ↔ tag many-to-many |
| `tag_prerequisites` | directed edges of the prerequisite DAG over tags |
| `submissions` | one row per attempt: language, status, verdict, runtime |
| `user_tag_stats` | denormalised per-user, per-tag solved/attempt counters |
| `refresh_tokens` | hashed, revocable refresh tokens with rotation lineage |

Design notes worth calling out:

- **Enums are `varchar` + `CHECK`, not native PG enums.** Adding a value stays an ordinary
  migration instead of an `ALTER TYPE` that cannot run inside a transaction.
- **`submissions` has a cross-column check**: a verdict exists *exactly when* status is
  `DONE`. The illegal intermediate state is unrepresentable rather than merely discouraged.
- **`user_tag_stats` counts attempts per *problem*, not per submission.** Five failed
  submissions on one problem is one attempt — otherwise `solved / attempts` would measure
  stubbornness rather than proficiency.
- **Hibernate runs with `ddl-auto: validate`.** Any drift between entity mappings and the
  Flyway schema is a startup failure, which makes every integration test a mapping test too.

**MongoDB** *(Phase 7)*: `problem_statements`, `submission_sources`.
**Redis** *(Phase 5)*: `leaderboard:global` sorted set, submission rate-limit counters.

### Seed data

Migrations `V2`–`V5` seed 30 tags with their prerequisite edges, 40 problems spanning ratings
800–2200, and three demo users with deliberately different skill profiles so the recommender
produces visibly different output for each:

| User | Password | Rating | Profile |
|---|---|---|---|
| `admin` | `Admin123!` | 2100 | ADMIN role |
| `alice` | `Password123!` | 1150 | strong on arrays/strings, bounces off dp and graphs |
| `bob` | `Password123!` | 1450 | broad coverage, repeatedly fails dp and shortest-path |
| `carol` | `Password123!` | 1750 | strong nearly everywhere, weak on geometry |

These are demo credentials for a throwaway local database — not secrets.

`user_tag_stats` is *derived* in SQL from the seeded submissions rather than hand-written, so
the counters cannot drift from the history that justifies them. A test re-derives them and
asserts zero difference.

---

## The recommendation algorithm

> Implemented in **Phase 5**. The design is fixed and documented here up front because it is
> the point of the project; this section will gain the complexity table and benchmark once
> the code lands.

Given a user, return the top *N* problems they should attempt next.

1. **Tag-proficiency vector** — for each tag, `proficiency = solved / (attempts + k)`. The
   `+ k` is a Bayesian pseudo-count: it stops "1 solved out of 1" from outranking
   "18 solved out of 20".
2. **Candidate pool** — unsolved problems with `rating ∈ [userRating - 100, userRating + 200]`,
   filtered in SQL so the pool is small before scoring begins.
3. **Score** — `w₁·tagWeakness + w₂·ratingFit + w₃·recencyBoost − w₄·repetitionPenalty`,
   where `ratingFit` peaks at a mild stretch *above* the user's current rating.
4. **Top-N selection** — a bounded min-heap (`PriorityQueue` capped at N) giving
   **O(M log N)**, rather than sorting the whole pool at O(M log M).
5. **Diversity cap** — at most *c* results per tag, so the list is not five flavours of DP.
6. **Prerequisite gate** — a topological sort over `tag_prerequisites` ensures an advanced
   topic is never recommended before its prerequisites are established.

---

## Getting started

### Run the stack

```bash
cp .env.example .env
docker compose up -d --build
```

Then:

```bash
curl http://localhost:8080/actuator/health
```

Postgres is exposed on `localhost:5432` (`codearena` / `codearena`) so you can inspect the
seeded data directly.

Tear down, including the database volume:

```bash
docker compose down -v
```

### Local development

No local Maven or JDK install beyond Java 17 is required — the Maven Wrapper bootstraps
Maven itself.

```bash
./mvnw clean verify
```

---

## API

Interactive docs: **<http://localhost:8080/swagger-ui.html>** (spec at `/v3/api-docs`).

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | — | Create a local account, returns a token pair |
| `POST` | `/api/v1/auth/login` | — | Exchange credentials for a token pair |
| `POST` | `/api/v1/auth/refresh` | refresh token | Rotate: consumes the token, returns a new pair |
| `POST` | `/api/v1/auth/logout` | refresh token | Revoke a refresh token (idempotent) |
| `GET` | `/api/v1/auth/me` | bearer | The caller's own profile |

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/problems` | List/filter/search, paginated |
| `GET` | `/api/v1/problems/{slug}` | Problem detail |
| `GET` | `/api/v1/problems/{slug}/submissions` | Submissions against a problem |
| `POST` | `/api/v1/submissions` | Submit a solution → `201` + `Location` |
| `GET` | `/api/v1/submissions/{id}` | One submission |
| `GET` | `/api/v1/submissions/{id}/source` | Submitted source as `text/plain` |
| `GET` | `/api/v1/submissions/me` | Calling user's history |
| `GET` | `/api/v1/users/{username}` | Profile with per-topic proficiency |
| `GET` | `/api/v1/tags` | Topic taxonomy + prerequisite DAG |
| `GET` | `/api/v1/reports/tag-difficulty` | Aggregate report — **Spring JDBC, not JPA** |
| `POST`/`PUT`/`DELETE` | `/api/v1/admin/problems[/{slug}]` | Problem authoring |

```bash
curl -s "http://localhost:8080/api/v1/problems?tag=dp&difficulty=HARD&size=3"
```

```bash
curl -s "http://localhost:8080/api/v1/reports/tag-difficulty?sort=HARDEST&minProblems=2"
```

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' -d '{"usernameOrEmail":"bob","password":"Password123!"}' | python -c "import sys,json;print(json.load(sys.stdin)['accessToken'])")
```

```bash
curl -s -X POST http://localhost:8080/api/v1/submissions -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"problemSlug":"edit-distance","language":"JAVA","sourceCode":"class Main {}"}'
```

### Design decisions worth calling out

- **Errors are RFC 7807** `application/problem+json` with a stable `type` URI clients can
  branch on, plus an `errors` array of field-level violations. Framework failures — unreadable
  bodies, type mismatches, unknown sort properties — are reshaped into the same envelope
  rather than falling through to Boot's default error body.
- **Difficulty is never accepted from a client.** It is derived from `rating` via
  `Difficulty.fromRating`, so a problem labelled `EASY` at rating 2200 is unrepresentable.
- **Pagination has its own envelope.** Serialising Spring's `Page` directly would publish
  `Pageable`/`Sort` internals as API contract — a shape Spring itself warns is unstable.
- **Page size is capped at 100.** Without it, `?size=1000000` is free denial-of-service.
- **Services return DTOs, not entities.** See the note on `open-in-view` under
  [Testing](#a-bug-the-tests-were-hiding).
- **`ORDER BY` in the JDBC report comes from an enum, never from the request.** A bind
  variable is only legal where a *value* is expected, so a sortable column has to be
  interpolated — which makes a whitelist the only safe way to do it.

### Why one endpoint uses `JdbcTemplate`

`/api/v1/reports/tag-difficulty` is four CTEs of set-level aggregation returning a shape that
maps to no entity. It uses `FILTER (WHERE ...)`, `bool_or` and `NULLS LAST` — none of which
JPQL has vocabulary for. This is the case where dropping to SQL is simpler, faster and more
honest than bending an ORM around it, and Spring JDBC gives that up without giving up
connection management or exception translation.

One detail it gets right: an untouched tag returns `null` rates rather than `0.0`. "Nobody has
tried this" and "everybody who tried failed" are different facts and should not render alike —
which means using `ResultSet#wasNull()`, since `getDouble` silently turns SQL `NULL` into zero.

---

## Security

Two filter chains, because the API and the browser login flow want opposite things:

| Chain | Matches | Session | CSRF | Unauthenticated response |
|---|---|---|---|---|
| API | `/api/**` | stateless | off | `401` + `application/problem+json` |
| Web | everything else | as needed | on | `401`, or the OAuth2 redirect |

CSRF is disabled **only** on the stateless chain. That is safe precisely because
authentication rides in the `Authorization` header rather than a cookie — a cross-site form
post carries no credentials to forge. The browser chain keeps CSRF protection, because the
OAuth2 `state` parameter genuinely needs a session behind it.

### Access rules

| Area | Rule |
|---|---|
| Problems, tags, profiles, reports (`GET`) | public — browsing needs no account |
| Submissions | authenticated |
| `/api/v1/admin/**` | `ADMIN` — enforced by *both* the path matcher and `@PreAuthorize` |
| `/actuator/health`, `/actuator/info`, Swagger | public |
| Other actuator endpoints | `ADMIN` |

The admin rule is deliberately doubled up. Path matchers are easy to break by accident — a new
controller mapped one level up, a matcher that stops matching after a refactor — and method
security fails closed regardless of how a request was routed.

### Tokens

**Access tokens are JWTs** (HS256, 15 minutes), so authorization needs no database round trip.
Signed with a key the app refuses to start without, and validated for signature, expiry *and*
issuer — so a token minted by another service sharing the key is still rejected.

**Refresh tokens are not JWTs.** They are 256 bits of `SecureRandom`, stored as a SHA-256
digest, valid for 7 days. The reasoning: the entire point of a refresh token is that it can be
revoked, and a signed JWT cannot be — once issued it is valid until it expires no matter what
the server later decides. A database row gives you logout, rotation and reuse detection; the
access token stays stateless precisely because it is short-lived enough that revocation doesn't
matter.

SHA-256 rather than BCrypt for the digest is also deliberate: BCrypt's work factor exists to
slow brute-forcing of low-entropy human passwords, and a 256-bit random token has nothing to
brute-force. This hash runs on every single refresh.

**Rotation with reuse detection.** Every refresh consumes the presented token and issues a new
one, linked back through `replaced_by`. Presenting an already-rotated token has only one
consistent explanation — it leaked — so every live session for that account is revoked. This
costs a legitimate user one re-login in the rare genuinely-concurrent case, which is the right
side of that trade.

> **A bug this caught:** the revocation originally ran in the same transaction as the exception
> that reports it — so throwing rolled the revocation straight back, leaving the *attacker's*
> newer token live. It only shows up in a test that checks the other token afterwards. Fixed by
> running the revocation in a `REQUIRES_NEW` transaction.

### Other decisions

- **Federated accounts are matched on the provider's `sub`, never on email.** Email addresses
  get reassigned and can change; matching on them would mean anyone who could get Google to
  issue a token for an address equal to an existing account's email would take that account
  over. A Google identity whose email already belongs to a local account is refused rather than
  silently linked.
- **The database enforces the password/provider correspondence.** `password_hash` is nullable,
  with a `CHECK` requiring it exactly when `auth_provider = 'LOCAL'`. "Federated user with a
  password nobody set" is unrepresentable, not merely unlikely.
- **The OAuth2 callback delivers the refresh token in an `HttpOnly` cookie**, not a URL
  parameter or fragment — both of those write a long-lived credential into browser history, and
  a query parameter additionally leaks through `Referer` and access logs.
- **Login does not distinguish "no such user" from "wrong password".** That difference is a free
  account-enumeration oracle. A test asserts the two responses are byte-identical.
- **Validation errors redact sensitive fields.** Echoing the rejected value is genuinely useful
  ("you sent 99999, the max is 4000") and is also how a rejected password ends up in a response
  body and every log that aggregates it. Fields matching `password`/`secret`/`token`/
  `credential` report no value, and long values are truncated.
- **Google login is optional.** With no credentials configured the application starts normally
  and simply has no Google button — Boot's own auto-configuration would instead fail at startup
  on an empty `client-id`, which is what happens when Compose passes the variable through
  unset.
- **Rate limiting is a token bucket, keyed by user.** A fixed window would let a caller spend
  the whole quota at the end of one window and again at the start of the next. Keyed by user id
  rather than IP, because IP punishes everyone behind one NAT and is trivially evaded — which
  is why it runs as an interceptor (after authentication) rather than a filter.

---

## Testing

| Command | What runs | Needs Docker |
|---|---|---|
| `./mvnw test` | unit tests only (`*Test`) — 65 tests | no |
| `./mvnw verify` | unit **and** integration tests (`*IT`) — 179 tests | yes |

Integration tests use Testcontainers against a real PostgreSQL 16 image — never H2 — so
migrations, `CHECK` constraints, `FULL OUTER JOIN` and Postgres-specific SQL are all exercised
as they run in production. A single container is shared across the whole suite via the
singleton-container pattern rather than started per test class.

Three layers, each testing something the others cannot:

| Layer | Style | What it proves |
|---|---|---|
| Unit | plain JUnit + Mockito | business rules — difficulty derivation, tag resolution, proficiency smoothing, LRU eviction |
| Web slice | `@WebMvcTest`, mocked services | the HTTP contract — status codes, JSON shape, error envelope |
| Full stack | `@SpringBootTest` + Testcontainers | the Specification SQL, entity graphs, DB constraints, the real security chain, real session semantics |

Authorization tests are deliberately weighted towards the **negative** cases. A test proving an
admin can create a problem says nothing about whether everyone else can too, and that second
question is the one that matters.

### A bug the tests were hiding

The API integration tests were originally `@Transactional`, the usual way to get rollback
isolation. They all passed. The API then returned **HTTP 500** on `GET /api/v1/problems` the
first time it was exercised against the running container.

The cause: `spring.jpa.open-in-view` is disabled, so the persistence session closes when the
service method returns. Mapping an entity's lazy `tags` collection in the controller therefore
throws `LazyInitializationException` — except in a test, where the test's own transaction keeps
a session open for the whole request and the lazy load quietly succeeds.

Two changes came out of it:

1. **Services return DTOs, not entities.** Mapping happens inside the transaction, so the
   transaction boundary and the "what is loaded" boundary are the same line of code.
2. **`AbstractApiIT` is no longer `@Transactional`.** The tests now run with production session
   semantics and undo their own writes by id watermark instead. The trade-off is that a test
   must create whatever it intends to mutate rather than editing seeded rows — which is worth
   it to stop the suite lying about what production does.

What the suite covers:

- every Flyway migration applies successfully, in order
- seeded catalogue invariants: every problem tagged, every tag used, difficulty label agrees
  with numeric rating
- `user_tag_stats` re-derived from submissions matches the stored rows exactly
- the `tag_prerequisites` graph is acyclic — Kahn's algorithm must consume every node
- entity-graph fetching, pagination, composite keys and JPA auditing round-trip correctly
- database-level uniqueness is genuinely enforced, not just checked in Java
- proficiency smoothing behaviour, including the `0/0` case that would otherwise be `NaN`
- LIKE wildcards in a search term are escaped — searching `%` matches nothing, not everything
- the page-size cap is applied, and an unknown `?sort=` property is a 400 rather than a 500
- the reporting SQL's null semantics: untouched tags report `null`, not `0.0`
- `?sort=DROP TABLE users` is rejected before it can reach the query
- the full token lifecycle against real signing and decoding: login → bearer call → rotate →
  replay the rotated token → every session dies
- a forged signature, a foreign issuer and an expired token are each rejected
- registration never echoes the password back, in success *or* validation responses
- a refused admin write genuinely did not happen — asserted by reading the row back

### Note on Docker API versions

Testcontainers reaches the daemon through docker-java, which still negotiates Docker API
**1.32** by default. Docker Engine 25+ advertises a minimum API of **1.40** and replies to
anything older with a bare `HTTP 400` — which Testcontainers reports as the thoroughly
unhelpful *"Could not find a valid Docker environment"*, even though the daemon is running and
the CLI works fine.

docker-java reads this as a **system property**, not an environment variable, so
`DOCKER_API_VERSION` has no effect. The root `pom.xml` hands it to the forked test JVM instead:

```xml
<argLine>-Dapi.version=${docker.api.version}</argLine>
```

Override with `-Ddocker.api.version=…` if your engine needs something different.

---

## Project structure

```
.
├── arena-common/          shared enums and Kafka event contracts
├── arena-api/             web + REST + persistence
│   ├── src/main/java/com/codearena/api/
│   │   ├── config/        OpenAPI, JPA auditing
│   │   ├── domain/        JPA entities
│   │   ├── reporting/     Spring JDBC reporting DAO
│   │   ├── repository/    Spring Data repositories + Specifications
│   │   ├── service/       business logic, transaction boundaries
│   │   └── web/           controllers, DTOs, MapStruct mappers, error handling
│   └── src/main/resources/db/migration/   Flyway V1..V5
├── arena-judge/           Kafka worker (Phase 6)
├── arena-ai/              Spring AI service (Phase 7)
├── docs/screenshots/      README imagery
├── docker-compose.yml
└── pom.xml
```

---

## Roadmap

| Phase | Scope | Status |
|---|---|---|
| 1 | Multi-module scaffold, entities, migrations, seed data, repository tests | ✅ done |
| 2 | REST API, DTOs, validation, RFC 7807 errors, OpenAPI, `JdbcTemplate` report | ✅ done |
| 3 | Spring Security, JWT access/refresh, OAuth2 Google, rate limiting | ✅ done |
| 4 | Thymeleaf UI: Bootstrap, CodeMirror editor, Chart.js progress charts | ⬜ |
| 5 | Recommendation engine, Redis leaderboard | ⬜ |
| 6 | Kafka pipeline, `arena-judge` worker, SSE live verdicts | ⬜ |
| 7 | MongoDB statements/sources, `arena-ai` hints and complexity analysis | ⬜ |
| 8 | Full compose stack, GitHub Actions, docs, screenshots | ⬜ |

---

## License

[MIT](LICENSE)

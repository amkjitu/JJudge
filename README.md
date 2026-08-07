# CodeArena

[![Build](https://img.shields.io/badge/build-passing-brightgreen)](#testing)
[![Java](https://img.shields.io/badge/Java-17-orange)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-brightgreen)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

A competitive-programming practice platform: browse problems, submit solutions, get
asynchronously judged verdicts, climb a leaderboard — and get told *what to solve next* by a
recommendation engine that models your per-topic proficiency.

> **Status: Phase 1 of 8 complete.** Domain model, migrations, seed data and repository
> integration tests are done and running in Docker. The build order and what each phase adds
> is in [Roadmap](#roadmap).

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
| Security | Spring Security, BCrypt, JWT access/refresh, OAuth2 Google *(Phase 3)* |
| Web UI | Thymeleaf, Bootstrap 5, CodeMirror, Chart.js *(Phase 4)* |
| AI | Spring AI with Ollama, provider-configurable to OpenAI *(Phase 7)* |
| API docs | springdoc-openapi *(Phase 2)* |
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

## Testing

| Command | What runs | Needs Docker |
|---|---|---|
| `./mvnw test` | unit tests only (`*Test`) — 11 tests | no |
| `./mvnw verify` | unit **and** integration tests (`*IT`) — 45 tests | yes |

Integration tests use Testcontainers against a real PostgreSQL 16 image — never H2 — so
migrations, `CHECK` constraints, `FULL OUTER JOIN` and Postgres-specific SQL are all exercised
as they run in production. A single container is shared across the whole suite via the
singleton-container pattern rather than started per test class.

What the Phase 1 suite covers:

- every Flyway migration applies successfully, in order
- seeded catalogue invariants: every problem tagged, every tag used, difficulty label agrees
  with numeric rating
- `user_tag_stats` re-derived from submissions matches the stored rows exactly
- the `tag_prerequisites` graph is acyclic — Kahn's algorithm must consume every node
- entity-graph fetching, pagination, composite keys and JPA auditing round-trip correctly
- database-level uniqueness is genuinely enforced, not just checked in Java
- proficiency smoothing behaviour, including the `0/0` case that would otherwise be `NaN`

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
│   │   ├── domain/        JPA entities
│   │   └── repository/    Spring Data repositories
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
| 2 | REST API, DTOs, validation, RFC 7807 errors, OpenAPI, `JdbcTemplate` report | ⬜ |
| 3 | Spring Security, JWT access/refresh, OAuth2 Google, rate limiting | ⬜ |
| 4 | Thymeleaf UI: Bootstrap, CodeMirror editor, Chart.js progress charts | ⬜ |
| 5 | Recommendation engine, Redis leaderboard | ⬜ |
| 6 | Kafka pipeline, `arena-judge` worker, SSE live verdicts | ⬜ |
| 7 | MongoDB statements/sources, `arena-ai` hints and complexity analysis | ⬜ |
| 8 | Full compose stack, GitHub Actions, docs, screenshots | ⬜ |

---

## License

[MIT](LICENSE)

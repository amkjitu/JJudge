# Real judging

By default CodeArena does not execute submitted code. `SimulatedJudge` derives a verdict from a
hash of the submission text, which is why the stack is safe to expose publicly — there is no
untrusted execution anywhere in it.

`ARENA_JUDGE_MODE=REAL` replaces that with a judge that compiles and runs each submission against
real test cases in a locked-down container.

- [Read this before enabling it](#read-this-before-enabling-it)
- [Running it](#running-it)
- [What the sandbox guarantees](#what-the-sandbox-guarantees)
- [How a verdict is decided](#how-a-verdict-is-decided)
- [Test cases](#test-cases)
- [Limitations](#limitations)

---

## Read this before enabling it

**The sandbox protects the machine from submitted code. It does not protect the machine from
arena-judge.**

To create containers, arena-judge needs a Docker socket, and access to a Docker socket is
equivalent to root on the host that owns it. A flaw in arena-judge — or in anything that can
reach it — is a full compromise of that machine, regardless of how well the submissions
themselves are contained.

So:

- **Real judging is off by default**, on every axis. The mode defaults to `SIMULATED`, the
  sandbox bean is not created unless the mode is `REAL`, and the production overlay never turns
  it on.
- **Do not enable it on a machine you care about.** A laptop you can rebuild, or a throwaway VM
  dedicated to judging, is the right place. Not the box also serving the site.
- **Do not enable it on the public deployment.** The simulated judge exists precisely so the
  public demo needs none of this.

The compose file mounts the socket so that switching modes is one variable. If you want the
guarantee to be structural rather than configurational, comment out the `volumes` entry on
`arena-judge` — with no socket, `REAL` mode fails to start a sandbox and every submission falls
back to simulation.

---

## Running it

Build the runner image once:

```bash
docker build -t codearena/arena-runner:dev arena-judge/runner
```

Then start the stack with the mode set:

```bash
ARENA_JUDGE_MODE=REAL docker compose up -d --build
```

On Linux the socket is owned by the `docker` group rather than `root`, so set its gid:

```bash
ARENA_DOCKER_GID=$(getent group docker | cut -d: -f3) ARENA_JUDGE_MODE=REAL docker compose up -d
```

The judge keeps its unprivileged uid either way — group membership is what makes the socket
reachable, not running as root.

### Verifying the sandbox yourself

The isolation tests are opt-in, because they deliberately exhaust resources:

```bash
ARENA_SANDBOX_IT=1 ./mvnw -pl arena-judge -am verify
```

They assert that a fork bomb, a memory bomb, a network call, a filesystem write, an infinite
loop, a sleeping process and unbounded output all fail in the specific way each is supposed to.
A sandbox nobody has tried to break is a claim, not a result.

CI runs them on every pull request, which is where they belong — a disposable Linux runner
tolerates a fork bomb better than a laptop does.

Two things to expect when running them locally:

- **They leave the Docker daemon busy.** Eleven containers are created and destroyed, two of them
  deliberately exhausting their limits. If a container then fails to start with an unhelpful
  message, `docker system prune -f` and retry — that is a clogged daemon, not a failing test.
- **Stop the compose stack first.** The stack and the tests compete for the same memory, and on a
  small machine the tests lose.

---

## What the sandbox guarantees

One container per submission, created with:

| Control | Flag | Stops |
|---|---|---|
| No network | `--network none` | Exfiltrating test data, using the judge as a proxy |
| Read-only root | `--read-only` | Tampering with the image or leaving anything behind |
| Capped scratch space | `--tmpfs /work:...,size=` | Filling the host disk — it costs RAM, and only a few MB |
| Memory ceiling | `--memory` + equal `--memory-swap` | Exhausting host memory. Equal swap makes it a kill, not a slow slide into swap |
| Process ceiling | `--pids-limit` | Fork bombs |
| CPU quota | `--cpus` | One submission starving the others |
| Unprivileged | `--user`, `--cap-drop ALL`, `no-new-privileges` | Anything requiring a capability, and acquiring more |

`/work` is mounted `exec` — a compiled submission is a file there that has to run. That is the
one concession, and it is why the rest of the filesystem is read-only.

Commands run as argv and never through a shell. The one exception is writing the source file,
which pipes it into `cat` inside the container — the code travels on stdin and never appears in
argv, so there is nothing for a crafted submission to break out of. (`docker cp` was the obvious
choice and does not work: it refuses on a `--read-only` container even when the destination is
the writable tmpfs.)

---

## How a verdict is decided

Compile once, then run each test case in order, stopping at the first failure. Sequential rather
than parallel: every case is a real process sharing one CPU quota, and timing is a *verdict*
here, not a statistic — a submission that passes alone and fails when three of its own cases run
beside it would make the judge untrustworthy.

| Outcome | Meaning |
|---|---|
| `CE` | The compile step failed |
| `TLE` | Wall clock exceeded. Wall clock, not CPU time — a program that sleeps or deadlocks burns no CPU |
| `MLE` | Killed for exceeding the memory ceiling |
| `RTE` | Non-zero exit |
| `WA` | Ran cleanly, output did not match |
| `AC` | Every case matched |

Order matters: a program that runs out of time usually also produces incomplete output, so
checking output first would report `WA` for what is really `TLE`, and send someone rewriting a
correct answer instead of speeding it up.

Output comparison is line-based, ignoring trailing whitespace and trailing blank lines. Exact
byte matching would reject correct solutions over the newline `println` adds; collapsing internal
whitespace would accept answers no other judge accepts.

### What the clock is actually measuring

A problem's time limit describes an *algorithm*, and it is written against C++. Two things would
otherwise be charged to the submission that are not its fault:

**The judge's own round trip.** Every test case is a `docker exec`, and that costs real time
before the program starts. On a loaded host it can exceed the whole time limit — a no-op measured
2.7 s on the machine this was developed on. So it is measured rather than assumed: opening a
session times `/bin/true` in the same container three times, takes the *minimum* (the floor is the
true cost; anything above it is contention), and subtracts that from every case. The runtime shown
to a user is their program's, not the judge's.

**Runtime startup.** A JVM costs a few hundred milliseconds before `main` is entered, and CPython
taxes every loop iteration. Each language therefore carries a multiplier on the stated limit:

| Language | Multiplier | 2000 ms becomes |
|---|---|---|
| C++ | 1.0 | 2000 ms |
| Java | 2.0 | 4000 ms |
| Python | 3.0 | 6000 ms |

These are the conventional figures rather than anything measured here, and they are deliberately
coarse — the point is to stop punishing a language for being that language, not to equalise them.

This is not a nicety. Before it existed, a correct Java solution to `reverse-the-words` was marked
`TLE` at 2016 ms against a 2000 ms limit, having spent 1.7 s of that starting the JVM. A wrong
`TLE` is the worst verdict a judge can give: it is confident, it is specific, and it sends someone
to optimise code that was never slow.

The overhead allowance is capped at 5 s. A host thrashing badly enough to spend longer than that
starting a no-op is not one whose timings mean anything, and without the cap the judge would
quietly hand out minute-long budgets and call slow code correct.

### Languages

The runner image carries **Python**, **C++** and **Java**. A submission in any other language is
not judged and not given a verdict — reporting `CE` would tell someone their correct code does not
compile, which is false and sends them hunting for a bug they did not write.

Java is run with `-XX:+UseSerialGC -XX:MaxRAMPercentage=75 -Xss64m`, and none of those are
cosmetic. A parallel collector starts several GC threads for a program that lives half a second,
which costs more than it collects and eats into the PID limit. The JVM reads the cgroup memory
limit but defaults to a quarter of it, so a 256 MB container would give a 64 MB heap and fail
solutions well inside the stated limit. And deep recursion is ordinary here, so the default stack
overflows on inputs the problem explicitly allows.

One consequence worth knowing: a Java program that exhausts its heap throws `OutOfMemoryError` and
exits non-zero, so it is reported as `RTE` rather than `MLE`. `MLE` means the *container* was
killed. The distinction is real — the JVM died on its own limit, not the sandbox's — but it does
mean the same underlying mistake is labelled differently across languages.

---

## Test cases

Stored in MongoDB as `problem_test_cases`, one document per problem, keyed by slug. Generated
rather than hand-written:

```bash
python tools/generate_test_cases.py
```

Each problem has a reference solution in that script, and every expected output is that
solution's actual output. A wrong expected output is the worst defect a judge can have — it fails
correct submissions and there is nothing in the judge's own code to find — so the answers come
from running a known-good implementation rather than from somebody's typing.

The cross-check is enforced rather than intended: the generator refuses to write anything unless
every statement's worked examples appear as sample cases with the same output, and every problem
has both a statement and cases. A statement and its test data are edited at different times by
different hands, so they drift — and when they do, the platform shows someone one thing and marks
them against another. The check caught exactly that while the catalogue was being filled in.

**All 40 problems have statements and test cases**, so nothing falls back to simulation on a
complete database. A problem added without cases still would, with a warning in the judge's log
naming it, and its verdict labelled `SIMULATED` — see below.

## How a verdict says what it is

A verdict carries how it was reached: `EXECUTED` when the code was compiled and run, `SIMULATED`
when it was not. It travels on `VerdictAssigned`, lands in `submissions.judged_by`, appears on the
API response and is a badge on the submission page.

Without it the two are the same characters in the same column, and the reasonable assumption on
seeing a verdict is the stronger one. The column is nullable: rows that predate it are left
`NULL`, because "not recorded" is true and backfilling a value would be inventing history.

---

## Limitations

- **Three languages**, as above.
- **No interactive problems**, and no special checkers — a problem with several valid answers
  cannot be judged by string comparison.
- **Timing is not competition-grade.** Wall clock inside a container on a shared machine varies
  with load; a real contest judge pins CPUs and calibrates. Subtracting the measured round trip
  and scaling per language removes the two largest systematic errors, but what remains is still
  noise from whatever else the host is doing, and the language multipliers are conventions rather
  than measurements of this runner image.
- **`MLE` detection is a heuristic.** A container killed for memory and one killed another way
  both exit 137. Nothing else in a sandbox with no signals reaching it sends `SIGKILL`, so the
  inference is sound in practice, but it is an inference.
- **One machine.** There is no pool of judge nodes, so throughput is bounded by how many
  containers this host will run.

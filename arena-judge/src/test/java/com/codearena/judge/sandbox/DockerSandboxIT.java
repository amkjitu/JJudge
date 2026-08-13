package com.codearena.judge.sandbox;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adversarial tests for the sandbox.
 *
 * <p>Every claim the sandbox makes is a claim about what hostile code <em>cannot</em> do, and an
 * untested claim of that shape is worth nothing. So these do not check that a well-behaved
 * program runs; they check that a fork bomb, a memory bomb, a network call, a filesystem write
 * and an infinite loop all fail, in the specific way they are supposed to fail.
 *
 * <p>Requires a Docker daemon and the runner image:
 *
 * <pre>
 *   docker build -t codearena/arena-runner:dev arena-judge/runner
 *   ARENA_SANDBOX_IT=1 ./mvnw -pl arena-judge verify
 * </pre>
 *
 * <p>Opt-in via an environment variable rather than run by default, because these deliberately
 * exhaust resources. A true fork bomb was enough to take down Docker Desktop on the machine this
 * was developed on, so the limits below are set low - the point is to prove the ceiling exists,
 * and a ceiling of 8 proves that as well as a ceiling of 64 while costing far less.
 */
@EnabledIfEnvironmentVariable(named = "ARENA_SANDBOX_IT", matches = ".+",
        disabledReason = "needs a Docker daemon and the runner image; set ARENA_SANDBOX_IT=1")
@DisplayName("DockerSandbox isolation")
class DockerSandboxIT {

    private static final SandboxProperties PROPERTIES = new SandboxProperties(
            "codearena/arena-runner:dev",
            "docker",
            128,    // memory MB
            16,     // workspace MB
            8,      // pid limit - low on purpose, see the class comment
            1.0,    // cpus
            "10001:10001",
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            Duration.ofSeconds(30));

    private static final long MEMORY_BYTES = 128L * 1024 * 1024;
    private static final long MAX_OUTPUT = 1024L * 1024;

    private static DockerSandbox sandbox;

    @BeforeAll
    static void createSandbox() {
        sandbox = new DockerSandbox(PROPERTIES);
    }

    private ExecutionResult runPython(String script, Duration wallClock) {
        try (SandboxSession session = sandbox.open("it-" + System.nanoTime())) {
            session.writeFile("main.py", script);
            return session.run(new ExecutionRequest(
                    List.of("python3", "main.py"), "", wallClock, MEMORY_BYTES, MAX_OUTPUT));
        }
    }

    @Nested
    @DisplayName("a well-behaved program still works")
    class Baseline {

        @Test
        @DisplayName("runs, reads stdin and returns output")
        void happyPath() {
            try (SandboxSession session = sandbox.open("baseline-" + System.nanoTime())) {
                session.writeFile("main.py", "import sys\nprint(sys.stdin.read().strip().upper())");
                ExecutionResult result = session.run(new ExecutionRequest(
                        List.of("python3", "main.py"), "hello", Duration.ofSeconds(20),
                        MEMORY_BYTES, MAX_OUTPUT));

                assertThat(result.succeeded()).isTrue();
                assertThat(result.stdout().strip()).isEqualTo("HELLO");
            }
        }

        @Test
        @DisplayName("compiles and executes C++ from the workspace")
        void compilesAndRuns() {
            // The one concession in the hardening: /work is mounted exec, because a compiled
            // submission is a file there that has to run. This proves that still holds.
            try (SandboxSession session = sandbox.open("cpp-" + System.nanoTime())) {
                session.writeFile("main.cpp",
                        "#include <iostream>\nint main(){ std::cout << 6*7 << std::endl; }");

                ExecutionResult compiled = session.run(new ExecutionRequest(
                        List.of("g++", "-O2", "-o", "main", "main.cpp"), "",
                        Duration.ofSeconds(60), MEMORY_BYTES, MAX_OUTPUT));
                assertThat(compiled.succeeded()).as("compile: %s", compiled.stderr()).isTrue();

                ExecutionResult ran = session.run(new ExecutionRequest(
                        List.of("./main"), "", Duration.ofSeconds(20), MEMORY_BYTES, MAX_OUTPUT));
                assertThat(ran.stdout().strip()).isEqualTo("42");
            }
        }
    }

    @Nested
    @DisplayName("hostile code is contained")
    class Containment {

        @Test
        @DisplayName("no network: a submission cannot phone home")
        void networkIsUnreachable() {
            // The one that matters most for a public deployment. A submission that can open a
            // socket can exfiltrate the test data, or use the judge as a proxy.
            ExecutionResult result = runPython("""
                    import socket
                    try:
                        socket.create_connection(('1.1.1.1', 53), timeout=5)
                        print('REACHED')
                    except Exception as e:
                        print('blocked')
                    """, Duration.ofSeconds(30));

            assertThat(result.stdout()).contains("blocked").doesNotContain("REACHED");
        }

        @Test
        @DisplayName("the root filesystem is read-only")
        void filesystemIsReadOnly() {
            ExecutionResult result = runPython("""
                    try:
                        open('/etc/passwd', 'a').write('x')
                        print('WROTE')
                    except Exception:
                        print('blocked')
                    """, Duration.ofSeconds(30));

            assertThat(result.stdout()).contains("blocked").doesNotContain("WROTE");
        }

        @Test
        @DisplayName("a fork bomb hits the process ceiling instead of the host")
        void forkBombIsCapped() {
            ExecutionResult result = runPython("""
                    import os
                    kids = 0
                    for _ in range(200):
                        try:
                            if os.fork() == 0:
                                os._exit(0)
                            kids += 1
                        except OSError:
                            print('capped at', kids)
                            break
                    else:
                        print('UNCAPPED', kids)
                    """, Duration.ofSeconds(60));

            assertThat(result.stdout()).contains("capped at").doesNotContain("UNCAPPED");
        }

        @Test
        @DisplayName("a memory bomb is killed rather than swapped")
        void memoryBombIsKilled() {
            // --memory-swap equal to --memory is what makes this a kill. Without it the process
            // swaps instead of dying and takes the wall clock with it, which would surface as a
            // confusing TLE rather than an honest MLE.
            ExecutionResult result = runPython("""
                    x = bytearray()
                    while True:
                        x.extend(b'0' * 5_000_000)
                    """, Duration.ofSeconds(60));

            assertThat(result.succeeded()).isFalse();
            assertThat(result.timedOut())
                    .as("should die on memory, not on the clock")
                    .isFalse();
            assertThat(result.outOfMemory()).isTrue();
        }

        @Test
        @DisplayName("an infinite loop is killed on the wall clock")
        void infiniteLoopTimesOut() {
            ExecutionResult result = runPython("while True: pass", Duration.ofSeconds(3));

            assertThat(result.timedOut()).isTrue();
            assertThat(result.succeeded()).isFalse();
        }

        @Test
        @DisplayName("a program that sleeps is still killed, which a CPU limit would miss")
        void sleepingProcessTimesOut() {
            // Wall clock rather than CPU time, deliberately: this consumes no CPU at all and
            // would run for ever under a CPU-time limit.
            ExecutionResult result = runPython("import time\ntime.sleep(600)", Duration.ofSeconds(3));

            assertThat(result.timedOut()).isTrue();
        }

        @Test
        @DisplayName("unbounded output is truncated rather than filling the judge's memory")
        void runawayOutputIsTruncated() {
            ExecutionResult result = runPython("""
                    import sys
                    line = 'x' * 1000
                    for _ in range(100000):
                        sys.stdout.write(line)
                    """, Duration.ofSeconds(30));

            assertThat(result.outputTruncated()).isTrue();
            assertThat(result.stdout().length()).isLessThanOrEqualTo((int) MAX_OUTPUT);
        }

        @Test
        @DisplayName("the workspace is size-capped, so filling the disk costs a few megabytes")
        void workspaceIsCapped() {
            ExecutionResult result = runPython("""
                    try:
                        with open('/work/big', 'wb') as f:
                            for _ in range(200):
                                f.write(b'0' * 1_000_000)
                        print('WROTE ALL')
                    except Exception:
                        print('capped')
                    """, Duration.ofSeconds(60));

            assertThat(result.stdout()).contains("capped").doesNotContain("WROTE ALL");
        }

        @Test
        @DisplayName("the process is unprivileged")
        void runsUnprivileged() {
            ExecutionResult result = runPython("import os\nprint(os.getuid())",
                    Duration.ofSeconds(30));

            assertThat(result.stdout().strip()).isEqualTo("10001");
        }
    }
}

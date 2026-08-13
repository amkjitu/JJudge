package com.codearena.judge.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A {@link Sandbox} backed by one hardened Docker container per submission.
 *
 * <h2>The trust boundary, stated plainly</h2>
 *
 * <p>This talks to a Docker daemon, which means whoever runs it can create containers - and
 * access to a Docker socket is equivalent to root on the host that owns it. The sandbox protects
 * the machine from the <em>submitted code</em>; it does not protect the machine from
 * <em>arena-judge</em>. That is why real execution is off by default and stays off in the
 * production overlay: enable it only where you accept that arena-judge is as privileged as root.
 *
 * <h2>How one submission is isolated</h2>
 *
 * <p>A single container is created per submission and every step runs inside it, so a compile
 * can produce a binary the test-case runs reuse. It is created with:
 *
 * <ul>
 *   <li>{@code --network none} - no interface at all, not merely no route out.</li>
 *   <li>{@code --read-only} with a size-capped {@code tmpfs} on {@code /work}. Nothing on disk
 *       is writable, and filling the scratch area costs a few megabytes of RAM rather than the
 *       host's disk. The tmpfs is mounted {@code exec} because a compiled submission has to be
 *       executable, which is the one concession here.</li>
 *   <li>{@code --memory} with {@code --memory-swap} set equal to it, so exceeding the limit is
 *       a kill rather than a slide into swap that never finishes.</li>
 *   <li>{@code --pids-limit} - a fork bomb exhausts its own allowance and nothing else.</li>
 *   <li>{@code --cpus} so one submission cannot starve the others.</li>
 *   <li>{@code --user}, {@code --cap-drop ALL} and {@code --security-opt no-new-privileges} -
 *       unprivileged, with no capabilities and no way to acquire any.</li>
 * </ul>
 *
 * <p>Commands are executed as argv, never through a shell. The one exception is writing the
 * source file, which pipes content into {@code cat} over stdin - the code never appears in argv,
 * so there is nothing for a crafted submission to break out of. See {@code writeFile}.
 */
public class DockerSandbox implements Sandbox {

    private static final Logger log = LoggerFactory.getLogger(DockerSandbox.class);

    /** Exit code for a process killed by SIGKILL, which is how the memory limit presents. */
    private static final int SIGKILL_EXIT = 137;

    private final SandboxProperties properties;

    public DockerSandbox(SandboxProperties properties) {
        this.properties = properties;
    }

    @Override
    public SandboxSession open(String workspaceName) {
        String container = "arena-run-" + workspaceName + "-" + UUID.randomUUID();

        List<String> create = new ArrayList<>(List.of(
                properties.dockerBinary(), "run", "--detach",
                "--name", container,
                "--network", "none",
                "--read-only",
                // exec is required: a compiled submission is a file in here that must run.
                "--tmpfs", "/work:rw,exec,size=" + properties.workspaceMegabytes() + "m,mode=1777",
                "--memory", properties.memoryMegabytes() + "m",
                // Equal to --memory means no swap at all. Without this a program over the limit
                // swaps instead of dying and takes the wall clock with it.
                "--memory-swap", properties.memoryMegabytes() + "m",
                "--pids-limit", String.valueOf(properties.pidLimit()),
                "--cpus", String.valueOf(properties.cpus()),
                "--user", properties.runAsUser(),
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                // A submission that opens files until it runs out of descriptors should hit its
                // own ceiling rather than the host's.
                "--ulimit", "nofile=256:256",
                "--workdir", "/work",
                properties.runnerImage(),
                // Keeps the container alive between steps. Bounded, so a leaked container
                // disappears on its own rather than living until somebody notices.
                "sleep", String.valueOf(properties.sessionLifetime().toSeconds())));

        ExecutionResult started = runHost(create, "", properties.dockerCommandTimeout(), 64 * 1024);
        if (!started.succeeded()) {
            throw new SandboxUnavailableException(
                    "Could not start a sandbox container: " + started.stderr().strip());
        }
        return new DockerSession(container);
    }

    private final class DockerSession implements SandboxSession {

        private final String container;

        private DockerSession(String container) {
            this.container = container;
        }

        /**
         * Writes a file by piping it into {@code cat} inside the container.
         *
         * <p>Not {@code docker cp}, which was the obvious choice and does not work: it refuses
         * with "container rootfs is marked read-only" even when the destination is the writable
         * tmpfs, because it checks the rootfs rather than the target mount. That only surfaced
         * when a submission was judged end to end.
         *
         * <p>The shell here is safe despite handling untrusted content: the source code travels
         * on <em>stdin</em> and never appears in argv, so there is nothing for it to break out
         * of. Only {@code relativePath} is interpolated, and that comes from
         * {@code LanguageToolchain}'s own constants - never from a submission.
         */
        @Override
        public void writeFile(String relativePath, String content) {
            if (!relativePath.matches("[A-Za-z0-9._-]+")) {
                // Defence against a future caller passing something derived from user input.
                throw new IllegalArgumentException("Unsafe workspace filename: " + relativePath);
            }

            ExecutionResult written = runHost(
                    List.of(properties.dockerBinary(), "exec", "--interactive",
                            "--user", properties.runAsUser(),
                            "--workdir", "/work",
                            container,
                            "sh", "-c", "cat > " + relativePath),
                    content, properties.dockerCommandTimeout(), 64 * 1024);

            if (!written.succeeded()) {
                throw new SandboxUnavailableException(
                        "Could not place " + relativePath + ": " + written.stderr().strip());
            }
        }

        @Override
        public ExecutionResult run(ExecutionRequest request) {
            List<String> argv = new ArrayList<>(List.of(
                    properties.dockerBinary(), "exec",
                    // stdin must be attached: test-case input arrives that way.
                    "--interactive",
                    "--user", properties.runAsUser(),
                    "--workdir", "/work",
                    container));
            argv.addAll(request.command());

            ExecutionResult result = runHost(argv, request.stdin(), request.wallClock(),
                    request.maxOutputBytes());

            if (result.timedOut()) {
                // The docker client has been killed, but the process inside the container has
                // not. Without this the submission keeps running - still holding CPU and memory -
                // until the session's own lifetime expires.
                killEverythingInside();
            }
            return result;
        }

        /**
         * Restarting the container is a blunt instrument that reliably kills whatever is inside,
         * including anything a fork bomb spawned, without needing to enumerate it.
         */
        private void killEverythingInside() {
            runHost(List.of(properties.dockerBinary(), "restart", "--time", "0", container),
                    "", properties.dockerCommandTimeout(), 4096);
        }

        @Override
        public void close() {
            ExecutionResult removed = runHost(
                    List.of(properties.dockerBinary(), "rm", "--force", "--volumes", container),
                    "", properties.dockerCommandTimeout(), 4096);

            // Never throws: cleanup failing must not turn a completed judgement into a failed
            // one. It is logged because a leak here is cumulative and worth noticing.
            if (!removed.succeeded()) {
                log.warn("Could not remove sandbox container {}: {}",
                        container, removed.stderr().strip());
            }
        }
    }

    /**
     * Runs a command on the host - the docker client itself, never a submission.
     *
     * <p>Streams go to temporary files rather than pipes. A process that writes more than the
     * pipe buffer while nobody is reading blocks for ever, and the classic version of this bug
     * is a judge that hangs only on submissions which print a lot.
     */
    private ExecutionResult runHost(List<String> argv, String stdin, Duration timeout,
                                    long maxOutputBytes) {
        Path in = null;
        Path out = null;
        Path err = null;
        long startedAt = System.nanoTime();

        try {
            in = Files.createTempFile("arena-stdin-", ".tmp");
            out = Files.createTempFile("arena-stdout-", ".tmp");
            err = Files.createTempFile("arena-stderr-", ".tmp");
            Files.writeString(in, stdin, StandardCharsets.UTF_8);

            Process process = new ProcessBuilder(argv)
                    .redirectInput(in.toFile())
                    .redirectOutput(out.toFile())
                    .redirectError(err.toFile())
                    .start();

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            long elapsed = (System.nanoTime() - startedAt) / 1_000_000;

            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return new ExecutionResult(SIGKILL_EXIT, readCapped(out, maxOutputBytes),
                        readCapped(err, 8192), elapsed, true, false, false);
            }

            int exitCode = process.exitValue();
            long produced = Files.size(out);
            String stdout = readCapped(out, maxOutputBytes);

            // A container killed for exceeding memory and one killed any other way both report
            // 137, so this is a heuristic rather than a fact. It is the right guess: nothing else
            // in a sandbox with no signals reaching it sends SIGKILL.
            boolean outOfMemory = exitCode == SIGKILL_EXIT;

            return new ExecutionResult(exitCode, stdout, readCapped(err, 8192), elapsed,
                    false, outOfMemory, produced > maxOutputBytes);

        } catch (IOException e) {
            throw new SandboxUnavailableException("Could not run " + argv.get(0), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxUnavailableException("Interrupted while running " + argv.get(0), e);
        } finally {
            deleteQuietly(in);
            deleteQuietly(out);
            deleteQuietly(err);
        }
    }

    private String readCapped(Path file, long maxBytes) throws IOException {
        byte[] all = Files.readAllBytes(file);
        int length = (int) Math.min(all.length, maxBytes);
        return new String(all, 0, length, StandardCharsets.UTF_8);
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not remove temporary file {}: {}", path, e.getMessage());
        }
    }
}

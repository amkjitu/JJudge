package com.codearena.judge.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Limits applied to every sandboxed submission.
 *
 * <p>The defaults suit a laptop running a demo. Raising them raises what a single hostile
 * submission can consume, so each one is a decision rather than a knob to turn up until things
 * stop failing.
 *
 * @param runnerImage      image submissions execute in, built from arena-judge/runner
 * @param dockerBinary     the docker client. Configurable because a judge VM may install it
 *                         somewhere unusual, and to make the tests able to point elsewhere
 * @param memoryMegabytes  container memory ceiling; exceeding it is a kill reported as MLE
 * @param workspaceMegabytes size of the tmpfs on /work - the only writable path
 * @param pidLimit         maximum processes. The fork-bomb ceiling
 * @param cpus             CPU quota, so one submission cannot starve the others
 * @param runAsUser        uid:gid inside the container. Matches the runner image's user
 * @param compileTimeout   wall clock for the compile step, which is generous relative to a run:
 *                         g++ on a cold container is slow and that is not the submission's fault
 * @param sessionLifetime  how long a session's container may live before it exits by itself, so
 *                         a leaked container disappears rather than lingering
 * @param dockerCommandTimeout bound on the docker client itself for bookkeeping commands
 */
@ConfigurationProperties(prefix = "arena.judge.sandbox")
public record SandboxProperties(String runnerImage,
                                String dockerBinary,
                                int memoryMegabytes,
                                int workspaceMegabytes,
                                int pidLimit,
                                double cpus,
                                String runAsUser,
                                Duration compileTimeout,
                                Duration sessionLifetime,
                                Duration dockerCommandTimeout) {

    public SandboxProperties {
        runnerImage = orDefault(runnerImage, "codearena/arena-runner:dev");
        dockerBinary = orDefault(dockerBinary, "docker");
        runAsUser = orDefault(runAsUser, "10001:10001");
        memoryMegabytes = memoryMegabytes <= 0 ? 256 : memoryMegabytes;
        workspaceMegabytes = workspaceMegabytes <= 0 ? 64 : workspaceMegabytes;
        pidLimit = pidLimit <= 0 ? 64 : pidLimit;
        cpus = cpus <= 0 ? 1.0 : cpus;
        compileTimeout = compileTimeout == null ? Duration.ofSeconds(20) : compileTimeout;
        sessionLifetime = sessionLifetime == null ? Duration.ofMinutes(5) : sessionLifetime;
        dockerCommandTimeout = dockerCommandTimeout == null
                ? Duration.ofSeconds(30) : dockerCommandTimeout;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

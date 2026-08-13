package com.codearena.judge;

import com.codearena.judge.real.ProblemTestCaseRepository;
import com.codearena.judge.real.SandboxedJudgeEngine;
import com.codearena.judge.real.TestCaseSource;
import com.codearena.judge.sandbox.DockerSandbox;
import com.codearena.judge.sandbox.Sandbox;
import com.codearena.judge.sandbox.SandboxProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties({JudgeProperties.class, SandboxProperties.class})
public class JudgeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public SimulatedJudge simulatedJudge() {
        return new SimulatedJudge();
    }

    /**
     * Fixed pool for test-case execution.
     *
     * <p>Fixed rather than cached: the work is CPU-shaped and bounded, so an unbounded pool would
     * let a burst of submissions spawn hundreds of threads and spend its time context-switching.
     * The queue in front of it is Kafka, which is a better place to hold backlog than the heap.
     *
     * <p>Registered as a bean with {@code destroyMethod} so shutdown is Spring's problem rather
     * than a leaked non-daemon pool keeping the JVM alive.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService testCasePool(JudgeProperties properties) {
        return Executors.newFixedThreadPool(properties.workerThreads(), runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("judge-case-" + thread.getId());
            // Daemon so a wedged test case cannot prevent the worker from exiting.
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * The sandbox, only when real execution is switched on.
     *
     * <p>Built conditionally rather than always, because constructing it is a statement of intent:
     * it means this process expects to be able to create containers. A judge running in SIMULATED
     * mode should have no path to a Docker daemon at all, so there is nothing to misconfigure into
     * executing untrusted code by accident.
     */
    @Bean
    public Sandbox sandbox(JudgeProperties judgeProperties, SandboxProperties sandboxProperties) {
        return judgeProperties.judgesForReal() ? new DockerSandbox(sandboxProperties) : null;
    }

    @Bean
    public SandboxedJudgeEngine sandboxedJudgeEngine(ObjectProvider<Sandbox> sandbox,
                                                     SandboxProperties properties) {
        Sandbox available = sandbox.getIfAvailable();
        return available == null ? null : new SandboxedJudgeEngine(available, properties);
    }

    /**
     * Present only when MongoDB is configured. In SIMULATED mode the judge has no database and
     * needs none, and requiring one would make the simplest way to run this project the one that
     * fails to start.
     */
    @Bean
    public TestCaseSource testCaseSource(ObjectProvider<ProblemTestCaseRepository> repository) {
        ProblemTestCaseRepository available = repository.getIfAvailable();
        return available == null ? null : new TestCaseSource(available);
    }

    @Bean
    public JudgeService judgeService(SimulatedJudge simulatedJudge,
                                     ExecutorService testCasePool,
                                     JudgeProperties properties,
                                     Clock clock,
                                     ObjectProvider<SandboxedJudgeEngine> sandboxedEngine,
                                     ObjectProvider<TestCaseSource> testCaseSource) {
        return new JudgeService(simulatedJudge, testCasePool, properties, clock,
                Optional.ofNullable(sandboxedEngine.getIfAvailable()),
                Optional.ofNullable(testCaseSource.getIfAvailable()));
    }
}

package com.codearena.judge;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties(JudgeProperties.class)
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

    @Bean
    public JudgeService judgeService(SimulatedJudge simulatedJudge,
                                     ExecutorService testCasePool,
                                     JudgeProperties properties,
                                     Clock clock) {
        return new JudgeService(simulatedJudge, testCasePool, properties, clock);
    }
}

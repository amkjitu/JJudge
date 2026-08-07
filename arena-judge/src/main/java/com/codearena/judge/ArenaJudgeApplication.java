package com.codearena.judge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Judge worker. Kafka consumption and the evaluation thread pool land in Phase 6; for now this
 * exists so the multi-module build and deployment topology are real from day one.
 */
@SpringBootApplication
public class ArenaJudgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArenaJudgeApplication.class, args);
    }
}

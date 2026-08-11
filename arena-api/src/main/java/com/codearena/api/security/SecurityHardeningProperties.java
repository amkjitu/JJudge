package com.codearena.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings that only matter once the application is reachable by someone other than its author.
 *
 * @param seededAccounts what to do about the demo accounts migration V3 creates
 * @param adminPassword  replacement for the seeded admin password. Required when
 *                       {@code seededAccounts} is {@code LOCKED}; ignored otherwise
 * @param lockDemoUsers  whether alice, bob and carol should also lose the ability to sign in.
 *                       Off by default: a public demo wants visitors to be able to log in as
 *                       somebody and try it
 */
@ConfigurationProperties(prefix = "arena.security")
public record SecurityHardeningProperties(SeededAccounts seededAccounts,
                                          String adminPassword,
                                          boolean lockDemoUsers) {

    public enum SeededAccounts {

        /** Leave them exactly as seeded. The right choice on a laptop. */
        PERMIT,

        /** Rotate the admin password, and refuse to start without one. */
        LOCKED
    }

    public SecurityHardeningProperties {
        // Defaulting to PERMIT rather than LOCKED so that `docker compose up` on a fresh clone
        // works with no secrets to invent. Production opts in, and is loud when it forgets to.
        seededAccounts = seededAccounts == null ? SeededAccounts.PERMIT : seededAccounts;
    }
}

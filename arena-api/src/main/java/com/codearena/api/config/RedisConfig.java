package com.codearena.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

@Configuration
public class RedisConfig {

    /**
     * The token-bucket script, loaded once at startup.
     *
     * <p>{@code DefaultRedisScript} caches the SHA and issues {@code EVALSHA}, falling back to
     * {@code EVAL} if the server has forgotten it - so the script body crosses the wire once
     * rather than on every submission.
     *
     * <p>Kept in a {@code .lua} file rather than a Java string constant: Lua embedded in Java
     * gets no syntax highlighting, no linting, and tempts people into building it by
     * concatenation, which is how a script ends up with an injected key name.
     */
    @Bean
    public RedisScript<List> tokenBucketScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/token-bucket.lua"));
        script.setResultType(List.class);
        return script;
    }
}

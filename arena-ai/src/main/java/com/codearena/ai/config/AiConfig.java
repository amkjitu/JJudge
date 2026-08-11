package com.codearena.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the language model, if there is one.
 *
 * <h2>Provider-agnostic on purpose</h2>
 *
 * <p>Everything downstream depends on Spring AI's {@link ChatClient}, never on Ollama. Switching
 * to OpenAI is a starter swap in {@code pom.xml} plus {@code spring.ai.openai.*} configuration -
 * no service, prompt or controller changes. That is the whole argument for taking the framework
 * abstraction rather than calling an HTTP API directly: the provider is a deployment decision,
 * not an architectural one.
 *
 * <p>Ollama is the default because it needs no API key, so a fresh clone starts and can be
 * exercised without an account or a bill.
 *
 * <h2>The model is optional</h2>
 *
 * <p>{@link ObjectProvider} rather than a required dependency, so the absence of a reachable
 * model is a normal state rather than a failure to start. A model worth asking is several
 * gigabytes resident; requiring one would mean the service only runs on hardware most people
 * reviewing this project do not have spare.
 *
 * <p>When there is no client, the services answer from their own analysis and say so in the
 * response. What is never acceptable is answering heuristically while implying a model spoke -
 * hence {@code AnswerSource} on every payload.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    /**
     * Injected rather than called statically, so {@link ModelAvailability}'s cooldown can be
     * driven from a test without sleeping through it.
     */
    @Bean
    public java.time.Clock clock() {
        return java.time.Clock.systemUTC();
    }

    /**
     * @return a chat client, or {@code null} when no model is configured. A null bean is
     *         deliberate: callers take {@code ObjectProvider<ChatClient>} and treat absence as
     *         a supported mode, which is clearer than a no-op client that silently returns
     *         nothing useful.
     */
    @Bean
    public ChatClient chatClient(ObjectProvider<ChatModel> chatModel, AiProperties properties) {
        if (!properties.enabled()) {
            log.info("arena.ai.enabled=false: answering from static analysis only, no model will "
                    + "be called.");
            return null;
        }

        ChatModel model = chatModel.getIfAvailable();
        if (model == null) {
            log.warn("No chat model is configured. Hints and complexity analysis will come from "
                    + "static analysis and be labelled HEURISTIC. Point spring.ai.ollama.base-url "
                    + "at an Ollama instance to enable model-backed answers.");
            return null;
        }

        log.info("Chat model available: hints and complexity analysis will be model-backed.");
        return ChatClient.builder(model).build();
    }
}

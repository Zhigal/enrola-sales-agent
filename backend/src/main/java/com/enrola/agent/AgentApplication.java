package com.enrola.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

@SpringBootApplication
public class AgentApplication {

    private static final Logger log = LoggerFactory.getLogger(AgentApplication.class);

    @Value("${enrola.openai.model}")
    private String model;

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    void logModel() {
        log.info("Agent model: {}", model);
    }
}

package com.enrola.agent.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** The entire frontend/backend wiring. */
@Configuration
class CorsConfig implements WebMvcConfigurer {

    private final String origin;

    CorsConfig(@Value("${enrola.cors-origin}") String origin) {
        this.origin = origin;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(origin).allowedMethods("GET", "POST");
    }
}

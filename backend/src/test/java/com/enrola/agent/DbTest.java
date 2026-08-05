package com.enrola.agent;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// AgentServiceTest overrides the production Clock bean with a fixed one via @TestConfiguration,
// which Spring Boot rejects by default (two bean definitions named "clock"). Allowing overrides
// here is scoped to the test classpath only; production startup is unaffected.
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@Testcontainers
public abstract class DbTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
}

package com.enrola.agent;

import org.springframework.boot.SpringApplication;

public class TestAgentApplication {

	public static void main(String[] args) {
		SpringApplication.from(AgentApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

package com.zigzaego.api;

import java.util.stream.Stream;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:18"));

	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8"))
			.withExposedPorts(6379);

	static {
		Startables.deepStart(Stream.of(POSTGRES, REDIS)).join();
	}

	@Bean(destroyMethod = "")
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return POSTGRES;
	}

	@Bean(destroyMethod = "")
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return REDIS;
	}

}

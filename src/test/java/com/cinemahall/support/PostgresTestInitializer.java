package com.cinemahall.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * With "mvn test -Ppostgres", points the test contexts at a real embedded PostgreSQL server (no Docker needed).
 * Without that profile, the tests use H2 from application-test.yml and this class does nothing.
 */
public class PostgresTestInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static EmbeddedPostgres server;

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        if (!"postgres".equals(System.getProperty("test.database"))) {
            return;
        }
        // The seeder test needs an empty database of its own, as it has under H2.
        boolean seeding = context.getEnvironment().getProperty("app.seed.enabled", Boolean.class, false);
        String database = seeding ? createSeedDatabase() : "postgres";
        String url = start().getJdbcUrl("postgres", database);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("embedded-postgres", Map.of(
                "spring.datasource.url", url,
                "spring.datasource.username", "postgres",
                "spring.datasource.password", "postgres")));
    }

    private static String createSeedDatabase() {
        try (Connection connection = start().getPostgresDatabase().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE seed_test");
            return "seed_test";
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not create the seed test database", ex);
        }
    }

    private static synchronized EmbeddedPostgres start() {
        if (server == null) {
            try {
                server = EmbeddedPostgres.builder().start();
            } catch (IOException ex) {
                throw new IllegalStateException("Could not start embedded PostgreSQL", ex);
            }
        }
        return server;
    }
}

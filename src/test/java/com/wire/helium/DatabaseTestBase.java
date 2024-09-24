package com.wire.helium;

import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.junit.jupiter.api.BeforeAll;

import java.sql.Driver;
import java.sql.DriverManager;

abstract public class DatabaseTestBase {
    protected static Flyway flyway;
    protected static Jdbi jdbi;

    @BeforeAll
    public static void initiate() throws Exception {
        String databaseUrl = System.getenv("POSTGRES_URL");
        String jdbcUrl = "jdbc:postgresql://" + (databaseUrl != null ? databaseUrl : "localhost/postgres");
        String user = System.getenv("POSTGRES_USER") != null ? System.getenv("POSTGRES_USER") : "postgres";
        String password = System.getenv("POSTGRES_PASSWORD") != null ? System.getenv("POSTGRES_PASSWORD") : "postgres";

        var driverClass = Class.forName("org.postgresql.Driver");
        final Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
        DriverManager.registerDriver(driver);

        jdbi = (password != null ? Jdbi.create(jdbcUrl, user, password) : Jdbi.create(jdbcUrl))
                .installPlugin(new SqlObjectPlugin());

        flyway = Flyway
                .configure()
                .dataSource(jdbcUrl, user, password)
                .baselineOnMigrate(true)
                .load();

        flyway.migrate();
    }
}

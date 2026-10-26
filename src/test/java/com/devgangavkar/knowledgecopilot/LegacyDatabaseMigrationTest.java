package com.devgangavkar.knowledgecopilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.devgangavkar.knowledgecopilot.support.IntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.StringJoiner;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;

/**
 * Simulates the database the old Render deployment created with Hibernate ddl-auto
 * (embeddings stored as JSON text, no Flyway history) and checks that Flyway adopts it
 * and converts it in place.
 */
class LegacyDatabaseMigrationTest extends IntegrationTest {

    @Test
    void migratesHibernateCreatedDatabase() throws Exception {
        String legacyUrl = createLegacyDatabase();

        MigrateResult result = Flyway.configure()
                .dataSource(legacyUrl, POSTGRES.getUsername(), POSTGRES.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load()
                .migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(2);

        try (Connection connection = DriverManager.getConnection(legacyUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {

            try (ResultSet rows = statement.executeQuery(
                    "select id, vector_dims(embedding) as dims from document_chunks order by id")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("dims")).isEqualTo(3072);
                // A malformed legacy embedding is left empty rather than failing the migration.
                assertThat(rows.next()).isTrue();
                rows.getInt("dims");
                assertThat(rows.wasNull()).isTrue();
            }

            try (ResultSet rows = statement.executeQuery("select title, updated_at from conversations")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("title")).isEqualTo("What is the leave policy?");
                assertThat(rows.getTimestamp("updated_at")).isNotNull();
            }

            try (ResultSet rows = statement.executeQuery(
                    "select count(*) from information_schema.columns where table_name = 'document_chunks' and column_name = 'embedding_vector'")) {
                rows.next();
                assertThat(rows.getInt(1)).isZero();
            }
        }
    }

    private String createLegacyDatabase() throws Exception {
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("drop database if exists legacy_render");
            statement.execute("create database legacy_render");
        }

        String legacyUrl = POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/legacy_render$1");

        StringJoiner vector = new StringJoiner(", ", "[", "]");
        for (int index = 0; index < 3072; index++) {
            vector.add(index == 7 ? "1.0" : "0.0");
        }

        try (Connection connection = DriverManager.getConnection(legacyUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = connection.createStatement()) {
            // Shape of the schema Hibernate generated for the original entities.
            statement.execute("""
                    create table users (id bigserial primary key, email varchar(150) not null unique,
                        password varchar(255) not null, username varchar(100) not null unique);
                    create table user_roles (user_id bigint not null references users, role varchar(50) not null);
                    create table documents (id bigserial primary key, created_at timestamp(6) with time zone not null,
                        name varchar(255) not null, uploaded_by varchar(100) not null);
                    create table document_chunks (id bigserial primary key, content text not null,
                        embedding_vector text, document_id bigint not null references documents);
                    create table conversations (id bigserial primary key, created_at timestamp(6) with time zone not null,
                        user_id bigint not null);
                    create table chat_messages (id bigserial primary key, query text not null, response text not null,
                        timestamp timestamp(6) with time zone not null, conversation_id bigint not null references conversations);
                    """);
            statement.execute("insert into documents (created_at, name, uploaded_by) values (now(), 'handbook.pdf', 'admin')");
            statement.execute("insert into document_chunks (content, embedding_vector, document_id) values ('Leave policy text', '" + vector + "', 1)");
            statement.execute("insert into document_chunks (content, embedding_vector, document_id) values ('Broken row', '[1, 2, 3]', 1)");
            statement.execute("insert into conversations (created_at, user_id) values (now() - interval '1 day', 1)");
            statement.execute("insert into chat_messages (query, response, timestamp, conversation_id) values ('What is the leave policy?', 'Twenty days.', now(), 1)");
        }
        return legacyUrl;
    }
}

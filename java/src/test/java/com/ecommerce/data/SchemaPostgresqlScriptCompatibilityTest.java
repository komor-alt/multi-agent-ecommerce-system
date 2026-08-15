package com.ecommerce.data;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaPostgresqlScriptCompatibilityTest {

    @Test
    void schemaScriptIsExecutableBySpringScriptUtilsWithoutDollarQuoteBlocks() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);

        assertThatCode(() -> ScriptUtils.executeSqlScript(
                connection,
                new EncodedResource(new ClassPathResource("schema-postgresql.sql"))))
                .doesNotThrowAnyException();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(statement, atLeastOnce()).execute(sql.capture());
        List<String> statements = sql.getAllValues();

        assertThat(statements)
                .allMatch(statementText -> !statementText.contains("DO $$"))
                .allMatch(statementText -> !statementText.contains("$$"))
                .allMatch(statementText -> !statementText.contains("DROP COLUMN"))
                .noneMatch(statementText -> statementText.trim().startsWith("UPDATE products"));
        assertThat(statements)
                .anyMatch(statementText -> statementText.contains(
                        "ALTER TABLE IF EXISTS products")
                        && statementText.contains("ADD COLUMN IF NOT EXISTS embedding vector"));
        assertThat(statements)
                .anyMatch(statementText -> statementText.contains(
                        "ALTER COLUMN embedding TYPE vector")
                        && statementText.contains("USING CASE")
                        && statementText.contains("embedding_provider IS NULL")
                        && statementText.contains("embedding_model IS NULL")
                        && statementText.contains("embedding_dimensions IS NULL")
                        && statementText.contains("embedding_content_hash IS NULL"));
        verify(connection).createStatement();
    }
}

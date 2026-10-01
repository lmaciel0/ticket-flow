package com.ticketflow.attachment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Exactly one AttachmentStorage adapter is active, chosen by app.attachments.storage. */
class AttachmentStorageSelectionTest {

    /** Loads application.yml like the real application, with only the database adapter registered. */
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            // Never connects: the adapter only needs a JdbcTemplate to exist.
            .withBean(JdbcTemplate.class, () -> new JdbcTemplate(new DriverManagerDataSource()))
            .withUserConfiguration(DatabaseAttachmentStorage.class);

    @Test
    void usesTheDatabaseByDefault() {
        runner.run(context -> assertThat(context).hasSingleBean(DatabaseAttachmentStorage.class));
    }

    @Test
    void usesTheDatabaseWhenAskedExplicitly() {
        runner.withPropertyValues("app.attachments.storage=database")
                .run(context -> assertThat(context).hasSingleBean(DatabaseAttachmentStorage.class));
    }

    @Test
    void stepsAsideWhenS3IsChosen() {
        runner.withPropertyValues("app.attachments.storage=s3")
                .run(context -> assertThat(context).doesNotHaveBean(DatabaseAttachmentStorage.class));
    }

    @Test
    void theEnvironmentVariableChoosesTheAdapter() {
        runner.withPropertyValues("ATTACHMENTS_STORAGE=s3")
                .run(context -> assertThat(context).doesNotHaveBean(DatabaseAttachmentStorage.class));
    }
}

package com.ticketflow;

import com.ticketflow.support.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Temporary: proves the whole context starts against a real PostgreSQL. Replaced in Task 2. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ApplicationSmokeTest {

    @Test
    void contextLoads() {
    }
}

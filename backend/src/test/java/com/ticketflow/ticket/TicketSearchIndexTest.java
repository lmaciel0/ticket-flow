package com.ticketflow.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.support.IntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The text search (q=) must use the trigram indexes instead of reading the whole table. The condition
 * below is the one TicketSpecifications generates through Hibernate: lower(column) LIKE ? ESCAPE '\'.
 */
class TicketSearchIndexTest extends IntegrationTest {

    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void theTextSearchUsesTheTrigramIndexes() {
        List<String> plan = new TransactionTemplate(transactionManager).execute(tx -> {
            // With an almost empty table the planner always prefers a full scan; this forbids it,
            // so the plan shows whether an index exists that can answer the query. LOCAL: undone at the end.
            jdbc.execute("SET LOCAL enable_seqscan = off");
            return jdbc.queryForList("""
                    EXPLAIN SELECT id FROM tickets
                    WHERE lower(title) LIKE ? ESCAPE '\\' OR lower(description) LIKE ? ESCAPE '\\'
                    """, String.class, "%impressora%", "%impressora%");
        });

        assertThat(String.join("\n", plan))
                .contains("idx_tickets_title_trgm")
                .contains("idx_tickets_description_trgm");
    }
}

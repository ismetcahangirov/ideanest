package az.ideanest;

import az.ideanest.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The context either wires or it does not. This is the cheapest possible proof
 * that a change to configuration has not broken start-up, and it fails long
 * before a deployment would.
 *
 * <p>Since the service acquired a database it also proves that the migrations
 * apply and that Hibernate's mapping validates against the schema Flyway
 * produced — a mismatch between an entity and a migration now fails here rather
 * than on the first request that touches the table.
 */
class IdeaNestApplicationTests extends AbstractIntegrationTest {

    @Test
    @DisplayName("the application context loads")
    void contextLoads() {
        // Failure to start is the assertion.
    }
}

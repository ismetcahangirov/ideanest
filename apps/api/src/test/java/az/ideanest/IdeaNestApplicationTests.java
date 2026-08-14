package az.ideanest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The context either wires or it does not. This is the cheapest possible proof
 * that a change to configuration has not broken start-up, and it fails long
 * before a deployment would.
 */
@SpringBootTest
class IdeaNestApplicationTests {

    @Test
    @DisplayName("the application context loads")
    void contextLoads() {
        // Failure to start is the assertion.
    }
}

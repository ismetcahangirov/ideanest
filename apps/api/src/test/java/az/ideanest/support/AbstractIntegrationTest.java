package az.ideanest.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for tests that need the application wired to a real database.
 *
 * <p>Extend this rather than annotating a test class directly. Sharing one
 * annotation set is what lets Spring cache a single context — and with it a
 * single PostgreSQL container — across the suite.
 *
 * <p>A test that needs no database should not extend this. Starting a container
 * to assert on a pure function makes the suite slower for no coverage.
 */
// One web environment for the whole suite. Varying it per class would split the
// context cache and start a second PostgreSQL container to no purpose.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({ContainersConfiguration.class, TestDoublesConfiguration.class})
public abstract class AbstractIntegrationTest {
}

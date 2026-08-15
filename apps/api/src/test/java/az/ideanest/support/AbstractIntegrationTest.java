package az.ideanest.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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

    /**
     * Points the identity providers at the local stub.
     *
     * <p>Here rather than on the one test class that signs in with a provider,
     * for the reason above: a class that adds a property source of its own gets
     * a context of its own, and with it a second PostgreSQL container. The
     * issuers and audiences are ordinary configuration in
     * {@code application-test.yml}; only the key set address is decided at run
     * time, because the stub takes whichever port is free.
     */
    @DynamicPropertySource
    static void identityProviderStub(DynamicPropertyRegistry registry) {
        registry.add("ideanest.auth.oauth.providers.google.jwks-uri", OidcProviderStub::googleJwksUri);
        registry.add("ideanest.auth.oauth.providers.apple.jwks-uri", OidcProviderStub::appleJwksUri);
    }
}

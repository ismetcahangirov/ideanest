package az.ideanest.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The collaborators tests substitute.
 *
 * <p>Imported by {@link AbstractIntegrationTest} rather than per test class, so
 * that every integration test shares one context — and therefore one PostgreSQL
 * container. A test class that imported a different set would get a second
 * context and a second container for its trouble.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDoublesConfiguration {

    @Bean
    @Primary
    RecordingVerificationNotifier recordingVerificationNotifier() {
        return new RecordingVerificationNotifier();
    }

    /**
     * The collaborator invitation notifier, remembering rather than logging.
     *
     * <p>Here for the same reason as the one above: there is no mail transport, so a
     * test that wanted to accept an invitation would otherwise have to read the
     * token's hash out of the row and could never prove that the link sent to the
     * invitee is the link that works.
     */
    @Bean
    @Primary
    RecordingCollaboratorInvitationNotifier recordingCollaboratorInvitationNotifier() {
        return new RecordingCollaboratorInvitationNotifier();
    }

    /**
     * The application clock, with a handle on it.
     *
     * <p>Free-running unless a test freezes it, so this changes nothing for the
     * tests that do not care. The ones that do — one-time passwords, challenge
     * expiry — would otherwise have to sleep, and a sleeping test is flaky
     * precisely when the machine is busy.
     */
    @Bean
    @Primary
    AdjustableClock adjustableClock() {
        return new AdjustableClock();
    }
}

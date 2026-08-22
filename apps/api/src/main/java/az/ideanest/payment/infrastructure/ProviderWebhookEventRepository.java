package az.ideanest.payment.infrastructure;

import az.ideanest.payment.domain.ProviderName;
import az.ideanest.payment.domain.ProviderWebhookEvent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Verified provider deliveries: appended once, read for support. */
public interface ProviderWebhookEventRepository extends JpaRepository<ProviderWebhookEvent, UUID> {

    /**
     * Whether this delivery has already been handled.
     *
     * <p><strong>Not the deduplication.</strong> V43's unique index is, and it has to be:
     * two deliveries of one event arriving together — which is exactly what a provider
     * retrying something it thinks timed out produces — would both pass this read and
     * both be processed. This exists so that the ordinary redelivery, arriving seconds or
     * minutes later, is answered without provoking a constraint violation and a rolled
     * back transaction for something that is not an error.
     */
    boolean existsByProviderAndProviderEventId(ProviderName provider, String providerEventId);

    /** One delivery, for the support conversation that starts "did you receive it". */
    Optional<ProviderWebhookEvent> findByProviderAndProviderEventId(
            ProviderName provider, String providerEventId);
}

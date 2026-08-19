package az.ideanest.notification.infrastructure;

import az.ideanest.notification.domain.EmailDelivery;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The email transport's attempt log.
 *
 * <p><strong>Writes only, in production.</strong> Nothing in the sending path reads this
 * table: the queue's state lives on {@code notifications}, and a transport that consulted
 * its own history to decide what to do next would be a second source of truth about
 * whether somebody has been told something.
 *
 * <p>The reads below exist for the tests and for the support question V30's header names —
 * "what did we do about this notification, and when". A screen over them belongs to
 * AD-16, in the administration epic (#100).
 */
public interface EmailDeliveryRepository extends JpaRepository<EmailDelivery, UUID> {

    /** Every attempt for one notification, oldest first. */
    List<EmailDelivery> findByNotificationIdOrderByCreatedAtAsc(UUID notificationId);

    /** Every attempt for one digest, oldest first. */
    List<EmailDelivery> findByDigestIdOrderByCreatedAtAsc(UUID digestId);
}

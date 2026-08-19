package az.ideanest.notification.domain;

/**
 * What one attempt by the email transport did — and, by its omissions, what SMTP is
 * unable to tell anybody.
 *
 * <p>The set is closed and it is closed in the schema too,
 * {@code email_deliveries_outcome_known}. <strong>There is no {@code DELIVERED} and no
 * {@code BOUNCED}</strong>, and that is the point of the type rather than a gap in it: a
 * relay answers whether it accepted a message and nothing further. Delivery, spam
 * filing, bounces and opens come back later over a provider webhook, and #86 has a relay
 * (§16, {@code spring.mail}) rather than a provider. Adding either constant is a
 * migration and an integration, in that order.
 */
public enum EmailDeliveryOutcome {

    /**
     * The relay took it.
     *
     * <p><strong>Not "it arrived."</strong> This is the strongest statement the transport
     * is in a position to make, and {@code email_deliveries.accepted_at} is named to
     * match so that a future reader cannot mistake one for the other.
     */
    ACCEPTED,

    /**
     * The relay would not take it.
     *
     * <p>Retryable, and retried: {@code NotificationDispatch} counts the attempt, backs
     * off, and eventually dead-letters, which is the same policy {@code outbox_events}
     * has. Most refusals here are the relay being briefly unreachable.
     */
    REFUSED,

    /**
     * There was nothing to send to, and there never will be.
     *
     * <p>The account behind the recipient is gone or has been anonymised (§17.4), so its
     * address is {@code deleted-<id>@anonymised.invalid} — a domain reserved precisely so
     * that nothing routes to it. Sending would either bounce or, worse, reach whoever
     * eventually owns a name that looks like it.
     *
     * <p><strong>This is a permanent outcome and is deliberately not counted as a
     * refusal.</strong> A refusal buys eight attempts spread over an hour, and no number
     * of attempts makes a deleted account exist again — so the notification is
     * dead-lettered at once. {@code PermanentDeliveryFailure} is how the sender says
     * which of the two it means.
     */
    SUPPRESSED
}

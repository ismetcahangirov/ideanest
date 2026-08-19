package az.ideanest.notification.application;

/**
 * A channel's way of saying "no, and not next time either".
 *
 * <p>{@link ChannelSender} gives an implementation two outcomes: returning means the
 * channel accepted the message, throwing means it did not. That is the right contract for
 * the failure it was written about — a relay that is briefly unreachable — where trying
 * again in five seconds, then ten, then twenty is exactly the correct behaviour.
 *
 * <p>It is the wrong contract for the other kind. #86's transport resolves a recipient
 * identifier to an address through {@code UserAccounts}, and the account may have been
 * deleted since the notification was written: §17.4's anonymisation rewrites the address
 * to {@code deleted-<id>@anonymised.invalid}, a domain reserved so that nothing routes to
 * it. There is no address, and eight attempts spread over an hour will not produce one.
 *
 * <p>Absorbing that into an ordinary refusal would spend the whole retry budget on a
 * question already answered, and — worse — the log would fill with {@code WARN} lines
 * about a transport that is working perfectly. Returning normally instead would be worse
 * still: the row would say {@code SENT}, and {@code SENT} would then mean "either it went
 * or there was nobody to send it to", which is precisely the ambiguity
 * {@code UndeliverableChannelSender} exists to argue against.
 *
 * <p>So there is a third answer, and it is a subtype rather than a flag because a channel
 * reports its outcome by throwing or not: {@code NotificationDispatch} and
 * {@code DigestAssembly} dead-letter on this at once, with the reason on the row.
 *
 * <p><strong>A dead letter is the honest end here.</strong> It is what {@code DEAD}
 * already means — something the platform recorded as owed to a person and will not
 * deliver — and it stays queryable, which "sent" would not.
 */
public class PermanentDeliveryFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param reason what is permanently wrong, as a sentence that ends up in
     *     {@code notifications.last_error} and in {@code email_deliveries.detail}.
     *     <strong>It must not contain an address</strong>: both columns are read by
     *     people who are not that person, and §17.4 keeps personal data out of them
     */
    public PermanentDeliveryFailure(String reason) {
        super(reason);
    }
}

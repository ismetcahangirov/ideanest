package az.ideanest.notification.application;

import java.util.Locale;

/**
 * Sending one email to one address, for the messages that are not notifications.
 *
 * <p>§4.10's queue is about things that happened to a person who has an account and a
 * preference about hearing them. The auth module's six messages are neither: a
 * verification link goes to an address that has not been proven, a password reset goes to
 * somebody who cannot sign in, and none of them is a row in {@code notifications} or a
 * preference anybody may switch off. They still want #86's envelope, layout and copy
 * pipeline, which is what this port lends them.
 *
 * <h2>What it does not do</h2>
 *
 * <p>No queue, no retry, no {@code email_deliveries} row — that table's rows point at a
 * notification and these messages are not one. A refusal is reported by throwing and the
 * caller decides; {@code SmtpVerificationNotifier} explains why, for auth, the decision
 * is to log it rather than to propagate. Durability is #135's outbox and is deliberately
 * not faked here.
 */
public interface TransactionalMailer {

    /**
     * Hands one message to the relay, synchronously.
     *
     * @param toAddress the recipient. Not checked against an account, because half the
     *     callers are writing to somebody who may not have one
     * @param toName what a mail client shows beside the address, or null when the caller
     *     does not know it. Auth does not: it holds an address and no profile
     * @param mail what the message says
     * @param locale the language to render it in
     * @throws TransactionalMailFailedException when the relay refused it or could not be
     *     reached. Nothing was sent
     */
    void send(String toAddress, String toName, TransactionalMail mail, Locale locale);
}

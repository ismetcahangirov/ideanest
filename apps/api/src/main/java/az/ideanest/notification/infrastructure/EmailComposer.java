package az.ideanest.notification.infrastructure;

import az.ideanest.notification.NotificationProperties;
import az.ideanest.notification.application.NotificationDigest;
import az.ideanest.notification.application.NotificationMessage;
import az.ideanest.notification.domain.NotificationType;
import az.ideanest.shared.money.Money;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What an email says, decided per notification type.
 *
 * <p>The half of #86 that is about words rather than about SMTP. It reads
 * {@code notifications.params}, pulls out the facts the type's copy refers to, and asks
 * {@code messages.properties} for the sentences — so changing what an email says is
 * editing a properties file, and changing which facts it may mention is editing the
 * switch below.
 *
 * <h2>The switch is exhaustive, deliberately</h2>
 *
 * <p>There is no {@code default}. Adding a constant to {@link NotificationType} therefore
 * fails to compile until somebody has decided what the email for it says, which is the
 * one property worth having here: the alternative is a template that resolves to an empty
 * string and an email that goes out saying nothing, discovered by whoever receives it.
 *
 * <p><strong>Thirteen of the twenty types have no producer yet</strong> — nothing
 * publishes an event that becomes a {@code SURVEY_AVAILABLE} or a {@code REWARD_SHIPPED},
 * and #64, #74 and #87 are where those arrive. Their copy is written all the same, for
 * the same reason the enum lists them: the row exists in §4.10, the preference model
 * already resolves it, and a type whose email is written the day its event lands is a
 * type whose email is written in a hurry.
 *
 * <h2>Naming the campaign — #249, and why there are two keys</h2>
 *
 * <p>{@code params} now carries {@code projectTitle} on every notification about a
 * campaign, so the copy can say which one. It cannot simply say it, because the rows
 * written before #249 have no title in them and neither will a row whose campaign was
 * deleted between the event and the send — and a sentence built around {@code {1}} renders
 * with a hole in it when {@code {1}} is empty.
 *
 * <p>So a key may have a {@code .named} variant, and {@link #copy} prefers it when there is
 * a title to put in it. Two plain sentences rather than one sentence with a conditional
 * inside it: {@code MessageFormat}'s {@code choice} would express the same thing in a form
 * no translator can read, and a single sentence with a generic fallback substituted for the
 * title reads wrongly in at least one of the two cases whatever wording is chosen — "your
 * pledge to this campaign is confirmed" is not English anybody writes.
 *
 * <p>A type with no {@code .named} variant is a type whose copy reads the same either way.
 * That is the common case for the button and for the paragraphs that are about money rather
 * than about which campaign it was.
 */
@Component
public class EmailComposer {

    /** Where {@code messages.properties} keeps this module's copy. */
    private static final String PREFIX = "email.";

    /**
     * The digest's own keys, which are not a {@link NotificationType} because a digest is
     * several types at once.
     */
    private static final String DIGEST = PREFIX + "digest.";

    /**
     * The suffix on the variant of a key that names the campaign — #249.
     *
     * <p>Optional on every key. Its absence means the sentence reads the same whether the
     * campaign is named or not, which is the majority of them.
     */
    private static final String NAMED = ".named";

    private final MessageSource messages;
    private final ObjectMapper json;
    private final NotificationProperties properties;

    public EmailComposer(MessageSource messages, ObjectMapper json, NotificationProperties properties) {
        this.messages = messages;
        this.json = json;
        this.properties = properties;
    }

    /**
     * The content of the email for one notification.
     *
     * @param recipientName the name on the recipient's account, for the greeting
     */
    public EmailContent compose(NotificationMessage message, String recipientName) {
        JsonNode params = read(message.params());
        EmailFacts facts = factsFor(message.type(), params, recipientName);
        String action = actionUrl(message.type(), params, message.subjectType(), message.subjectId());
        String base = PREFIX + message.type().name() + ".";

        return EmailContent.of(
                copy(base + "subject", facts),
                copy(base + "headline", facts),
                paragraphs(base, facts),
                copy(base + "action", facts),
                action);
    }

    /**
     * The content of the email for one digest — §12.2's "one message about several
     * things".
     *
     * <p>Each member becomes a line built from its own type's {@code .line} key, which is
     * the same sentence as that type's headline in a form that reads inside a list. The
     * members are already ordered by when they happened; nothing is regrouped here,
     * because a digest that reordered its contents would not match the inbox beside it.
     */
    public EmailContent compose(NotificationDigest digest, String recipientName) {
        List<EmailContent.Item> items = new ArrayList<>(digest.notifications().size());
        for (NotificationMessage member : digest.notifications()) {
            JsonNode params = read(member.params());
            EmailFacts facts = factsFor(member.type(), params, recipientName);
            items.add(new EmailContent.Item(
                    copy(PREFIX + member.type().name() + ".line", facts),
                    actionUrl(member.type(), params, member.subjectType(), member.subjectId())));
        }

        // The count is the digest's one fact, and it goes in the slot the keys document
        // for one — which is `detail`, because a digest is not about an amount.
        EmailFacts facts = EmailFacts.of(recipientName).withDetail(String.valueOf(digest.notifications().size()));

        // No button. Every line above is already a link to the thing it is about, and a
        // digest's single most likely destination is whichever of them the reader cares
        // about — so one button would have to pick, and picking wrongly is worse than
        // not offering one. The obvious candidate, a link to the notification settings
        // that produced the digest, is #89's page and does not exist; pointing at it
        // would be a dead link in every message the platform sends daily.
        return new EmailContent(
                copy(DIGEST + "subject", facts),
                copy(DIGEST + "headline", facts),
                List.of(copy(DIGEST + "body", facts)),
                null,
                null,
                items);
    }

    /**
     * Which facts this type's copy may refer to.
     *
     * <p>Only the keys the producing event actually writes are read. A type whose
     * producer does not exist yet reads nothing and gets copy that needs nothing, rather
     * than a guess about the shape of a payload nobody has written.
     */
    private EmailFacts factsFor(NotificationType type, JsonNode params, String recipientName) {
        // Read for every type rather than per branch, because it is not a fact any one type
        // carries -- it is what the message is about, and the branch below is only about
        // which further facts the copy may mention. Absent on a row written before #249, on
        // a message that is not about a campaign at all, and on one whose campaign could not
        // be found; in all three the `.named` copy is simply not used.
        EmailFacts facts = EmailFacts.of(recipientName).about(text(params, "projectTitle"));

        return switch (type) {
            // Produced today. The params are NotificationEventListener's, and the
            // names below are the names it writes.
            case PLEDGE_CONFIRMED, PLEDGE_EDITED -> facts.withAmount(money(params, "total"));
            case PAYMENT_FAILED -> facts.withAmount(money(params, "amount"))
                    .withDetail(text(params, "attempt"));
            case GOAL_REACHED -> facts.withAmount(money(params, "goal"));
            case CAMPAIGN_SUCCEEDED, CAMPAIGN_UNSUCCESSFUL -> facts.withAmount(money(params, "pledged"))
                    .withDetail(text(params, "backersCount"));
            case PROJECT_APPROVED -> facts;

            // Not produced yet. #64 owns collection and the payment schedule, #69 the
            // payout, #74 the surveys, #80 fulfilment, #83 updates, #84 comments, #90
            // saving and following, and #87 the push half of all of them. Each reads the
            // amount its future event will carry where an amount is the point of the
            // message, and nothing where it is not — see the class comment for why these
            // are written before they are reachable.
            case PAYMENT_COLLECTED -> facts.withAmount(money(params, "amount"));
            case FINAL_PAYMENT_WARNING -> facts.withAmount(money(params, "amount"))
                    .withDetail(text(params, "attempt"));
            case PAYOUT_SENT -> facts.withAmount(money(params, "amount"));
            case NEW_UPDATE_PUBLISHED,
                    COMMENT_REPLY,
                    DIRECT_MESSAGE,
                    SURVEY_AVAILABLE,
                    SURVEY_OVERDUE,
                    REWARD_SHIPPED,
                    FOLLOWED_CREATOR_LAUNCHED,
                    LAUNCH_REMINDER,
                    SAVED_PROJECT_ENDING_SOON,
                    NEW_DEVICE_SIGN_IN -> facts;

            // The deadline reminders need no fact: how long is left is what the type is,
            // so the copy says it rather than reading it out of a document.
            //
            // DEADLINE_24H is here and has copy, and it can never be sent — §4.10 gives
            // it no email column and NotificationType agrees, so no email row of this
            // type is ever written and EmailTemplates leaves it out of the previews. It
            // is covered because the switch is exhaustive and an unreachable branch is a
            // cheaper way to say that than a default clause, which would also swallow
            // every genuinely new type.
            case DEADLINE_48H, DEADLINE_24H -> facts;
        };
    }

    /**
     * Where the button goes.
     *
     * <p>Five sources, in this order, and the order is the point:
     *
     * <ol>
     *   <li>The type, when the message is not about a campaign at all. Only
     *       {@code NEW_DEVICE_SIGN_IN} is: it is about the account, and what a person who
     *       did not recognise that sign-in needs is the session list.
     *   <li>The campaign's public path, {@code /projects/{creatorSlug}/{projectSlug}}, from
     *       the two slugs #249 puts in {@code params}. <strong>This is the only one of
     *       these that addresses the campaign page</strong> — see below.
     *   <li>{@code params.projectId}, which the pledge-shaped types carry — a message
     *       about a pledge is best answered by the campaign page, and a pledge has no
     *       page of its own on the web application yet.
     *   <li>The subject, when it is a campaign.
     *   <li>The site. A message with nowhere specific to go still gives the reader
     *       somewhere to go, and a button pointing at nothing is worse than the home page.
     * </ol>
     *
     * <p><strong>The third and fourth are kept, and they are wrong.</strong> §10.2's
     * campaign page takes two slugs, so {@code /projects/{uuid}} matches no route and
     * answers 404 — which is what every email the platform had sent did, and what the rows
     * written before #249 will keep doing, because their documents hold no slugs and
     * nothing here can invent them. Removing them would send those readers to the home page
     * instead; that is arguably better and it is a change to what old messages do, so it is
     * left to whoever decides that rather than taken quietly here. Every row written from
     * now on takes the second branch.
     *
     * <p><strong>Every destination here is a route that exists.</strong> That is a
     * constraint on the copy as much as on this method: a button labelled "update your
     * payment method" would need {@code /settings/payment-methods}, which is #55's and is
     * not built, so the copy asks the reader to sign in and the button goes to the
     * campaign. A live-looking button that 404s is the one outcome worse than a plain one.
     *
     * <p>Every one of them is resolved against {@code ideanest.notification.email.base-url}
     * so that a preview environment's mail does not send its readers to production.
     */
    private String actionUrl(NotificationType type, JsonNode params, String subjectType, UUID subjectId) {
        String base = properties.email().baseUrl();

        if (type == NotificationType.NEW_DEVICE_SIGN_IN) {
            return base + "/settings/sessions";
        }

        String creatorSlug = text(params, "creatorSlug");
        String projectSlug = text(params, "projectSlug");
        if (!creatorSlug.isEmpty() && !projectSlug.isEmpty()) {
            // Encoded although the shape check on both columns already restricts them to
            // lowercase, digits and hyphens. The value reaches here through a jsonb
            // document, and a link built by concatenation is the wrong place to rely on a
            // constraint several tables away.
            return base + "/projects/" + encode(creatorSlug) + "/" + encode(projectSlug);
        }

        UUID projectId = uuid(params);
        if (projectId != null) {
            return base + "/projects/" + projectId;
        }
        if ("project".equals(subjectType) && subjectId != null) {
            return base + "/projects/" + subjectId;
        }
        return base;
    }

    /**
     * The body, as however many paragraphs the type has.
     *
     * <p>{@code .body} is required and {@code .body2} is optional, which covers every
     * message §4.10 asks for without inventing a list format in a properties file. A type
     * needing three would add {@code .body3} here; none does.
     */
    private List<String> paragraphs(String base, EmailFacts facts) {
        // Through the same `.named` preference as everything else, so that a second
        // paragraph is not the one place in the file where naming the campaign silently
        // does nothing. `.body2.named` without `.body2` would drop the paragraph for a row
        // that has no title; `EmailCopyTests` holds that every `.named` key has its plain
        // counterpart, which is the property that makes this safe.
        String second = facts.projectTitle().isEmpty() ? null : optional(base + "body2" + NAMED, facts);
        if (second == null) {
            second = optional(base + "body2", facts);
        }
        return second == null ? List.of(copy(base + "body", facts)) : List.of(copy(base + "body", facts), second);
    }

    /**
     * One line of copy.
     *
     * <p>{@link Locale#ROOT} rather than a request locale, and that is a limitation stated
     * rather than hidden: this runs on a background sender with no request and no reader
     * attached, and {@code users} has a {@code locale} column nothing here reads yet.
     * Sending in the recipient's language is #123's, which is where the message bundles
     * grow their per-language files — the keys are already in the shape that needs.
     *
     * <p>A missing key throws. It is caught by {@code EmailCopyTests}, which asks for
     * every key of every type, so a missing one is a build failure rather than an email
     * that goes out with a placeholder in it.
     */
    private String copy(String key, EmailFacts facts) {
        if (!facts.projectTitle().isEmpty()) {
            String named = optional(key + NAMED, facts);
            if (named != null) {
                return named;
            }
        }
        return messages.getMessage(key, facts.arguments(), Locale.ROOT);
    }

    /** The same, for a key a type may legitimately not have. */
    private String optional(String key, EmailFacts facts) {
        return messages.getMessage(key, facts.arguments(), null, Locale.ROOT);
    }

    /**
     * An amount from the document, formatted for reading, or empty when it is not there.
     *
     * <p>{@code amount + " " + currency}: unambiguous, stable, and free of the locale
     * question above. A currency symbol and a thousands separator are a rendering decision
     * that differs per reader, and the reader here has no locale attached.
     *
     * <p>Read through the application's {@code ObjectMapper} so that {@link Money}'s own
     * deserialiser applies — §10.3 puts the amount in the document as a string, and
     * reading it as anything else is how a pledge becomes a double.
     */
    private String money(JsonNode params, String field) {
        JsonNode node = params.get(field);
        if (node == null || node.isNull()) {
            return "";
        }
        try {
            Money money = json.treeToValue(node, Money.class);
            return money.amount().toPlainString() + " " + money.currency();
        } catch (JacksonException notMoney) {
            // Not fatal, and deliberately so: the alternative is that one malformed
            // rendering document stops a person being told their payment failed. The
            // email goes out with the amount missing rather than not at all, and the
            // document is on the notification row for whoever investigates.
            return "";
        }
    }

    /**
     * One path segment, safe to concatenate.
     *
     * <p>{@link java.net.URLEncoder} is not used: it is written for form bodies, so it
     * encodes a space as {@code +}, which in a path is a plus sign rather than a space.
     */
    private static String encode(String segment) {
        return UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8);
    }

    /** A scalar from the document as text, or empty when it is not there. */
    private static String text(JsonNode params, String field) {
        JsonNode node = params.get(field);
        return node == null || node.isNull() ? "" : node.asString();
    }

    /** {@code params.projectId} as an identifier, or null when it is absent or not one. */
    private static UUID uuid(JsonNode params) {
        JsonNode node = params.get("projectId");
        if (node == null || !node.isString()) {
            return null;
        }
        try {
            return UUID.fromString(node.asString());
        } catch (IllegalArgumentException notAnIdentifier) {
            // Same argument as money() above: a link that falls back to the site is a
            // worse email, and no email at all is a worse outcome than a worse email.
            return null;
        }
    }

    /**
     * The rendering document.
     *
     * <p>An unreadable one yields an empty object rather than throwing, so that the
     * message still goes out — with the facts missing and the copy's fallbacks showing.
     * The schema already refuses a non-object ({@code notifications_params_is_an_object}),
     * so reaching this means something rewrote the column by hand.
     */
    private JsonNode read(String params) {
        if (params == null || params.isBlank()) {
            return json.createObjectNode();
        }
        try {
            JsonNode node = json.readTree(params);
            return node == null || !node.isObject() ? json.createObjectNode() : node;
        } catch (JacksonException malformed) {
            return json.createObjectNode();
        }
    }
}

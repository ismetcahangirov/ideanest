package az.ideanest.notification;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.notification.application.NotificationMessage;
import az.ideanest.notification.domain.NotificationChannel;
import az.ideanest.notification.domain.NotificationType;
import az.ideanest.notification.infrastructure.EmailComposer;
import az.ideanest.notification.infrastructure.EmailContent;
import az.ideanest.notification.infrastructure.EmailRenderer;
import az.ideanest.notification.infrastructure.RenderedEmail;
import az.ideanest.support.AbstractIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * That every notification type has copy, and that the copy is finished.
 *
 * <p>The failure this suite exists to prevent is the quiet one. {@code EmailComposer}
 * asks {@code MessageSource} for a key per type; a missing key throws, a key with a
 * placeholder nothing fills renders the placeholder, and a key referring to a fact its
 * type does not carry renders a hole in the middle of a sentence. None of those is
 * visible until somebody receives the message, and one of the messages is "your payment
 * failed".
 *
 * <p>So every type is rendered, all the way through both layouts, and the result is
 * checked for the shapes an unfinished template leaves behind.
 *
 * <p>Parameterised over the enum rather than listing the types, deliberately: a type
 * added to {@link NotificationType} joins this suite without anybody remembering to add
 * it, which is the same property {@code EmailComposer}'s exhaustive switch has at compile
 * time.
 */
class EmailCopyTests extends AbstractIntegrationTest {

    /** A rendering document carrying every fact any type reads. */
    private static final String PARAMS =
            """
            {
              "projectId": "01890000-0000-7000-8000-000000000001",
              "total": {"amount": "120.00", "currency": "AZN"},
              "amount": {"amount": "120.00", "currency": "AZN"},
              "goal": {"amount": "5000.00", "currency": "AZN"},
              "pledged": {"amount": "6250.00", "currency": "AZN"},
              "backersCount": 184,
              "attempt": 2
            }""";

    private static final String TITLE = "Xari Bulbul Ceramics";

    /**
     * The same document as {@link #PARAMS}, plus the three fields #249 added.
     *
     * <p>Written out rather than derived from the one above, so that both are readable as
     * what a stored document actually looks like — which is what these tests are about.
     */
    private static final String NAMED_PARAMS =
            """
            {
              "projectId": "01890000-0000-7000-8000-000000000001",
              "projectTitle": "Xari Bulbul Ceramics",
              "creatorSlug": "aysel-studio",
              "projectSlug": "xari-bulbul-ceramics",
              "total": {"amount": "120.00", "currency": "AZN"},
              "amount": {"amount": "120.00", "currency": "AZN"},
              "goal": {"amount": "5000.00", "currency": "AZN"},
              "pledged": {"amount": "6250.00", "currency": "AZN"},
              "backersCount": 184,
              "attempt": 2
            }""";

    /** The suffix on a key that names the campaign, as {@code EmailComposer} spells it. */
    private static final String NAMED = ".named";

    private static final String RECIPIENT = "Aysel";

    @Autowired
    private EmailComposer composer;

    @Autowired
    private EmailRenderer renderer;

    // ------------------------------------------------------------------
    // Every type
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(NotificationType.class)
    @DisplayName("every notification type renders a complete email")
    void everyTypeRenders(NotificationType type) {
        EmailContent content = composer.compose(message(type), RECIPIENT);
        RenderedEmail email = renderer.render(content);

        assertThat(email.subject()).as("%s has a subject", type).isNotBlank();
        assertThat(content.headline()).as("%s opens with something", type).isNotBlank();
        assertThat(content.paragraphs()).as("%s has a body", type).isNotEmpty();
        assertThat(content.paragraphs()).allSatisfy(paragraph -> assertThat(paragraph)
                .as("%s has no empty paragraph", type)
                .isNotBlank());
    }

    /**
     * The three shapes an unfinished template leaves in a rendered message.
     *
     * <p>A {@code {2}} that nothing replaced, the word {@code null} where a fact should
     * be, and the double space left behind when a sentence refers to a fact its type does
     * not carry. The last one is why {@link EmailFactsHole} is checked at all: an empty
     * slot renders as nothing rather than as an error, so the sentence still forms and
     * the only trace is the spacing.
     */
    @ParameterizedTest
    @EnumSource(NotificationType.class)
    @DisplayName("no rendered email leaves a placeholder, a null, or a gap where a fact should be")
    void nothingIsLeftUnfilled(NotificationType type) {
        EmailContent content = composer.compose(message(type), RECIPIENT);
        RenderedEmail email = renderer.render(content);

        for (String part : List.of(email.subject(), email.text(), email.html())) {
            assertThat(part)
                    .as("%s renders every argument its copy refers to", type)
                    .doesNotContainPattern("\\{\\d}");
            assertThat(part).as("%s has no null fact", type).doesNotContain("null");
        }

        // The gap check is against the sentences rather than the rendered parts: both
        // layouts are indented, so runs of spaces are ordinary there and would make this
        // assertion fire on the whitespace of the template instead of on the copy.
        List<String> sentences = new java.util.ArrayList<>(content.paragraphs());
        sentences.add(content.subject());
        sentences.add(content.headline());
        assertThat(sentences).allSatisfy(sentence -> assertThat(sentence)
                .as("%s refers to no fact it does not carry -- see EmailFacts", type)
                .doesNotContain(EmailFactsHole.DOUBLE_SPACE));
    }

    /**
     * That both parts say the same thing.
     *
     * <p>The headline and the call to action are the two things a reader acts on, and a
     * plain-text part that lost either is a message that is complete for some readers and
     * broken for the rest — which is precisely the failure {@code RenderedEmail} argues
     * against sending only HTML to avoid.
     */
    @ParameterizedTest
    @EnumSource(NotificationType.class)
    @DisplayName("the plain-text part carries the same headline and destination as the HTML")
    void bothPartsAgree(NotificationType type) {
        EmailContent content = composer.compose(message(type), RECIPIENT);
        RenderedEmail email = renderer.render(content);

        assertThat(email.text()).as("%s: headline in the text part", type).contains(content.headline());
        assertThat(email.html()).as("%s: headline in the HTML part", type).contains(content.headline());
        assertThat(email.text())
                .as("%s: the text part carries the destination as a readable URL", type)
                .contains(content.actionUrl());
        assertThat(email.html())
                .as("%s: the HTML part links to the same place", type)
                .contains(content.actionUrl());
    }

    // ------------------------------------------------------------------
    // What the links point at
    // ------------------------------------------------------------------

    /**
     * That a message about a campaign goes to that campaign.
     *
     * <p>{@code params.projectId} is what the pledge-shaped types carry, and reading it
     * is what stops the button falling back to the home page on the messages people
     * actually receive today.
     */
    @Test
    @DisplayName("a pledge confirmation links to the campaign it is about")
    void thePledgeConfirmationLinksToItsCampaign() {
        EmailContent content = composer.compose(message(NotificationType.PLEDGE_CONFIRMED), RECIPIENT);

        assertThat(content.actionUrl()).endsWith("/projects/01890000-0000-7000-8000-000000000001");
    }

    /**
     * That the one message about the account rather than a campaign goes to the account.
     *
     * <p>A sign-in alert whose only button led to a campaign page would be the least
     * useful possible response to "somebody signed in as you".
     */
    @Test
    @DisplayName("a new-device alert links to the session list rather than to a campaign")
    void theSecurityAlertLinksToTheSessions() {
        EmailContent content = composer.compose(message(NotificationType.NEW_DEVICE_SIGN_IN), RECIPIENT);

        assertThat(content.actionUrl()).endsWith("/settings/sessions");
    }

    /**
     * That an amount reaches the copy as the string §10.3 stored, unrounded.
     *
     * <p>Money is the reason this file is not only about spelling. {@code params} holds
     * {@code {"amount": "120.00"}} and a renderer that read it as a number would send
     * somebody {@code 120.0} — or, on the wrong value, something further from what they
     * pledged.
     */
    @Test
    @DisplayName("an amount is rendered exactly as it was stored, to the currency's scale")
    void anAmountIsNotRounded() {
        RenderedEmail email =
                renderer.render(composer.compose(message(NotificationType.PLEDGE_CONFIRMED), RECIPIENT));

        assertThat(email.text()).contains("120.00 AZN").doesNotContain("120.0 ").doesNotContain("120 AZN");
    }

    // ------------------------------------------------------------------
    // Naming the campaign -- #249
    // ------------------------------------------------------------------

    /**
     * That a document carrying a title produces copy that uses it.
     *
     * <p>The point of #249, checked on the message where it is worth most: a digest line and
     * a subject that say which campaign, rather than "a campaign you backed".
     */
    @Test
    @DisplayName("copy names the campaign when the document carries its title")
    void namedCopyIsUsedWhenThereIsATitle() {
        EmailContent content = composer.compose(named(NotificationType.GOAL_REACHED), RECIPIENT);

        assertThat(content.subject()).contains(TITLE);
        assertThat(content.headline()).contains(TITLE);
        assertThat(content.paragraphs()).anySatisfy(paragraph -> assertThat(paragraph)
                .contains(TITLE));
    }

    /**
     * That a document without one still produces a finished sentence.
     *
     * <p>The rows written before #249 have no title, and neither has a notification whose
     * campaign was deleted between the event and the send. Those must fall back to copy that
     * reads correctly rather than to a sentence with a gap in it — which is what a single
     * key built around {@code {1}} would give them, and the reason there are two keys.
     */
    @Test
    @DisplayName("copy without a title falls back to the sentence that needs none")
    void plainCopyIsUsedWhenThereIsNoTitle() {
        EmailContent content = composer.compose(message(NotificationType.GOAL_REACHED), RECIPIENT);

        assertThat(content.subject()).isEqualTo("The goal has been reached");
        assertThat(content.paragraphs()).allSatisfy(paragraph -> assertThat(paragraph)
                .doesNotContain(EmailFactsHole.DOUBLE_SPACE));
    }

    /**
     * That every {@code .named} key has the plain key it falls back to.
     *
     * <p>The one way this arrangement fails silently. {@code EmailComposer} prefers the named
     * variant and falls back to the plain one; a {@code .body2.named} whose counterpart was
     * never written would simply drop that paragraph for every reader whose notification has
     * no title, and nothing else in this suite would notice — the sentences that remain are
     * all well formed.
     */
    @Test
    @DisplayName("every campaign-naming key has the plain key it falls back to")
    void everyNamedKeyHasAPlainCounterpart() {
        ResourceBundle bundle = ResourceBundle.getBundle("messages", Locale.ROOT);

        List<String> orphans = bundle.keySet().stream()
                .filter(key -> key.endsWith(NAMED))
                .filter(key -> !bundle.containsKey(key.substring(0, key.length() - NAMED.length())))
                .sorted()
                .toList();

        assertThat(orphans)
                .withFailMessage(
                        "These keys name the campaign and have no fallback for a notification that carries"
                                + " no title:%n  %s",
                        String.join("\n  ", orphans))
                .isEmpty();
    }

    /**
     * The same three checks, against the document every new notification now carries.
     *
     * <p>{@link #nothingIsLeftUnfilled} renders the copy a titleless row falls back to, so on
     * its own it leaves the {@code .named} half of the bundle unexercised — and a named key
     * referring to a fact its type does not carry fails in exactly the invisible way that
     * suite exists to catch.
     */
    @ParameterizedTest
    @EnumSource(NotificationType.class)
    @DisplayName("the campaign-naming copy leaves no placeholder, null or gap either")
    void namedCopyIsAlsoComplete(NotificationType type) {
        EmailContent content = composer.compose(named(type), RECIPIENT);
        RenderedEmail email = renderer.render(content);

        for (String part : List.of(email.subject(), email.text(), email.html())) {
            assertThat(part)
                    .as("%s renders every argument its named copy refers to", type)
                    .doesNotContainPattern("\\{\\d}");
            assertThat(part).as("%s has no null fact", type).doesNotContain("null");
        }

        List<String> sentences = new java.util.ArrayList<>(content.paragraphs());
        sentences.add(content.subject());
        sentences.add(content.headline());
        assertThat(sentences).allSatisfy(sentence -> assertThat(sentence)
                .as("%s refers to no fact it does not carry -- see EmailFacts", type)
                .doesNotContain(EmailFactsHole.DOUBLE_SPACE));
    }

    /**
     * That the button goes to a page that exists.
     *
     * <p>§10.2's campaign page takes two slugs, so the {@code /projects/{uuid}} this used to
     * build matched no route and answered 404 — on every email the platform sent about a
     * campaign. #249 puts both slugs in the document; this is the assertion that they are
     * used.
     */
    @Test
    @DisplayName("a campaign message links to the two-segment public path")
    void theButtonUsesThePublicPath() {
        EmailContent content = composer.compose(named(NotificationType.PLEDGE_CONFIRMED), RECIPIENT);

        assertThat(content.actionUrl()).endsWith("/projects/aysel-studio/xari-bulbul-ceramics");
    }

    // ------------------------------------------------------------------
    // The one type with copy and no email
    // ------------------------------------------------------------------

    /**
     * {@code DEADLINE_24H} has copy because the switch is exhaustive, and no email column.
     *
     * <p>Asserted from the enum rather than from a list here, so that this stays true if
     * §4.10 changes its mind: what the test is about is that copy existing does not imply
     * an email being sendable.
     */
    @Test
    @DisplayName("a type with no email column still renders, and is still not emailed")
    void copyExistsForATypeThatIsNeverEmailed() {
        assertThat(NotificationType.DEADLINE_24H.channels())
                .as("§4.10 gives the twenty-four hour reminder push and in-app only")
                .doesNotContain(NotificationChannel.EMAIL);

        assertThat(renderer.render(composer.compose(message(NotificationType.DEADLINE_24H), RECIPIENT))
                        .subject())
                .isNotBlank();
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static NotificationMessage message(NotificationType type) {
        return message(type, PARAMS);
    }

    /** The same notification, about a campaign the document can name. */
    private static NotificationMessage named(NotificationType type) {
        return message(type, NAMED_PARAMS);
    }

    private static NotificationMessage message(NotificationType type, String params) {
        return new NotificationMessage(
                UUID.fromString("01890000-0000-7000-8000-000000000002"),
                UUID.fromString("01890000-0000-7000-8000-000000000003"),
                type,
                NotificationChannel.EMAIL,
                "project",
                UUID.fromString("01890000-0000-7000-8000-000000000001"),
                params,
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                1);
    }

    /** Named so the assertion above reads as what it is checking rather than as a literal. */
    private static final class EmailFactsHole {

        /** What an unfilled slot leaves between two words. */
        static final String DOUBLE_SPACE = "  ";

        private EmailFactsHole() {
        }
    }
}

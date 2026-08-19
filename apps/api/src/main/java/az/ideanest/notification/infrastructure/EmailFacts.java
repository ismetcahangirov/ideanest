package az.ideanest.notification.infrastructure;

/**
 * The four things any email copy in {@code messages.properties} may refer to.
 *
 * <p><strong>One argument vector for every type, rather than a bespoke one each.</strong>
 * {@code MessageFormat} is positional, so a per-type vector would mean twenty
 * independent conventions about what {@code {0}} means and twenty chances for a template
 * and its caller to disagree silently — the failure mode being an email that says
 * "your pledge to 25.00 AZN". Fixing the positions once, and naming them here, makes a
 * key's arguments something a translator can read off this record.
 *
 * <p>A slot a type has nothing for is the empty string rather than null, so that a key
 * which mentions it renders a gap instead of the word {@code null}. Keys are written not
 * to mention slots their type does not fill; {@code EmailCopyTests} holds that.
 *
 * @param recipientName {@code {0}} — who is being written to, by the name on their
 *     account
 * @param projectTitle {@code {1}} — the campaign this is about.
 *     <p><strong>Empty today, on every type.</strong> {@code notifications.params} does
 *     not carry a title: {@code NotificationEventListener} puts {@code projectId} in and
 *     the events behind it — {@code PledgeConfirmedEvent},
 *     {@code CampaignFinalisedEvent} — have no title field to put. Supplying it means a
 *     shared port the project module implements, in the shape of
 *     {@code shared.audience.ProjectAudiences}, and changing seven translations to ask
 *     it; that is three modules and it is not #86. The copy therefore reads as
 *     "your campaign" wherever a title would go, and the day the field arrives this
 *     record is where it lands and the keys stop needing the fallback
 * @param amount {@code {2}} — the money the message is about, already formatted for
 *     reading. A string, because §10.3's rule does not stop at the API boundary: an
 *     amount that became a double to be rendered is an amount that may render wrongly
 * @param detail {@code {3}} — the one further fact the type needs, if it needs one: which
 *     retry a payment failure was, how many people backed a campaign
 */
public record EmailFacts(String recipientName, String projectTitle, String amount, String detail) {

    /** What a type with nothing to add says: the recipient's name and nothing else. */
    public static EmailFacts of(String recipientName) {
        return new EmailFacts(recipientName, "", "", "");
    }

    public EmailFacts {
        recipientName = recipientName == null ? "" : recipientName;
        projectTitle = projectTitle == null ? "" : projectTitle;
        amount = amount == null ? "" : amount;
        detail = detail == null ? "" : detail;
    }

    /** The same, with the campaign named. */
    public EmailFacts about(String title) {
        return new EmailFacts(recipientName, title, amount, detail);
    }

    /** The same, carrying an amount. */
    public EmailFacts withAmount(String formatted) {
        return new EmailFacts(recipientName, projectTitle, formatted, detail);
    }

    /** The same, carrying the type's one further fact. */
    public EmailFacts withDetail(String value) {
        return new EmailFacts(recipientName, projectTitle, amount, value);
    }

    /** The vector, in the order the keys document. */
    public Object[] arguments() {
        return new Object[] {recipientName, projectTitle, amount, detail};
    }
}

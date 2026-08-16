package az.ideanest.discovery.application;

import java.util.List;

/**
 * How many campaigns each filter value would return — D-10's live faceted counts.
 *
 * <p><strong>Every facet excludes its own dimension and applies every other.</strong>
 * This is the rule that decides whether the filter panel is usable. Counting the
 * category facet under the category filter the caller already selected makes every
 * category except the chosen one read zero, and a panel of zeros is a dead end: the
 * only move it offers is to clear the filter, so a backer who picked Games can never
 * discover that there are also forty campaigns in Comics matching everything else
 * they chose. Excluding the dimension from its own counts turns the same panel into
 * "and if you switched to Comics instead, there would be forty".
 *
 * <p>Every other active filter still applies, or the counts would be answering a
 * question nobody asked. So the category counts are computed under the status,
 * completion, tag, and amount filters, and under no category filter; the status
 * counts under the category, completion, tag, and amount filters, and under no status
 * filter; and so on.
 *
 * <p><strong>For the AND'd tag dimension this is a choice, and it is the same
 * choice.</strong> An alternative reading — count each tag with the already-selected
 * tags still applied, so the number means "how many remain if I add this one" — is
 * defensible and is what some engines do by default. It is not used here, because one
 * rule stated once is worth more than a rule with an exception, and because the
 * exception would make the tag counts mean something different from every other
 * number on the same panel.
 *
 * <p><strong>Zero-count values are present, not omitted</strong>, for every dimension
 * with a fixed vocabulary: the five statuses, the five completion bands, the two sets
 * of five amount bands, and every category. §4.3 asks for a live count on each
 * category, and a filter control that disappears when its count reaches zero is a
 * control that moves under the reader's cursor. Tags are the exception — the
 * vocabulary is free and unbounded, so only tags with campaigns behind them appear.
 *
 * @param statuses keyed by {@code DiscoveryStatus.wireValue()}
 * @param categories in the taxonomy's display order, each with its subcategories
 * @param tags the most used first, capped; a free vocabulary has no fixed list to
 *     render in full
 * @param completion keyed by {@code CompletionBand.wireValue()}
 * @param goalAmount keyed by {@code AmountBand.wireValue()}
 * @param amountRaised keyed by {@code AmountBand.wireValue()}
 */
public record FacetCounts(
        List<ValueCount> statuses,
        List<CategoryCount> categories,
        List<NamedCount> tags,
        List<ValueCount> completion,
        List<ValueCount> goalAmount,
        List<ValueCount> amountRaised) {

    /**
     * How many tags a facet response carries.
     *
     * <p>Fifty. The vocabulary is free (§4.3) and unbounded, so the whole list is not
     * a thing that can be rendered or transferred; the tags worth showing are the ones
     * with campaigns behind them, and a panel that offers more than a screenful of
     * them is not a filter any more. Trending tags (D-09) is a different question and
     * belongs to its own surface.
     */
    public static final int TAG_LIMIT = 50;

    /** One value of a fixed vocabulary, and how many campaigns it would return. */
    public record ValueCount(String value, long count) {
    }

    /**
     * A taxon or a tag: the stable handle, what to render, and the count.
     *
     * @param name resolved against {@code Accept-Language} for a taxon, and the
     *     creator's original spelling for a tag — {@code tags.label}, never the folded
     *     {@code tags.slug}, which would show "incesenet" to a reader whose alphabet
     *     has the letter ə in it
     */
    public record NamedCount(String slug, String name, long count) {
    }

    /**
     * A primary category with its children.
     *
     * @param count campaigns in this category, whichever subcategory they are filed
     *     under and including those filed under none. It is therefore at least the sum
     *     of {@code subcategories}, and usually more
     */
    public record CategoryCount(String slug, String name, long count, List<NamedCount> subcategories) {
    }

    public FacetCounts {
        statuses = List.copyOf(statuses);
        categories = List.copyOf(categories);
        tags = List.copyOf(tags);
        completion = List.copyOf(completion);
        goalAmount = List.copyOf(goalAmount);
        amountRaised = List.copyOf(amountRaised);
    }
}

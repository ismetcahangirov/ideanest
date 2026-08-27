package az.ideanest.support;

import az.ideanest.fx.application.PublishedRate;
import az.ideanest.fx.application.RateSource;
import az.ideanest.fx.application.RateSourceUnavailableException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A rate source a test writes the answers for — issue #327.
 *
 * <p><strong>The suite never reaches cbar.az.</strong> A test that fetched a public website
 * would fail for reasons that are not ours, on somebody else's schedule, and it could not
 * produce the cases that matter anyway: a source that is down, a source serving Friday's
 * rates on Sunday, a rouble quoted per hundred, a rate that has aged past
 * {@code ideanest.fx.max-age}. The XML parsing itself is tested directly against a document
 * on disk, which is where a parser belongs.
 *
 * <h2>How a test uses it</h2>
 *
 * <pre>{@code
 * source.publishes(LocalDate.of(2026, 8, 27), Map.of("USD", "1.7000000000"));
 * source.willBeUnavailable();
 * }</pre>
 */
public class ScriptedRateSource implements RateSource {

    private final List<PublishedRate> published = new CopyOnWriteArrayList<>();
    private final AtomicInteger fetches = new AtomicInteger();

    private volatile boolean unavailable;

    @Override
    public az.ideanest.fx.domain.RateSource name() {
        return az.ideanest.fx.domain.RateSource.CBAR;
    }

    @Override
    public List<PublishedRate> fetch() {
        fetches.incrementAndGet();
        if (unavailable) {
            throw new RateSourceUnavailableException("The scripted rate source is unavailable");
        }
        return List.copyOf(published);
    }

    /**
     * What the source publishes from now on, replacing whatever it published before.
     *
     * @param publishedFor the day the source says these are in force from. A test passes an
     *     older one to produce a stale rate without waiting
     * @param rates currency to rate, as strings — the fixture is held to CLAUDE.md §3's rule
     *     as much as the code is, and a double literal here would be the one place a test
     *     rounded differently from what it is asserting
     */
    public void publishes(LocalDate publishedFor, Map<String, String> rates) {
        List<PublishedRate> next = new ArrayList<>();
        rates.forEach((currency, rate) -> next.add(new PublishedRate(currency, new BigDecimal(rate), publishedFor)));
        published.clear();
        published.addAll(next);
        unavailable = false;
    }

    /** Every fetch from now on throws, which is what an outage looks like from here. */
    public void willBeUnavailable() {
        unavailable = true;
    }

    /** How many times the refresh asked. What proves a disabled deployment asks nothing. */
    public int fetchCount() {
        return fetches.get();
    }

    /** Forgets the script and the count. Called between tests. */
    public void reset() {
        published.clear();
        unavailable = false;
        fetches.set(0);
    }
}

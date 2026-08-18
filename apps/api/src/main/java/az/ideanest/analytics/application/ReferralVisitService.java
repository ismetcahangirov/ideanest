package az.ideanest.analytics.application;

import az.ideanest.analytics.AnalyticsProperties;
import az.ideanest.analytics.domain.ReferralSource;
import az.ideanest.analytics.domain.ReferralTouch;
import az.ideanest.analytics.domain.VisitorToken;
import az.ideanest.analytics.infrastructure.ReferralTouchRepository;
import az.ideanest.project.application.PublicProjects;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The first half of attribution: remembering where somebody came from, before there
 * is a pledge to attribute.
 *
 * <p>§4.6's Promotion tab issues share links and §4.7's CD-03 reports on them, and
 * between the two there has to be something that watches people arrive. This is it,
 * and it is reachable without an account because the visits that matter most are made
 * before anybody has one.
 *
 * <h2>What it does not do</h2>
 *
 * <p>It records a source against an opaque token. It does not set a cookie, does not
 * read one, and does not fingerprint anything: the client keeps the token and sends it
 * back, which makes the retention rule the client's as well as ours and makes clearing
 * it a thing a person can actually do. §17.4's argument about pseudonymous identifiers
 * is the same one, and {@code VisitorToken} carries the part about why the token is
 * randomness rather than anything derived from an account.
 *
 * <h2>Claiming, which is what makes the anonymous half count</h2>
 *
 * <p>A visitor reads a campaign for a week, signs in at checkout, and pledges. Every
 * visit before that moment is anonymous, and without {@link ReferralTouch#claimedBy}
 * the attribution rule would only ever see the last one — so every campaign would
 * appear to convert nobody except the people who arrived already signed in. When an
 * authenticated caller presents a token they were already holding, the visits that
 * token made become theirs.
 *
 * <p>Only the unclaimed ones, and only the open ones. A touch already attached to
 * another account stays attached: a shared device and a forwarded link both produce
 * that case, and moving the row would transfer one person's browsing to somebody
 * else's report.
 */
@Service
public class ReferralVisitService {

    /**
     * What a token this service minted looks like: {@code SecureTokens}' 32 bytes,
     * URL-safe Base64 without padding, which is 43 characters.
     *
     * <p>Anything else is treated as no token at all rather than refused — see
     * {@link #tokenOf}.
     */
    private static final Pattern MINTED_TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    /**
     * How many recent visits are read to decide whether this one is a repeat.
     *
     * <p>A bound rather than a target. Inside one session interval a person produces a
     * handful of visits; a client producing more than this is looping, and the answer
     * to a loop is not to read all of it.
     */
    private static final int RECENT_VISITS_READ = 20;

    private final PublicProjects projects;
    private final ReferralTouchRepository touches;
    private final AnalyticsProperties properties;
    private final Clock clock;

    public ReferralVisitService(
            PublicProjects projects,
            ReferralTouchRepository touches,
            AnalyticsProperties properties,
            Clock clock) {

        this.projects = projects;
        this.touches = touches;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Records a visit, and answers with the token to send next time.
     *
     * <p><strong>The campaign is asked first.</strong> A visit to a campaign that does
     * not exist, or to one whose state is not public, writes nothing — otherwise this
     * endpoint would be a way to find out which identifiers exist, and a way to put
     * rows against a draft somebody has not announced.
     *
     * @throws az.ideanest.project.application.ProjectNotFoundException for a campaign
     *     that does not exist and for one that is not publicly visible, identically
     */
    @Transactional
    public ReferralVisit record(CaptureReferralVisit command) {
        // Also the reason this module never reads `projects` itself: PublicProjects is
        // the project module's application layer and the only part of it another
        // module may see. ModuleBoundaryTests fails the build over the alternative.
        projects.requireVisible(command.projectId());

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String token = tokenOf(command.visitorToken());
        byte[] visitor = VisitorToken.hash(token);

        if (command.accountId() != null) {
            claim(command, visitor, now);
        }

        AnalyticsProperties.Referral referral = properties.referral();
        Optional<ReferralTouch> repeat = repeatOf(command, visitor, now, referral);
        if (repeat.isPresent()) {
            // Deduplicated. The client is told the window it is already inside rather
            // than a new one, because no new evidence was recorded and answering with a
            // later expiry would be answering about a row that does not exist.
            return new ReferralVisit(token, repeat.get().getExpiresAt());
        }

        Instant expiresAt = now.plus(referral.attributionWindow());
        touches.save(ReferralTouch.record(
                command.projectId(), visitor, command.accountId(), command.source(), now, expiresAt));
        return new ReferralVisit(token, expiresAt);
    }

    /**
     * The token to remember this visitor by: the one presented, or a new one.
     *
     * <p><strong>An unrecognisable token is replaced rather than refused.</strong> A
     * 400 here would break every client holding a value from before some future format
     * change, for a request whose entire purpose is to be made by clients we do not
     * control — and there is nothing to protect: a token is a bucket to put visits in,
     * so the worst a stale one can do is start a new bucket, which is what minting one
     * does anyway.
     *
     * <p>The shape is checked at all because the alternative is accepting whatever
     * arrives. A one-character "token" is a bucket every client sending it shares, and
     * shared buckets are how one visitor's browsing ends up as evidence about another
     * — {@link ReferralTouch#claimedBy} refuses to move a claimed row for the same
     * reason.
     */
    private static String tokenOf(String presented) {
        return presented != null && MINTED_TOKEN.matcher(presented).matches() ? presented : VisitorToken.mint();
    }

    /** Attaches this visitor's earlier anonymous visits to the account that has just appeared. */
    private void claim(CaptureReferralVisit command, byte[] visitor, Instant now) {
        List<ReferralTouch> unclaimed = touches.findUnclaimed(command.projectId(), visitor, now);
        for (ReferralTouch touch : unclaimed) {
            touch.claimedBy(command.accountId());
        }
        // Dirty checking would write these at the flush; saveAll says so at the line
        // that decided it, which is what a reader looking for "when does this become
        // true" needs.
        touches.saveAll(unclaimed);
    }

    /**
     * This visit again, if the same visitor arrived from the same place inside the
     * session interval.
     *
     * <p>Compared on the whole source rather than on the channel, because "the same
     * place" means the same place: somebody who reads the newsletter and then follows a
     * tweet within half an hour has done two things, and collapsing them would
     * attribute the pledge to whichever came first — the opposite of the rule.
     */
    private Optional<ReferralTouch> repeatOf(
            CaptureReferralVisit command, byte[] visitor, Instant now, AnalyticsProperties.Referral referral) {

        if (referral.repeatVisitInterval().isZero()) {
            // Configured off: every visit is recorded. For a deployment debugging its
            // own tagging, where "did that link send anything" is the question.
            return Optional.empty();
        }
        ReferralSource source = command.source();
        return touches
                .findRecent(
                        command.projectId(),
                        visitor,
                        now.minus(referral.repeatVisitInterval()),
                        PageRequest.of(0, RECENT_VISITS_READ))
                .stream()
                .filter(touch -> touch.getSource().equals(source))
                .findFirst();
    }
}

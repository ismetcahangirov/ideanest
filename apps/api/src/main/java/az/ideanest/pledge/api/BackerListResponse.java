package az.ideanest.pledge.api;

import az.ideanest.pledge.application.BackerPage;
import az.ideanest.shared.money.Money;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * §4.7's CD-10 on the wire: one page of a campaign's backers.
 *
 * <p>Money is a {@link Money} and therefore crosses as a string inside
 * {@code {"amount", "currency"}}, per §10.3. A table that received a double would round
 * somebody's pledge on the way to a cell.
 *
 * <p><strong>This body carries names and email addresses</strong>, which nothing else in
 * the dashboard does. The route is {@code Cache-Control: private, no-store} for that
 * reason and not merely by convention — a shared cache holding this body holds a
 * campaign's whole mailing list.
 *
 * @param nextCursor what to send as {@code ?cursor=} for the following page, absent when
 *     this is the last one
 * @param matched how many backers the filter matches on the campaign, not on this page
 * @param currency what every amount is in, absent when nothing matched
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BackerListResponse(List<Backer> backers, UUID nextCursor, long matched, String currency) {

    public static BackerListResponse of(BackerPage page) {
        return new BackerListResponse(
                page.backers().stream().map(Backer::of).toList(),
                page.nextCursor(),
                page.matched(),
                page.currency());
    }

    /**
     * One backer.
     *
     * @param anonymous whether they asked not to be named on the public page. The name is
     *     sent either way — {@link BackerPage} says why withholding it from the team that
     *     owes them a reward would be the wrong reading of PL-12 — and this flag is what
     *     lets the screen say so
     * @param rewardTitle the tier's current title, absent for support that took no reward
     * @param country the shipping destination, absent where the pledge named none
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Backer(
            UUID pledgeId,
            String name,
            String email,
            boolean anonymous,
            UUID rewardTierId,
            String rewardTitle,
            Money amount,
            String state,
            String country,
            Instant backedAt) {

        static Backer of(BackerPage.Backer backer) {
            return new Backer(
                    backer.pledgeId(),
                    backer.name(),
                    backer.email(),
                    backer.anonymous(),
                    backer.rewardTierId(),
                    backer.rewardTitle(),
                    backer.amount(),
                    backer.state().name(),
                    backer.country(),
                    backer.backedAt());
        }
    }
}

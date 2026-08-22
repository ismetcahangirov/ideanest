package az.ideanest.pledge.api;

import az.ideanest.pledge.application.PledgeDetail;
import az.ideanest.pledge.domain.PledgeSupplement;
import az.ideanest.shared.money.Money;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One purchase a backer made after the campaign closed — §4.8's PM-09 and PM-10.
 *
 * <p>Beside the pledge's own amounts rather than inside them, exactly as V39 stores
 * it: the pledge's total is what the campaign raised and what will be collected in its
 * batch, and this is a separate transaction. A client that added the two together and
 * showed one number would be telling a backer they are about to be charged something
 * nobody is going to charge them in one go.
 *
 * @param kind {@code UPGRADE} or {@code ADDONS}
 * @param amount what is owed for it. Always positive — a downgrade is a refund, and
 *     refunds are #67's
 * @param addons the lines, for an add-on purchase. Empty for an upgrade, which has the
 *     two tiers instead
 * @param collectedAt <strong>null on every supplement this platform holds.</strong>
 *     PM-16's charge is epic #59's, and the field is present rather than omitted so a
 *     client does not have to infer from its absence that nothing has been collected
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PledgeSupplementBody(
        UUID id,
        String kind,
        UUID fromRewardTierId,
        UUID toRewardTierId,
        Money amount,
        List<PledgeAddonBody> addons,
        Instant collectedAt,
        Instant createdAt) {

    /** Every purchase on this pledge, with its own lines beside it. */
    public static List<PledgeSupplementBody> of(PledgeDetail detail) {
        return detail.supplements().stream()
                .map(supplement -> of(supplement, detail))
                .toList();
    }

    private static PledgeSupplementBody of(PledgeSupplement supplement, PledgeDetail detail) {
        return new PledgeSupplementBody(
                supplement.getId(),
                supplement.getKind().name(),
                supplement.getFromRewardTierId(),
                supplement.getToRewardTierId(),
                Money.of(supplement.getAmount(), supplement.getCurrency()),
                detail.linesOf(supplement.getId()).stream()
                        .map(line -> new PledgeAddonBody(line.getRewardTierId(), line.getQuantity()))
                        .toList(),
                supplement.getCollectedAt(),
                supplement.getCreatedAt());
    }
}

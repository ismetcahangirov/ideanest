package az.ideanest.reward.application;

import az.ideanest.reward.domain.ShippingRule;
import az.ideanest.reward.domain.ShippingZone;
import az.ideanest.reward.domain.ShippingZoneCountry;
import az.ideanest.reward.domain.ShippingZoneRule;
import az.ideanest.reward.infrastructure.ShippingRuleRepository;
import az.ideanest.reward.infrastructure.ShippingZoneCountryRepository;
import az.ideanest.reward.infrastructure.ShippingZoneRuleRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "What does it cost to post this tier there" — §4.8's PM-11, and the one place
 * the answer is decided.
 *
 * <h2>Two tables can answer, and the more specific one always wins</h2>
 *
 * <p>Since #77 a destination can be priced twice: by name in {@code shipping_rules},
 * and by falling into a zone priced in {@code shipping_zone_rules}. The named
 * country wins, unconditionally.
 *
 * <p>That is a rule rather than a tie-break, and the difference is what makes it
 * safe. A creator who prices the European Union at 12 and then writes a row for
 * Germany at 8 has said something specific about Germany; the only reading of that
 * second row under which it means anything is that it overrides the first. The
 * alternatives are worse in the way that costs money quietly: "cheapest wins" lets
 * a creator lose on every German parcel by adding a zone, and "last written wins"
 * makes the amount a backer is charged depend on the order somebody happened to
 * type things in months ago.
 *
 * <p>A destination falls into <strong>at most one zone</strong> — V37 makes that
 * the primary key of {@code shipping_zone_countries} rather than a rule this class
 * enforces — so beyond that first comparison there is nothing to resolve. Zones
 * that could overlap would need a priority column, and a priority column is a thing
 * creators get wrong in a way they only discover from a carrier invoice.
 *
 * <h2>Three queries, whatever the size of the selection</h2>
 *
 * <p>The country rules for every selected tier, the zone the destination falls into,
 * and — only if it falls into one — that zone's rules for those same tiers.
 * Resolving per tier would be a query per add-on on the request a backer is waiting
 * on at checkout, which is the pattern that is only noticed once there are enough
 * checkouts for it to matter.
 *
 * <h2>Absent, never zero</h2>
 *
 * <p>A tier that neither table prices for a destination is missing from the result.
 * That distinction is the whole of §7.2's "anywhere the creator has priced": a
 * missing rate on a shipped line is a refusal, and substituting a zero would make
 * the creator pay the carrier out of their own funding without either party
 * noticing until the parcels went out.
 */
@Service
public class ShippingRates {

    private final ShippingRuleRepository countryRules;
    private final ShippingZoneCountryRepository zoneMemberships;
    private final ShippingZoneRuleRepository zoneRules;

    public ShippingRates(
            ShippingRuleRepository countryRules,
            ShippingZoneCountryRepository zoneMemberships,
            ShippingZoneRuleRepository zoneRules) {

        this.countryRules = countryRules;
        this.zoneMemberships = zoneMemberships;
        this.zoneRules = zoneRules;
    }

    /**
     * The rate each of these tiers charges to one destination, for the tiers that
     * price it at all.
     *
     * @param projectId the campaign the zones belong to. Zones are per campaign, so
     *     the destination cannot be resolved without it
     * @param destinationCountry ISO 3166-1 alpha-2, in any case. Null means the
     *     backer has not said where it goes, which is not the same as a destination
     *     nobody priced but comes out of the quote as the same refusal, because both
     *     are "this cannot be posted yet"
     * @return by tier identifier. A tier with no rate is absent — see the class
     *     comment
     */
    @Transactional(readOnly = true)
    public Map<UUID, ShippingRate> ratesTo(
            UUID projectId, Collection<UUID> rewardTierIds, String destinationCountry) {

        if (destinationCountry == null || rewardTierIds.isEmpty()) {
            return Map.of();
        }

        String destination;
        try {
            destination = ShippingZone.normaliseCountry(destinationCountry);
        } catch (IllegalArgumentException malformed) {
            // A destination that is not a country code prices nothing, and it is not
            // this class's place to refuse the request: the pledge module validates
            // the field a backer typed and reports it against that field. Answering
            // "nothing is priced" leaves the refusal where the message can name the
            // input.
            return Map.of();
        }

        Map<UUID, ShippingRate> rates = new HashMap<>();

        // The less specific answer first, so that the loop below simply overwrites
        // it. Written this way round rather than as a per-tier conditional because
        // "the named country wins" is then one line of code rather than a branch in
        // two places that have to agree.
        Optional<ShippingZoneCountry> zone = zoneMemberships.findDestination(projectId, destination);
        if (zone.isPresent()) {
            for (ShippingZoneRule rule : zoneRules.findByZoneAndRewardTiers(zone.get().getZoneId(), rewardTierIds)) {
                rates.put(
                        rule.getRewardTierId(),
                        new ShippingRate(
                                destination,
                                rule.getAmount(),
                                rule.getAdditionalItemAmount(),
                                rule.getPerKilogramAmount()));
            }
        }

        for (ShippingRule rule : countryRules.findByRewardTiers(rewardTierIds)) {
            if (destination.equals(rule.getCountryCode())) {
                rates.put(
                        rule.getRewardTierId(),
                        new ShippingRate(
                                rule.getCountryCode(),
                                rule.getAmount(),
                                rule.getAdditionalItemAmount(),
                                rule.getPerKilogramAmount()));
            }
        }

        return Map.copyOf(rates);
    }

    /**
     * Every destination this campaign prices at all, by name or through a zone.
     *
     * <p>What the creator's rate editor renders, and what the checkout would use to
     * populate a destination list. Deliberately not "every country in the world
     * minus the unpriced ones": a list built that way is a list a creator has to
     * read to discover what they have <em>not</em> done.
     */
    @Transactional(readOnly = true)
    public List<String> destinationsPricedBy(UUID projectId, UUID rewardTierId) {
        Map<String, UUID> zoneOf = new HashMap<>();
        for (ShippingZoneCountry membership : zoneMemberships.findByProject(projectId)) {
            zoneOf.put(membership.getCountryCode(), membership.getZoneId());
        }

        List<UUID> pricedZones = zoneRules.findByRewardTier(rewardTierId).stream()
                .map(ShippingZoneRule::getZoneId)
                .toList();

        return java.util.stream.Stream.concat(
                        countryRules.findByRewardTier(rewardTierId).stream().map(ShippingRule::getCountryCode),
                        zoneOf.entrySet().stream()
                                .filter(entry -> pricedZones.contains(entry.getValue()))
                                .map(Map.Entry::getKey))
                .distinct()
                .sorted()
                .toList();
    }
}

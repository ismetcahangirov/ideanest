package az.ideanest.reward.application;

import az.ideanest.reward.domain.ShippingZone;
import az.ideanest.reward.domain.ShippingZoneCountry;
import az.ideanest.reward.infrastructure.ShippingZoneCountryRepository;
import az.ideanest.reward.infrastructure.ShippingZoneRepository;
import az.ideanest.shared.Identifiers;
import az.ideanest.shared.access.ProjectAuthorisation;
import az.ideanest.shared.access.ProjectCapability;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A campaign's shipping regions — §4.8's PM-13 (#77).
 *
 * <h2>Replaced wholesale, like the rate tables they price</h2>
 *
 * <p>{@code PUT}, and the whole set. A zone left out of the body is a zone the
 * creator has removed, which is the same contract {@code PUT /v1/rewards/{id}/
 * shipping-rules} has had since V7 and for the same reason: a set of destinations is
 * read as a whole by whatever quotes from it, and merging would leave a creator
 * shipping to a region they believe they deleted.
 *
 * <p>A zone present in both the old and the new set <strong>keeps its
 * identifier</strong>, matched on its folded name. That is not a nicety: a tier's
 * rates name the zone by identifier, so deleting and recreating "EU" on every edit
 * would silently discard every rate every tier charges to it. Matching by name is
 * what makes renaming the one operation that has to be done deliberately — a rename
 * arrives as the same identifier with a different name, and the editor sends it that
 * way.
 *
 * <h2>{@code EDIT_REWARDS}, because a zone is part of what a backer is promised</h2>
 *
 * <p>The same capability that guards the rate tables and the tiers themselves.
 * §17's vocabulary calls it "items, reward tiers, shipping rules — what a backer is
 * promised", and a region is the shape of a shipping rule rather than a separate
 * kind of thing.
 */
@Service
public class ShippingZoneService {

    /**
     * How many regions one campaign may name.
     *
     * <p>A bound rather than a preference. Every zone is a row the checkout may have
     * to consider and a row in an editor somebody has to read, and a campaign with
     * two hundred regions has not modelled its tariff — it has modelled its country
     * list twice. Twenty-five is more than any real carrier agreement distinguishes.
     */
    private static final int ZONE_LIMIT = 25;

    /**
     * How many destinations one campaign may place in zones, across all of them.
     *
     * <p>Above the number of countries that exist, deliberately: the bound is a
     * runaway guard on a request body rather than a product rule, and a creator who
     * genuinely ships everywhere should not meet it.
     */
    private static final int MEMBERSHIP_LIMIT = 300;

    private final ShippingZoneRepository zones;
    private final ShippingZoneCountryRepository memberships;
    private final ProjectAuthorisation projects;

    public ShippingZoneService(
            ShippingZoneRepository zones,
            ShippingZoneCountryRepository memberships,
            ProjectAuthorisation projects) {

        this.zones = zones;
        this.memberships = memberships;
        this.projects = projects;
    }

    /**
     * The campaign's regions and what each covers.
     *
     * @throws az.ideanest.project.application.ProjectNotFoundException for a campaign
     *     that does not exist and for one this account has no part in, identically
     * @throws az.ideanest.project.application.CapabilityNotGrantedException without
     *     {@code EDIT_REWARDS}
     */
    @Transactional(readOnly = true)
    public List<ShippingZoneDetail> list(UUID projectId, UUID accountId) {
        projects.requireCapability(projectId, accountId, ProjectCapability.EDIT_REWARDS);
        return detailsOf(projectId);
    }

    /**
     * Makes the campaign's regions exactly the ones given.
     *
     * @throws ShippingZoneInvalidException when a zone has no name or no
     *     destinations, when two zones are named the same thing after folding, when a
     *     destination appears in two zones, or when either bound is exceeded
     */
    @Transactional
    public List<ShippingZoneDetail> replace(UUID projectId, UUID accountId, List<ZoneDefinition> definitions) {
        projects.requireCapability(projectId, accountId, ProjectCapability.EDIT_REWARDS);

        List<ZoneDefinition> requested = definitions == null ? List.of() : definitions;
        if (requested.size() > ZONE_LIMIT) {
            throw new ShippingZoneInvalidException(
                    "A campaign names at most " + ZONE_LIMIT + " shipping regions, not " + requested.size() + ".");
        }

        Map<String, ShippingZone> existing = new HashMap<>();
        for (ShippingZone zone : zones.findByProject(projectId)) {
            existing.put(fold(zone.getName()), zone);
        }

        Map<String, UUID> destinations = new HashMap<>();
        Set<String> keptNames = new LinkedHashSet<>();
        List<ShippingZone> result = new ArrayList<>();

        for (ZoneDefinition definition : requested) {
            if (definition == null) {
                throw new ShippingZoneInvalidException("A shipping region has a name and at least one destination.");
            }

            Set<String> countries;
            ShippingZone zone;
            try {
                countries = ShippingZone.normaliseCountries(
                        definition.countryCodes() == null ? List.of() : definition.countryCodes());
                // Matched on the folded name so that an unchanged zone keeps its
                // identifier and the rates that name it. See the class comment.
                String folded = fold(definition.name());
                zone = existing.get(folded);
                if (zone == null) {
                    zone = zones.save(ShippingZone.named(Identifiers.newIdentifier(), projectId, definition.name()));
                } else {
                    zone.rename(definition.name());
                }
                if (!keptNames.add(folded)) {
                    throw new ShippingZoneInvalidException(
                            "Two regions are called \"" + definition.name().trim() + "\".");
                }
            } catch (IllegalArgumentException rejected) {
                throw new ShippingZoneInvalidException(rejected.getMessage());
            }

            for (String country : countries) {
                UUID claimed = destinations.putIfAbsent(country, zone.getId());
                if (claimed != null && !claimed.equals(zone.getId())) {
                    // V37 refuses this with a primary key. Refusing it here means the
                    // creator reads which destination is in two regions rather than a
                    // constraint name.
                    throw new ShippingZoneInvalidException(
                            country + " is in two regions. A destination belongs to one region at most.");
                }
            }
            result.add(zone);
        }

        if (destinations.size() > MEMBERSHIP_LIMIT) {
            throw new ShippingZoneInvalidException(
                    "A campaign places at most " + MEMBERSHIP_LIMIT + " destinations in regions.");
        }

        applyMemberships(projectId, destinations);

        existing.forEach((folded, zone) -> {
            if (!keptNames.contains(folded)) {
                // Cascades to this zone's memberships and to every tier's rate for
                // it, which is what "the creator removed this region" means. The
                // named-country rates are untouched.
                zones.delete(zone);
            }
        });

        return detailsOf(projectId);
    }

    /**
     * The diff, destination by destination.
     *
     * <p>A country that stays keeps its row and is moved if its zone changed, rather
     * than being deleted and reinserted — the same reason {@code ShippingRule.reprice}
     * exists: Hibernate orders inserts before deletes inside a flush, so the pair
     * would collide on the primary key that makes a destination belong to one zone.
     */
    private void applyMemberships(UUID projectId, Map<String, UUID> destinations) {
        Map<String, ShippingZoneCountry> existing = new HashMap<>();
        for (ShippingZoneCountry membership : memberships.findByProject(projectId)) {
            existing.put(membership.getCountryCode(), membership);
        }

        destinations.forEach((country, zoneId) -> {
            ShippingZoneCountry stored = existing.get(country);
            if (stored == null) {
                memberships.save(ShippingZoneCountry.of(zoneId, projectId, country));
            } else {
                stored.moveTo(zoneId);
            }
        });

        existing.forEach((country, stored) -> {
            if (!destinations.containsKey(country)) {
                memberships.delete(stored);
            }
        });
    }

    private List<ShippingZoneDetail> detailsOf(UUID projectId) {
        Map<UUID, List<String>> countries = new HashMap<>();
        for (ShippingZoneCountry membership : memberships.findByProject(projectId)) {
            countries.computeIfAbsent(membership.getZoneId(), key -> new ArrayList<>())
                    .add(membership.getCountryCode());
        }
        return zones.findByProject(projectId).stream()
                .map(zone -> new ShippingZoneDetail(
                        zone.getId(), zone.getName(), List.copyOf(countries.getOrDefault(zone.getId(), List.of()))))
                .toList();
    }

    /** The comparable form V37's unique index folds to, so the two agree. */
    private static String fold(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}

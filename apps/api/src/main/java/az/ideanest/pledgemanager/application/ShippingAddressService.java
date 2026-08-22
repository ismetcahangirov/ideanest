package az.ideanest.pledgemanager.application;

import az.ideanest.audit.AuditAction;
import az.ideanest.audit.AuditActor;
import az.ideanest.audit.AuditLog;
import az.ideanest.audit.AuditOutcome;
import az.ideanest.pledge.application.BackedPledges;
import az.ideanest.pledgemanager.PledgeManagerProperties;
import az.ideanest.pledgemanager.domain.PostalAddress;
import az.ideanest.pledgemanager.domain.ShippingAddress;
import az.ideanest.pledgemanager.infrastructure.ShippingAddressRepository;
import az.ideanest.shared.access.ProjectAuthorisation;
import az.ideanest.shared.access.ProjectCapability;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * §4.8's PM-07 and PM-08 (#75): a backer saying where their reward goes, and a
 * creator freezing that answer.
 *
 * <h2>Two actors, two rules, one row</h2>
 *
 * <p>The <strong>backer</strong> writes the address and may rewrite it until the
 * creator locks it. The <strong>creator</strong> never writes one — they lock, and
 * they read. That asymmetry is deliberate: PM-18's bulk address editing is a separate
 * capability on a separate issue, and until it exists the only person who has ever
 * typed a backer's address is the backer.
 *
 * <p>Which means the two reads are guarded differently. A backer reads their own
 * address by owning the pledge; a creator reads the campaign's with
 * {@code VIEW_FINANCES} — the same capability the backer report needs, because a list
 * of who backed a campaign and where they live is the backer report with the most
 * sensitive column added.
 *
 * <h2>The address must go where shipping was quoted</h2>
 *
 * <p>{@code pledges.shipping_country} is what §4.5's PL-05 priced the parcel against,
 * and it is frozen at checkout. An address in a different country is one the backer
 * was never charged postage for, so it is refused rather than accepted — accepting it
 * silently makes the creator pay the difference on every parcel, and they find out
 * from a carrier invoice months later.
 *
 * <p>What a backer does instead is edit the pledge, which re-quotes. That is a worse
 * experience than an address form that quietly accepts anything, and it is the honest
 * one.
 *
 * <h2>Nothing here sees a street</h2>
 *
 * <p>Every route between the ciphertext and a readable address goes through
 * {@link AddressCipher}. This service holds a {@link PostalAddress} only for the
 * duration of one call, never logs one, and never puts one in an audit row —
 * {@code audit_logs} has no retention rule and refuses {@code DELETE}, so a home
 * address in it is a decision nobody can reverse.
 */
@Service
public class ShippingAddressService {

    private static final Logger log = LoggerFactory.getLogger(ShippingAddressService.class);

    private final ShippingAddressRepository addresses;
    private final BackedPledges pledges;
    private final AddressCipher cipher;
    private final ProjectAuthorisation projects;
    private final AuditLog audit;
    private final PledgeManagerProperties properties;
    private final Clock clock;

    public ShippingAddressService(
            ShippingAddressRepository addresses,
            BackedPledges pledges,
            AddressCipher cipher,
            ProjectAuthorisation projects,
            AuditLog audit,
            PledgeManagerProperties properties,
            Clock clock) {

        this.addresses = addresses;
        this.pledges = pledges;
        this.cipher = cipher;
        this.projects = projects;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The address on a pledge, as its backer sees it.
     *
     * @throws PledgeNotBackedException when the pledge does not exist, is not a
     *     backing, or belongs to somebody else — deliberately the same answer to all
     *     three, because distinguishing them turns the endpoint into an oracle for
     *     which pledge identifiers exist
     */
    @Transactional(readOnly = true)
    public Optional<StoredAddress> readOwn(UUID pledgeId, UUID backerId) {
        BackedPledges.BackedPledge pledge = requireOwnedPledge(pledgeId, backerId);
        return addresses.findById(pledge.pledgeId()).map(this::open);
    }

    /**
     * Records where this pledge's reward goes.
     *
     * @throws AddressStorageNotConfiguredException when the deployment has no
     *     encryption key. Refused rather than stored in the clear: an address written
     *     unencrypted would satisfy the request and quietly break the one promise §17.2
     *     makes about this table
     * @throws AddressLockedException once the creator has frozen it (PM-08)
     * @throws az.ideanest.pledgemanager.domain.AddressInvalidException when a required
     *     part is missing or a part is too long
     * @throws AddressDestinationMismatchException when the country is not the one the
     *     pledge was quoted for
     */
    @Transactional
    public StoredAddress save(UUID pledgeId, UUID backerId, PostalAddress address) {
        if (!properties.addresses().isConfigured()) {
            throw new AddressStorageNotConfiguredException();
        }

        BackedPledges.BackedPledge pledge = requireOwnedPledge(pledgeId, backerId);

        if (pledge.shippingCountry() == null) {
            // §4.5's PL-02, or a digital reward. There is nothing to post, so an
            // address is not merely unnecessary — it is personal data the platform
            // has no reason to hold, and §17.4's minimisation says not to take it.
            throw new AddressNotRequiredException(pledgeId);
        }
        if (!address.isGoingTo(pledge.shippingCountry())) {
            throw new AddressDestinationMismatchException(pledge.shippingCountry(), address.countryCode());
        }

        ShippingAddress stored = addresses
                .findById(pledgeId)
                .map(existing -> {
                    if (existing.isLocked()) {
                        throw new AddressLockedException(pledgeId, existing.getLockedAt());
                    }
                    existing.replaceWith(cipher.seal(address));
                    return existing;
                })
                .orElseGet(() -> addresses.save(ShippingAddress.of(
                        pledgeId, pledge.projectId(), backerId, cipher.seal(address))));

        // The act, never the content — see the class comment. The country is already
        // on the pledge, so recording it here reveals nothing new and is what makes
        // "which addresses changed after the labels were printed" answerable.
        log.debug("Address recorded for pledge {} on campaign {}", pledgeId, pledge.projectId());
        return open(stored);
    }

    /**
     * PM-08: freezes every address on the campaign so labels can be printed from them.
     *
     * <p>One statement, and only the addresses that are not already frozen — see
     * {@code ShippingAddressRepository.lockAll} for why the second half matters.
     *
     * <p><strong>Audited, unlike the backer's write.</strong> A lock is a privileged
     * action taken over other people's data by somebody who is not those people, and
     * "who stopped four thousand backers from correcting their address, and when" is
     * exactly the question §17.1 keeps an append-only table for.
     *
     * @return how many addresses this call froze
     */
    @Transactional
    public int lockAll(UUID projectId, UUID accountId) {
        projects.requireCapability(projectId, accountId, ProjectCapability.VIEW_FINANCES);

        Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int locked = addresses.lockAll(projectId, accountId, at);

        audit.record(
                AuditAction.PROJECT_ADDRESSES_LOCKED,
                projectId,
                AuditActor.user(accountId),
                AuditOutcome.SUCCEEDED,
                "locked=" + locked + "; stillEditable=" + addresses.countUnlocked(projectId));

        log.info("Campaign {} locked {} shipping addresses", projectId, locked);
        return locked;
    }

    /**
     * How many of the campaign's addresses have been given, and how many are still
     * editable.
     *
     * <p>The number a creator watches before they place a manufacturing order, and the
     * one read here that returns no address at all — which is why it is the read a
     * dashboard can poll.
     */
    @Transactional(readOnly = true)
    public AddressCollectionProgress progressOf(UUID projectId, UUID accountId) {
        projects.requireCapability(projectId, accountId, ProjectCapability.VIEW_FINANCES);

        long given = addresses.findByProject(projectId).size();
        long editable = addresses.countUnlocked(projectId);
        return new AddressCollectionProgress(given, given - editable, editable);
    }

    /**
     * The pledge, if this account holds it and it is a backing.
     *
     * <p>One refusal for three cases. A caller who does not own the pledge learns
     * nothing about whether it exists, which is the same rule {@code PledgeService}
     * applies to reading one.
     */
    private BackedPledges.BackedPledge requireOwnedPledge(UUID pledgeId, UUID backerId) {
        return pledges.pledge(pledgeId)
                .filter(pledge -> pledge.backerId().equals(backerId))
                .orElseThrow(() -> new PledgeNotBackedException(pledgeId));
    }

    /** The stored row with its envelope opened, which is the only place that happens. */
    private StoredAddress open(ShippingAddress stored) {
        return new StoredAddress(
                stored.getPledgeId(),
                cipher.open(stored.getSealed()),
                stored.getLockedAt(),
                stored.getUpdatedAt());
    }
}

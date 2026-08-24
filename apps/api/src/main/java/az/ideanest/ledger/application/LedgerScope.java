package az.ideanest.ledger.application;

import java.util.UUID;

/**
 * Which slice of the ledger is being read — AD-05, #305.
 *
 * <p>§4.11 asks for the ledger "readable by account and by campaign", and those are the two
 * filters, both optional and either combinable with the other. What differs from the payment
 * log's three mutually exclusive shapes is that these two <em>do</em> combine, because
 * {@code ledger_entries_account_idx} leads on {@code (account, project_id)} — asking for
 * escrow on one campaign uses the same index as asking for escrow.
 *
 * <p><strong>The account filter never changes what a posting shows.</strong> It decides
 * which postings appear; every posting that appears shows all of its entries. A ledger that
 * rendered one side of a double entry because that was the side you filtered on would be
 * showing a balance that does not balance, which is the one thing this table exists to make
 * impossible.
 *
 * @param account one of §7.2's six accounts, or null for all of them. A value rather than a
 *     string because {@link LedgerAccount} is the only sanctioned way to produce one V41's
 *     check constraint accepts — a typo here would silently return nothing, which reads as
 *     "this account has no entries"
 * @param projectId one campaign, or null for the whole platform
 */
public record LedgerScope(LedgerAccount account, UUID projectId) {

    /** Every posting the platform has made, newest first. */
    public static final LedgerScope EVERYTHING = new LedgerScope(null, null);
}

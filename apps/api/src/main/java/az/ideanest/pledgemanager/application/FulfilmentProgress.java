package az.ideanest.pledgemanager.application;

/**
 * How far a campaign's fulfilment has got — §4.8's PM-22, the creator's half.
 *
 * <p>Counts only. It names no backer and carries no tracking number, which is what
 * makes it the read a dashboard may poll — the same argument
 * {@link AddressCollectionProgress} makes for address collection.
 *
 * @param backings how many pledges the campaign has to fulfil. The denominator, and
 *     the reason it is here: {@code shipped} on its own is a number with nothing to
 *     compare it against
 * @param preparing how many have a row that says nothing has left yet
 * @param shipped how many are with a carrier
 * @param delivered how many arrived
 * @param returned how many came back — the count a creator acts on
 * @param untouched how many backings have no fulfilment row at all. <strong>Distinct
 *     from {@code preparing} on purpose</strong>: a parcel a creator has said nothing
 *     about and one a creator has explicitly marked as being packed are the same to a
 *     backer and completely different to the creator, who is looking for the ones they
 *     have not started
 */
public record FulfilmentProgress(
        long backings, long preparing, long shipped, long delivered, long returned, long untouched) {
}

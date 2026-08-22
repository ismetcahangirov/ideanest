package az.ideanest.pledgemanager.api;

/**
 * What a bulk lock did — §4.8's PM-08.
 *
 * @param locked how many addresses <em>this call</em> froze, which is not the same as
 *     how many are frozen: a second lock on a campaign that is already locked reports
 *     zero, and that is the honest number. A creator who reads "4,000 locked" after
 *     pressing the button twice would believe something happened the second time
 * @param stillEditable how many backers have not given an address yet and so have
 *     nothing to freeze. The number that says whether it is safe to print labels
 */
public record LockAddressesResponse(int locked, long stillEditable) {
}

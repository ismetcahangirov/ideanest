package az.ideanest.pledgemanager.application;

/**
 * How far a campaign has got with collecting addresses — §4.8's PM-07.
 *
 * <p>Three counts and no addresses, which is what makes this the read a dashboard can
 * poll: it passes through no ciphertext, decrypts nothing, and reveals nothing about
 * any individual backer.
 *
 * @param given how many pledges have an address at all
 * @param locked how many the creator has frozen (PM-08)
 * @param editable how many the backer may still change, which is the number to watch
 *     before placing a manufacturing order
 */
public record AddressCollectionProgress(long given, long locked, long editable) {
}

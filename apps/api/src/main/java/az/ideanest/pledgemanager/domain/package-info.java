/**
 * What a survey and an address are, and the rules that hold whoever asked for one.
 *
 * <p>Nothing here knows it is stored in PostgreSQL or reached over HTTP. In particular
 * {@code PostalAddress} is a value with no persistence of its own: what reaches the
 * database is a ciphertext, and {@code AddressCipher} is the only route between the
 * two.
 */
package az.ideanest.pledgemanager.domain;

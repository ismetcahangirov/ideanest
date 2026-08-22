/**
 * The pledge manager's tables, and the one place a key is read.
 *
 * <p>{@code AesGcmAddressCipher} is here rather than in {@code application} because it
 * is an adapter to key material a deployment configures — the port it implements is
 * declared beside the service that uses it.
 */
package az.ideanest.pledgemanager.infrastructure;

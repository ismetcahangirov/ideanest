package az.ideanest.pledgemanager.application;

/**
 * This deployment has no address encryption key.
 *
 * <p>§17.2 requires application-managed keys for {@code shipping_addresses} and
 * {@code PledgeManagerProperties.Addresses} explains why there is deliberately no
 * default one: a generated key changes on the next deploy and a committed key is a
 * published key.
 *
 * <p><strong>Refused rather than stored in the clear.</strong> Writing the address
 * unencrypted would satisfy the request and quietly break the only promise the schema
 * makes about this table, and nothing afterwards would distinguish those rows from the
 * encrypted ones.
 *
 * <p>503, and it is honest: the platform is temporarily unable to serve this feature,
 * the caller did nothing wrong, and the fix is an operator's.
 */
public class AddressStorageNotConfiguredException extends RuntimeException {

    public AddressStorageNotConfiguredException() {
        super("No address encryption key is configured for this deployment");
    }
}

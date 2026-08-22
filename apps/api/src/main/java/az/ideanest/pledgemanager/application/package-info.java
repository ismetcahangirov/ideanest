/**
 * What the pledge manager does: surveys, their answers, and postal addresses.
 *
 * <p>Where authorisation is asked and where the transaction boundary is. Every read of
 * a pledge goes through {@code pledge.application.BackedPledges} — this module owns no
 * pledge rows and may not read them.
 */
package az.ideanest.pledgemanager.application;

/**
 * Electronic signatures with the legal force of a handwritten one — §22, issue #428.
 *
 * <p><strong>Its own module, shaped like {@code payment}, for the same reasons.</strong> §9.4's
 * provider abstraction exists because "changing provider must be a single-file change", and
 * {@code PaymentProviderBoundaryTests} makes that checkable rather than aspirational. SİMA
 * İmza is one national provider today and ASAN İmza is the obvious second, so the same
 * discipline applies from the start rather than after the second one arrives.
 *
 * <p>Callers name {@code signature.application.SignatureProviders} and get a
 * {@code SignatureProvider}. Nothing outside this module names a {@code SignatureRequest}, a
 * {@code SignatureResult} or a {@code SignatureProviderName} — {@code SignatureProviderBoundaryTests}
 * asserts it, exactly as the payment module's does.
 *
 * <p><strong>Nothing outside this module references any of it yet.</strong> #428 ships the
 * mechanism and #429 is the caller: it is #429 that has a citizen sign the creator agreement
 * and binds the signature to the acceptance. A module nothing calls is the correct state for
 * one whose adapter may not be pointed at production SİMA until #423 answers what personal
 * data may be kept and for how long.
 */
package az.ideanest.signature;

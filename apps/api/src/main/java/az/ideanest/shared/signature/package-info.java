/**
 * Signing a document, as it crosses a module boundary — issue #429.
 *
 * <p>#428's {@code SignatureProvider} is a provider abstraction and stays where it is: the rule
 * it exists to enforce is that "no provider SDK is called anywhere except behind this
 * interface", and a legal module that named {@code StoredSignature} or {@code SignatureSession}
 * would be a second module holding the provider's vocabulary. This package is the other half of
 * that rule — what a caller may say and hear, with no SİMA in it at all.
 *
 * <p>The shape is {@code shared.legal}'s and {@code shared.compliance}'s: a question, a small
 * record for the answer, and an implementation in the module that owns the rows.
 * {@code signature.application.DocumentSignatures} is the answerer.
 *
 * <p>Nothing here carries a signature value or a certificate. What crosses is an identifier, a
 * name, a FİN and a time — enough for the legal module to bind a signature to an acceptance and
 * to refuse a name that does not match, and not enough for it to become a second place a
 * citizen's certificate material sits. V67's header is the argument, unchanged.
 */
package az.ideanest.shared.signature;

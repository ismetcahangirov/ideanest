/**
 * §22.2's eight documents, their versions, and the record that somebody accepted one.
 *
 * <p><strong>Its own module because three others need it and none should own it.</strong>
 * The project module refuses a submission without an accepted creator agreement (#426); the
 * pledge module refuses a confirmation without an accepted backer agreement (#427); the
 * console publishes versions and reads what an account has agreed to. Putting the documents
 * in {@code project} would mean the checkout reached into the campaign module to record a
 * backer's acknowledgement, and putting them in {@code auth} would make the terms of use a
 * property of a login.
 *
 * <p>Callers name {@code shared.legal.Agreements} and get an {@code AgreementInForce}.
 * Nothing outside this module names {@code LegalDocument} or {@code DocumentAcceptance}, or
 * reads {@code legal_documents} and {@code document_acceptances}.
 *
 * <p><strong>No text ships here.</strong> The eight kinds exist; the words are #423's
 * adviser's and #439 publishes them. A module that seeded its own terms of use would be this
 * repository writing a legal position, and it is the position a regulator would read back to
 * us.
 *
 * <p><strong>The gates fail open, deliberately</strong>, which is the opposite of the
 * subscription gate beside them. {@code shared.legal.Agreements} carries the argument: an
 * agreement that has not been published is not a requirement, because the alternative is a
 * platform that refuses every campaign and every pledge with a message telling people to
 * accept a document that does not exist.
 */
package az.ideanest.legal;

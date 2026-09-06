/**
 * Who a creator legally is, and whether the platform has checked — published where the
 * gating modules may name it.
 *
 * <p>{@code shared.legal}'s argument, applied to the second half of epic #421's question.
 * That package exists because the project module refuses a submission on the strength of a
 * row in {@code document_acceptances} without being allowed to read the table; this one
 * exists because the payout module refuses to release money on the strength of a row in
 * {@code identity_verifications}, and the compliance module writes an agreement's
 * signature against a name held in {@code creator_legal_subjects}. Three modules, three
 * tables, none of them each other's.
 *
 * <p><strong>{@link az.ideanest.shared.compliance.SubjectKind} and
 * {@link az.ideanest.shared.compliance.RejectionReason} were moved here from
 * {@code verification.domain} rather than copied.</strong> #429 says it plainly — "two
 * vocabularies for one idea is one too many" — and a second {@code MISMATCHED_NAME},
 * declared in the module that happened to need it next, is how a closed set stops being
 * closed. V58 chose the values and the reasoning behind them is unchanged; only the
 * package is, so that a module which must name a rejection reason need not reach into the
 * verification module's internals to do it.
 *
 * <p>Nothing here holds a document, a decision or an identity. The identity documents are
 * V58's, encrypted and swept; the decision is a reviewer's; the legal subject's own row is
 * the compliance module's. What crosses is the answer, never the evidence.
 */
package az.ideanest.shared.compliance;

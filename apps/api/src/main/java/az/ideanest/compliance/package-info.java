/**
 * Exceptions to compliance requirements: who was let past which rule, by whom, and until
 * when — §3.1 and issue #436.
 *
 * <p><strong>Its own module because the requirements it waives belong to four others.</strong>
 * An override applies to an identity verification, a legal subject, a payout destination or a
 * signature on the creator agreement; those live in {@code identity}, {@code legal} and
 * {@code payout}, and putting the override beside any one of them would mean the other three
 * reached into that module to ask whether their own rule had been waived.
 *
 * <p>Callers name {@code compliance.application.ComplianceOverrides} and ask one question —
 * "does this account have a live override of this requirement" — which is the only question a
 * gate has. Nothing outside this module names {@code ComplianceOverride} or reads
 * {@code compliance_overrides}.
 *
 * <p>The gates themselves are #429 to #432 and are not built here. This module ships the
 * record and the answer; wiring it into a refusal is the issue that owns that refusal, and an
 * override nothing consults yet is a row an auditor can read rather than a control that
 * silently does nothing.
 */
package az.ideanest.compliance;

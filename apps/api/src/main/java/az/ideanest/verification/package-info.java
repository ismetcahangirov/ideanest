/**
 * Identity verification for creators — §22.1's anti-money-laundering row, §5.4's R6,
 * issue #105.
 *
 * <p>Document capture, restricted access, and a retention limit. <strong>Not a
 * threshold.</strong> §22.1 lists "identity verification thresholds for creators" among
 * the questions requiring a specific legal answer and #71 carries
 * {@code status: needs-decision}; a threshold invented here would be a compliance position
 * this repository made up. So nothing on the platform is gated on the outcome yet, and
 * {@code VerificationProperties} is where the gate goes when somebody may decide it.
 */
package az.ideanest.verification;

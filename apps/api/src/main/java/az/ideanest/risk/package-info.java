/**
 * Automated fraud signals — §17.2, §4.11's AD-02, issue #108.
 *
 * <p>It correlates rather than owns: how many pledges an account has made lately, how old
 * the account is, which addresses it has been seen from. None of those facts belong to
 * this module and none of them are read through another module's classes — {@code
 * RiskFacts} asks in SQL, on the argument {@code NotificationRecipients} already makes for
 * reading {@code users} from the notification module.
 *
 * <p><strong>It advises. It does not decide.</strong> Nothing here refuses a pledge,
 * holds money, or suspends an account. See {@code RiskAssessments}.
 */
package az.ideanest.risk;

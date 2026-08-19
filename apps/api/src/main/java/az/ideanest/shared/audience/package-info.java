/**
 * Who a message about a campaign goes to, asked from outside the module that knows.
 *
 * <p>{@code shared.access} publishes the question "may this account do this"; this package
 * publishes "who are these people". Both exist for the same reason and neither decides
 * anything: the answer lives in the module that owns the rows, and the interface exists so
 * that a caller depends on the question rather than on the class that answers it.
 *
 * <p>The problem it solves is #245's. §4.10 has notifications whose audience is a list the
 * platform computes — a campaign's backers, a creator's followers — and the notification
 * module cannot compute one: a backer is a row in {@code pledges} and a follower is a row the
 * discovery module will own, and reading either from {@code notification} is exactly the
 * coupling {@code ModuleBoundaryTests} exists to prevent. The two alternatives were an event
 * that carries the audience — ten thousand identifiers in a message, which is the wrong shape
 * for an event — and this.
 */
package az.ideanest.shared.audience;

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
 * module cannot compute one: a backer is a row in {@code pledges} and a follower is a row in
 * {@code follows}, and reading either from {@code notification} is exactly the coupling
 * {@code ModuleBoundaryTests} exists to prevent. The two alternatives were an event that
 * carries the audience — ten thousand identifiers in a message, which is the wrong shape for
 * an event — and this.
 *
 * <h2>Three types, and what each is for</h2>
 *
 * <ul>
 *   <li>{@link az.ideanest.shared.audience.ProjectAudience} is the vocabulary: the names a
 *       caller may ask for.
 *   <li>{@link az.ideanest.shared.audience.ProjectAudiences} is the question, and the only
 *       type a caller names. There is exactly one bean of it.
 *   <li>{@link az.ideanest.shared.audience.ProjectAudienceSource} is a module's answer for the
 *       audiences whose rows it owns, and
 *       {@link az.ideanest.shared.audience.RoutedProjectAudiences} is what joins them up.
 * </ul>
 *
 * <p>The split arrived with #90. Until {@code saves} and {@code follows} existed the pledge
 * module owned every audience there was, so the question and the answer could be one interface
 * with one implementation. They are two modules now, and the router is also where an audience
 * nobody claims becomes a start-up failure instead of a comment asking people not to add one.
 */
package az.ideanest.shared.audience;

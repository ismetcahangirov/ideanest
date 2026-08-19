/**
 * The notification module's HTTP surface, and the bodies it binds.
 *
 * <p>§10.2's two account-scoped notification endpoints, plus the read the settings page
 * and the read stamp the inbox need. Everything here is under {@code /v1/me}: a
 * notification belongs to one person, there is no path on which one is public, and no
 * endpoint in this package takes a recipient from anything the caller could choose.
 *
 * <p>None of these paths appears in {@code SecurityConfiguration}, so all of them fall
 * through to the catch-all rule and require a bearer token from an account that is not
 * inside §17.4's deletion grace period. That last part is deliberate rather than
 * incidental: an account that has asked to be deleted has no business rewriting the
 * preferences that decide what it is sent while it closes.
 */
package az.ideanest.notification.api;

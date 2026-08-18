/**
 * The community module's HTTP surface, and the bodies it binds.
 *
 * <p>Two controllers on one path for updates, split by audience rather than by
 * resource: one requires a bearer token and one must not. See
 * {@code PublicProjectUpdateController} for why the public read still looks at the token
 * when there is one.
 */
package az.ideanest.community.api;

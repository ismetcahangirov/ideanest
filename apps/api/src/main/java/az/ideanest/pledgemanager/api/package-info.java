/**
 * The pledge manager's HTTP surface, and the bodies it binds.
 *
 * <p>Two audiences on separate resources: a backer answering a survey or giving an
 * address does it on their own pledge, and a creator building a survey or locking
 * addresses does it on the campaign. No endpoint here returns one backer's answer to
 * another.
 */
package az.ideanest.pledgemanager.api;

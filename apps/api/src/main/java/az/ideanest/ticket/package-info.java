/**
 * Support conversations, with the account context they are about — §4.11's AD-10, issue
 * #310.
 *
 * <p><strong>{@code ticket} rather than {@code support}</strong>, which is what §4.11 calls
 * the module. The test source set already has an {@code az.ideanest.support} package
 * holding the integration fixtures — {@code AbstractIntegrationTest}, {@code Campaigns},
 * {@code Pledges} — and a production package of the same name would merge with it in every
 * IDE and in every stack trace. The rows are tickets; the name says so.
 */
package az.ideanest.ticket;

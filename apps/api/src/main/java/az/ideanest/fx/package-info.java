/**
 * §21.2's display currency: central bank rates, cached hourly — issue #327.
 *
 * <h2>What this module is, in one sentence</h2>
 *
 * It turns an amount the platform will actually charge into an approximation of that amount
 * in a currency the reader thinks in, and it records which rate it used.
 *
 * <h2>WHAT IT IS NOT, AND THE DISTINCTION IS THE WHOLE MODULE</h2>
 *
 * <strong>Nothing here decides what anybody is charged.</strong> §21.2: "Display currency —
 * user preference, shown as an <em>approximation</em>; collection occurs in the project
 * currency." A campaign priced at ₼50 collects ₼50 from every backer, and what this module
 * produces is the "≈ $29" beside it.
 *
 * <p>That is why {@code Money} is not asked to do any of it. {@code Money} refuses
 * arithmetic between two currencies with a {@code CurrencyMismatchException}, deliberately
 * and correctly, because §21.2's rate is an approximation and never the basis of a
 * collection. This module is built <em>around</em> that refusal rather than through it: the
 * division happens on plain {@link java.math.BigDecimal}, and a {@code Money} is
 * constructed from the result at the end. Two amounts in different currencies never meet.
 *
 * <h2>Why the issue said this was not next, and why it is here anyway</h2>
 *
 * #327 argued that a display currency is only worth building "once §21.2's phase 2 gives
 * the platform a second currency to convert into", because {@code SUPPORTED_CURRENCY} is
 * {@code AZN} in three services and "a display-currency selector today would convert AZN to
 * AZN".
 *
 * <p>That reads the two currencies as one. The <strong>project</strong> currency is the one
 * pinned to AZN — it is what a creator sets a goal in and what a card is charged in, and
 * §21.2's phase 2 is what widens it. The <strong>display</strong> currency is a property of
 * the reader, not of the campaign: a backer in Istanbul looking at a manat campaign wants to
 * know roughly what it costs in lira, and that question is answerable today with a real rate
 * from a real central bank. Nothing in phase 1 stands in its way.
 *
 * <h2>Layout</h2>
 *
 * <ul>
 *   <li>{@code domain} — the stored rate.
 *   <li>{@code application} — the port a source implements, the cache over the table, and
 *       the conversion. {@code ExchangeRates} is what every other module talks to.
 *   <li>{@code infrastructure} — the Central Bank of Azerbaijan adapter and the repository.
 *   <li>{@code api} — the public read the web and mobile clients convert with.
 * </ul>
 *
 * <h2>Everything degrades to absence, never to a guess</h2>
 *
 * A source that cannot be reached, a currency with no published rate, a rate older than the
 * configured limit, and a deployment with the feature switched off all produce the same
 * answer: <strong>no approximation</strong>. A converted figure computed from a stale or
 * invented rate is worse than no figure at all, because a backer acts on it. Every method
 * here returns an {@link java.util.Optional} for that reason rather than a fallback.
 */
package az.ideanest.fx;

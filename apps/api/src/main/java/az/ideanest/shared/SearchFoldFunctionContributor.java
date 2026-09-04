package az.ideanest.shared;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

/**
 * Teaches JPQL the name of §11.3's fold — issue #413.
 *
 * <h2>What was wrong without it</h2>
 *
 * <p>The platform has one definition of how a word is folded for comparison, written twice on
 * purpose: {@link Slugs#fold} in Java and {@code ideanest_fold(text)} in the database (V13),
 * pinned to each other by {@code SearchFoldingTests}. Public search, the campaign directory and
 * V63's two account indexes are all built on the database half.
 *
 * <p>{@code /admin/users} was not, and could not be. {@code UserRepository.search} is JPQL,
 * JPQL may only call functions the dialect knows about, and nothing had registered this one —
 * so that query matched on {@code lower()} since #104. Two consequences, and the second is the
 * one staff noticed first:
 *
 * <ul>
 *   <li><strong>It read every row.</strong> An expression index serves a query that repeats the
 *       expression exactly, and {@code lower(name)} is not {@code ideanest_fold(name)}. V63
 *       built {@code users_name_trgm_idx} and {@code users_slug_trgm_idx} and said out loud
 *       that the account directory did not use them.
 *   <li><strong>It folded differently from every other search on the platform.</strong>
 *       {@code lower()} leaves {@code ə}, {@code ı}, {@code ö}, {@code ü}, {@code ğ},
 *       {@code ş} and {@code ç} alone, so this screen found "Köhnə" from {@code köhnə} and not
 *       from {@code kohne}, while the campaign directory beside it found both. A console with
 *       two spellings of one rule is a console where staff learn which box needs the right
 *       keyboard.
 * </ul>
 *
 * <h2>Why a registration and not a second copy of the query</h2>
 *
 * <p>#413 offers two ways out: register the function, or move the read to
 * {@code NamedParameterJdbcTemplate} as {@code CampaignDirectoryRows} did. That class exists
 * because its query has four optional predicates and sixteen shapes, which Spring Data cannot
 * express without a nullable parameter — a real reason, and not this one. The account
 * directory's read is one shape with a keyset and a boolean; rewriting it as assembled SQL
 * would trade a working query for a hand-mapped result set to gain a function name.
 *
 * <p>Registered once and globally rather than per query, because the fold is one rule. The day
 * a second JPQL read needs to match a folded column it names {@code ideanest_fold} and is
 * index-backed for free, which is the property that was missing.
 *
 * <h2>{@code registerPattern}, deliberately</h2>
 *
 * <p>The pattern form renders the call and validates nothing about its argument, which is what
 * lets {@code ideanest_fold(u.email)} compile. {@code users.email} is {@code citext} behind an
 * {@link EmailAddressConverter}, so its SQM path is an {@code EmailAddress} rather than a
 * string; a registration with an arguments validator would refuse it, and the query would have
 * to cast — which would put {@code cast(email as varchar(255))} inside the expression and make
 * the index unusable again for a reason nobody would find twice. PostgreSQL's implicit
 * {@code citext → text} cast resolves the call itself.
 *
 * <p><strong>Discovered by {@link java.util.ServiceLoader}, not by Spring.</strong> Hibernate
 * builds its function registry before any application context exists, so this is registered in
 * {@code META-INF/services/org.hibernate.boot.model.FunctionContributor}. A file, not an
 * annotation — which is worth knowing, because deleting it makes every query that names the
 * fold fail at startup rather than at the call.
 */
public class SearchFoldFunctionContributor implements FunctionContributor {

    /** The name in both languages. It is the same word in JPQL as it is in SQL, on purpose. */
    public static final String FUNCTION = "ideanest_fold";

    @Override
    public void contributeFunctions(FunctionContributions functions) {
        functions.getFunctionRegistry()
                .registerPattern(
                        FUNCTION,
                        FUNCTION + "(?1)",
                        functions.getTypeConfiguration()
                                .getBasicTypeRegistry()
                                .resolve(StandardBasicTypes.STRING));
    }
}

package az.ideanest.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may read {@code /actuator/prometheus} — §18, issue #138.
 *
 * <h2>Why this is not simply another matcher on the main chain</h2>
 *
 * Because a scraper cannot present the credential the main chain understands. Every other
 * endpoint on this service authenticates with a bearer JWT this platform signed for a person;
 * Prometheus has no account, no refresh token and no way to obtain one, and giving it a
 * long-lived token for a service account would be inventing an identity type to solve a
 * scraping problem. HTTP Basic is what every scraper already speaks, and a separate chain is
 * how one path gets a different authentication scheme without weakening the other.
 *
 * <p>Ordered ahead of {@code SecurityConfiguration#apiSecurity}, which matches everything.
 *
 * <h2>NO CREDENTIAL, NO ENDPOINT</h2>
 *
 * This whole configuration is conditional on {@code ideanest.metrics.scrape.password} being
 * set, and `application.yml` exposes the Prometheus endpoint only alongside it. A deployment
 * that has not configured a credential does not get an unauthenticated metrics endpoint — it
 * gets no metrics endpoint at all.
 *
 * <p>That is the same rule the cache-invalidation endpoint follows and it matters more here.
 * Metrics are not confidential in the way a pledge is, but they are a map: queue depths,
 * provider availability, JVM internals, and every URI template the application serves. Read
 * continuously by a stranger, that is reconnaissance and a rate-limit-free way to measure when
 * the platform is under strain.
 *
 * <h2>Basic over TLS, and the deployment's job</h2>
 *
 * HTTP Basic sends the password on every request, so it is only as private as the transport.
 * The service is behind TLS termination in every environment `docs/architecture.md` describes,
 * and `ops/deploy/README.md` says so where somebody configuring a scrape will read it. A
 * scrape over plain HTTP inside a private network is a decision the operator makes; this class
 * cannot make it for them and does not pretend to.
 */
/*
 * A blank password is not a password. `@ConditionalOnProperty` would match one, because
 * `application.yml` declares the key with an empty default so that an operator can find it —
 * and matching would give the platform an unauthenticated metrics endpoint guarded by an
 * account whose password is the empty string. The expression is what makes "configured" mean
 * what the class comment says it means.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${ideanest.metrics.scrape.password:}' != ''")
public class MetricsScrapeSecurity {

    /** The one path this chain claims. Everything else falls through to the API's own. */
    private static final String SCRAPE_PATH = "/actuator/prometheus";

    /**
     * Ahead of the API chain, which matches every request.
     *
     * <p>{@code HIGHEST_PRECEDENCE + 10} rather than {@code 0}: it leaves room for a chain that
     * must come first — a maintenance mode, an IP deny list — without renumbering this one.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    /*
     * Named for the chain rather than for the class. A `@Bean` method whose name matches its
     * own configuration class's bean name collides with it, and Spring refuses the definition
     * outright — which takes the whole context down at start-up.
     */
    public SecurityFilterChain metricsScrapeFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher(SCRAPE_PATH)
                /*
                 * CSRF disabled and sessions stateless, for the API chain's reasons: there is
                 * no browser here and no cookie to forge, and a scrape that created a session
                 * every fifteen seconds would be a slow memory leak with a schedule.
                 */
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().hasRole("METRICS"))
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    /**
     * The one account that may scrape.
     *
     * <p>In memory rather than in {@code users}. A scraper is not a person: it has no email to
     * verify, no password to reset, no sessions to revoke and no rows to delete when it is
     * decommissioned, and putting it in the account table would give every query that counts
     * users one row that is not one. It is configuration, and it lives in configuration.
     *
     * <p>{@code {noop}} is deliberate and is not a shortcut past hashing. The value comes from
     * a deployment secret that is already only readable by the process; hashing it in the
     * configuration file would mean an operator generating an Argon2 digest by hand to change
     * a scrape password, and the digest would protect against an attacker who could already
     * read the environment of a running JVM.
     */
    @Bean
    public InMemoryUserDetailsManager metricsScrapeUser(MetricsScrapeProperties properties) {
        return new InMemoryUserDetailsManager(User.withUsername(properties.username())
                .password("{noop}" + properties.password())
                .roles("METRICS")
                .build());
    }
}

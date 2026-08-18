package az.ideanest.config;

import az.ideanest.auth.application.AccessTokenIssuer;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Who may reach what.
 *
 * <p>Deny by default. Every endpoint added from here on is unreachable until
 * someone states who may call it, which is the right way round: forgetting to
 * protect a new endpoint should produce a 401 in a test, not an open door in
 * production.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    /**
     * Held by an account that is not being deleted.
     *
     * <p>Stated as something an active account <em>has</em> rather than as
     * something a closing one lacks, because the two fail in opposite
     * directions. A missing authority denies; a missing "is closing" flag
     * allows — so a token from an older release, a claim that failed to
     * serialise, or a converter that was not applied would all quietly let a
     * closed account carry on.
     */
    private static final String ACCOUNT_ACTIVE = "ACCOUNT_ACTIVE";

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(requests -> requests
                        // The platform reads these to decide whether this
                        // instance takes traffic. They carry a status and no
                        // component detail; see application.yml.
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        // The category tree. Public because it is the discovery
                        // navigation: the same list, with nothing in it that
                        // belongs to a person, and cached for an hour. Read only —
                        // GET and nothing else, so that a write method added under
                        // the same path later does not inherit this.
                        .requestMatchers(HttpMethod.GET, "/v1/categories")
                        .permitAll()
                        // Browsing, and the counts beside it. Public because
                        // discovery is the front door: a visitor who has not
                        // registered is exactly the audience it exists for, and
                        // requiring a token would mean the platform's own home
                        // page could not render.
                        //
                        // Nothing in either response belongs to a person, which
                        // is what lets them be Cache-Control: public. The one
                        // filter that would change that is showOnly=saved, and
                        // it is refused today — DiscoveryController carries the
                        // note about what has to change here when it is not.
                        //
                        // GET and nothing else, for the reason the categories
                        // rule gives.
                        //
                        // /v1/search and /v1/search/suggest are the same feed and
                        // the same reasoning with a query term attached (#43): a
                        // visitor who has not registered searching before deciding
                        // whether to is the audience, and a search box that demanded
                        // a token would be a search box nobody uses.
                        //
                        // /v1/collections and /v1/collections/{slug} are the same
                        // reasoning again (#48): D-08's curated collections and
                        // open-call landing pages are what the platform points a
                        // visitor at, and a staff-picks page behind a sign-in wall
                        // would be a staff-picks page nobody sees. Only collections
                        // the platform has published are reachable — an unpublished
                        // one answers 404 rather than 403, so this rule does not make
                        // an editorial decision in progress readable. The writes are
                        // under /v1/admin/collections and fall through to the rule
                        // below.
                        .requestMatchers(
                                HttpMethod.GET,
                                "/v1/discover",
                                "/v1/discover/facets",
                                "/v1/search",
                                "/v1/search/suggest",
                                "/v1/collections",
                                "/v1/collections/*")
                        .permitAll()
                        // A campaign's pre-launch page. Public because the people
                        // it exists to collect have not registered — that is what
                        // makes it a pre-launch page rather than a second editor
                        // screen. What may be read is not decided here: the
                        // handler serves it only for a campaign in PRELAUNCH or
                        // SCHEDULED and answers 404 for everything else,
                        // including a draft, so that this endpoint cannot be used
                        // to enumerate what other people are preparing.
                        //
                        // GET and nothing else, deliberately, for the reason the
                        // categories rule gives: POST on this exact path is the
                        // creator's DRAFT -> PRELAUNCH transition, and it falls
                        // through to the rule at the bottom.
                        .requestMatchers(HttpMethod.GET, "/v1/projects/*/prelaunch")
                        .permitAll()
                        // A campaign's reward list as a backer sees it. Public
                        // because §4.5 opens the pledge flow with it and the
                        // person reading it is deciding whether to register at
                        // all. What may be read is not decided here: the handler
                        // serves it only for a campaign in one of §6.1's nine
                        // public states and answers 404 otherwise, it omits
                        // secret tiers unless the request carries the token, and
                        // it omits tiers outside their availability window.
                        //
                        // GET and nothing else, for the reason the categories
                        // rule gives. Note that the creator's own reward list at
                        // /v1/projects/*/rewards is a different path and is not
                        // matched here: it falls through to the rule at the
                        // bottom and still requires a token, which is what keeps
                        // secret tiers and reservation counts out of reach.
                        .requestMatchers(HttpMethod.GET, "/v1/projects/*/rewards/public")
                        .permitAll()
                        // A campaign's backers as its page shows them (#57).
                        // Public because §4.4 puts the backer count in the
                        // header, a count beside every reward tier, and the
                        // community statistics on a tab — all of it on a page
                        // a visitor reads before deciding whether to register.
                        //
                        // What may be read is not decided here. The handler
                        // serves it only for a campaign in one of §6.1's nine
                        // public states and answers 404 otherwise, it counts
                        // only pledges that were confirmed rather than drafts
                        // in flight, and it carries no identity at all for a
                        // backer who asked to be anonymous — §4.5's PL-12,
                        // which PublicBacker enforces by having nowhere to put
                        // one rather than by remembering to omit it.
                        //
                        // GET and nothing else, for the reason the categories
                        // rule gives. Note that the creator's backer list at
                        // /v1/projects/*/backers is a different path and is
                        // deliberately not matched here: it is §10.2's
                        // dashboard endpoint, it names every backer including
                        // the anonymous ones because the creator has to ship to
                        // them, and it falls through to the rule at the bottom.
                        .requestMatchers(HttpMethod.GET, "/v1/projects/*/backers/public")
                        .permitAll()
                        // ---- #83: project updates -------------------------
                        // A campaign's Updates tab (§4.4). Public because §10.2
                        // lists this read under "Project — public" and because
                        // §5.5 makes updates the record of whether a creator
                        // keeps their promises — which is exactly what somebody
                        // reads before deciding to become a backer.
                        //
                        // What may be read is not decided here. The handler
                        // serves it only for a campaign in one of §6.1's nine
                        // public states and answers 404 otherwise, it omits
                        // updates whose publication time has not arrived, and it
                        // omits backers-only ones — see ProjectUpdateService,
                        // which is also where the campaign's own team is told
                        // apart from everybody else when a token is presented.
                        //
                        // GET and nothing else, for the reason the categories
                        // rule gives: POST on this exact path is the creator
                        // publishing an update, and it falls through to the rule
                        // at the bottom so that an account inside its deletion
                        // grace period cannot make new promises to backers.
                        .requestMatchers(HttpMethod.GET, "/v1/projects/*/updates")
                        .permitAll()
                        // ---- end #83 --------------------------------------
                        // ---- #84: comments --------------------------------
                        // A campaign's Comments tab (§4.4). Public because §10.2
                        // lists this read under "Project — public", and because
                        // what a visitor is weighing before they back a campaign
                        // is frequently the creator's answer to the question
                        // somebody else already asked.
                        //
                        // What may be read is not decided here. The handler
                        // serves it only for a campaign in one of §6.1's nine
                        // public states and answers 404 otherwise, and a removed
                        // comment is served as a tombstone with no text and no
                        // author — see CommentResponse, which is the one place
                        // that decision is made.
                        //
                        // GET and nothing else. POST on this exact path is
                        // somebody writing a comment, and it falls through to
                        // the rule at the bottom so that an anonymous caller and
                        // an account inside its deletion grace period are both
                        // refused. DELETE /v1/comments/* and the reply and report
                        // routes are deliberately not matched here either.
                        .requestMatchers(HttpMethod.GET, "/v1/projects/*/comments")
                        .permitAll()
                        // ---- end #84 --------------------------------------
                        // "Tell me when this opens", and "stop reminding me".
                        // Unauthenticated on purpose and bounded in the handler by
                        // a rate limiter per source address and per email address:
                        // an open write that promises to send mail is an open
                        // relay if it is left unbounded. The DELETE is public
                        // because the credential it accepts is the token in the
                        // message itself — an unsubscribe that required signing in
                        // would not be an unsubscribe.
                        .requestMatchers(HttpMethod.POST, "/v1/projects/*/remind")
                        .permitAll()
                        // "Somebody arrived here, from there" (#94). Public
                        // because the visits that decide an attribution happen
                        // before anybody signs in: a visitor reads a campaign
                        // for a week and registers at checkout, and an endpoint
                        // that required a token would only ever see the last
                        // step of that journey. Every campaign would then appear
                        // to convert nobody except the people who arrived
                        // already logged in, which is the one number §4.7's
                        // CD-03 exists to get right.
                        //
                        // An unauthenticated write, and bounded like the other
                        // one above: per source address, in the handler. It is
                        // not fraud protection and ReferralController says so —
                        // what actually keeps invented sources out of a
                        // creator's dashboard is that the report folds
                        // everything past a configured limit into one line.
                        //
                        // Nothing is revealed. The handler serves it only for a
                        // campaign in one of §6.1's public states and answers
                        // 404 otherwise, so it cannot be used to find out what
                        // other people are preparing, and the response is a
                        // token the caller will send back plus the moment it
                        // stops being useful.
                        //
                        // POST and nothing else. The creator's report at
                        // /v1/projects/*/referrers is a different path, is
                        // deliberately not matched here, and falls through to
                        // the rule at the bottom — it carries a campaign's
                        // marketing performance and every code that ever earned
                        // it a pledge.
                        .requestMatchers(HttpMethod.POST, "/v1/projects/*/referral-visits")
                        .permitAll()
                        .requestMatchers(HttpMethod.DELETE, "/v1/projects/*/remind")
                        .permitAll()
                        // How someone with no credentials gets one, and how a
                        // client whose access token expired gets another. Each
                        // authenticates by its own means — a password, a
                        // verification token, a refresh token, a provider ID
                        // token — so requiring an access token here would be
                        // circular.
                        .requestMatchers(
                                "/v1/auth/register",
                                "/v1/auth/verify-email",
                                "/v1/auth/login",
                                "/v1/auth/oauth/*",
                                "/v1/auth/refresh",
                                "/v1/auth/logout",
                                // The second half of a sign-in. The caller has
                                // no session yet — that is what it is for — and
                                // what stands in for one is a single-use
                                // challenge that expires in minutes and was
                                // only issued for a correct password.
                                //
                                // The other three two-factor endpoints are
                                // deliberately absent: enrolling, confirming,
                                // and disabling all require a bearer token, and
                                // fall through to the rule below.
                                "/v1/auth/2fa/verify")
                        .permitAll()
                        // What an account inside its deletion grace period may
                        // still do: look at itself, take its data with it, and
                        // change its mind. Listed rather than derived, so that
                        // adding an endpoint never quietly adds a permission.
                        .requestMatchers("/v1/me", "/v1/me/export", "/v1/me/deletion")
                        .authenticated()
                        // Moderation, and every administrative endpoint added
                        // under this prefix later.
                        //
                        // Authentication is all the filter chain can decide here.
                        // There is no role model anywhere in the service — not in
                        // the schema, not in the access token — so this matcher
                        // cannot tell platform staff from a creator, and being
                        // authenticated is emphatically not enough to approve a
                        // campaign. Who may is decided one layer in, by
                        // ProjectAccess.requireModeratable against the configured
                        // moderator list, which is empty by default: an endpoint
                        // added under this prefix without its own check therefore
                        // reaches its handler, and it is the handler's job to
                        // refuse.
                        //
                        // **Epic #100 owns administrative roles and audit.** When
                        // it lands this matcher becomes hasAuthority("MODERATOR")
                        // or equivalent, the configured list is deleted, and
                        // requireModeratable keeps its signature — which is why
                        // the interim control lives there rather than here.
                        .requestMatchers("/v1/admin/**")
                        .hasAuthority(ACCOUNT_ACTIVE)
                        // Everything else additionally requires that the account
                        // is not closing. The creator's project endpoints fall
                        // through to here deliberately: launching a campaign or
                        // taking a pledge is exactly what an account inside its
                        // deletion grace period must not be able to do.
                        .anyRequest()
                        .hasAuthority(ACCOUNT_ACTIVE))
                // Every other request authenticates with a bearer JWT we signed.
                // Stateless by construction: no lookup, which is also why
                // revoking a session cannot reach an access token already
                // issued. That window is the token's lifetime, and it is why
                // the lifetime is fifteen minutes.
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(accountStanding())))
                // Stateless. No server-side session means nothing to fixate, and
                // nothing that has to be shared between instances.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // An access token in an Authorization header is not sent
                // automatically by a browser, so for almost every endpoint here
                // there is no cross-site request to forge and Spring's token
                // repository would only add a round trip.
                //
                // The refresh cookie is the exception, and it is defended in
                // two ways rather than by this filter: SameSite=Strict, so the
                // browser does not attach it to a cross-site request at all,
                // and a required custom header on the endpoints that read it,
                // which a form post or an image tag cannot set. That is what
                // §17.3 asks for.
                .csrf(csrf -> csrf.disable())
                // No login form and no browser prompt. This is an API; an
                // unauthenticated call gets 401 and a client decides what to do.
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .anonymous(Customizer.withDefaults())
                .exceptionHandling(handling ->
                        handling.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .build();
    }

    /**
     * Turns the account's standing, as the token records it, into an authority.
     *
     * <p>An account inside its deletion grace period has said it is leaving.
     * Letting it pledge, launch a campaign, or post a comment in the meantime
     * creates obligations it has already announced it will not be around to
     * meet — and every one of those rows then outlives the account by law
     * rather than by choice. So it keeps exactly the three permissions listed
     * above and loses the rest, without any endpoint having to remember.
     *
     * <p>Read from the token rather than from the database, because the
     * alternative is a query on every authenticated request and the point of
     * this filter chain is that there is not one. The cost is that a token
     * minted before the deletion keeps working until it expires — the same
     * fifteen-minute window that already applies to revoking a session, bounded
     * by the same value, for the same reason.
     */
    private static JwtAuthenticationConverter accountStanding() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(token -> {
            boolean closing = Boolean.TRUE.equals(token.getClaim(AccessTokenIssuer.DELETION_PENDING_CLAIM));
            List<GrantedAuthority> authorities =
                    closing ? List.of() : List.of(new SimpleGrantedAuthority(ACCOUNT_ACTIVE));
            return authorities;
        });
        return converter;
    }
}

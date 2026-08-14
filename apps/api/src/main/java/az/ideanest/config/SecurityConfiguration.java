package az.ideanest.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
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

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(requests -> requests
                        // The platform reads these to decide whether this
                        // instance takes traffic. They carry a status and no
                        // component detail; see application.yml.
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        // How someone with no credentials gets one, and how a
                        // client whose access token expired gets another. All
                        // four authenticate by their own means — a password, a
                        // verification token, a refresh token — so requiring an
                        // access token here would be circular.
                        .requestMatchers(
                                "/v1/auth/register",
                                "/v1/auth/verify-email",
                                "/v1/auth/login",
                                "/v1/auth/refresh",
                                "/v1/auth/logout")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                // Every other request authenticates with a bearer JWT we signed.
                // Stateless by construction: no lookup, which is also why
                // revoking a session cannot reach an access token already
                // issued. That window is the token's lifetime, and it is why
                // the lifetime is fifteen minutes.
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
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
}

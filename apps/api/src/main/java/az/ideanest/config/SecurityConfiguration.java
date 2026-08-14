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
                        // Registration and verification are how someone who has
                        // no credentials gets one.
                        .requestMatchers("/v1/auth/register", "/v1/auth/verify-email")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                // Stateless. No server-side session means nothing to fixate, and
                // nothing that has to be shared between instances.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Nothing here is authenticated by a cookie yet, and a token in
                // an Authorization header is not sent automatically by a
                // browser, so there is no cross-site request to forge. This has
                // to be revisited in #24, which introduces a refresh cookie:
                // that is precisely when CSRF starts to matter.
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

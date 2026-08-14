package az.ideanest.auth.application;

import az.ideanest.auth.AuthProperties;
import az.ideanest.shared.EmailAddress;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * What counts as an acceptable password.
 *
 * <p>Length, and almost nothing else. Composition rules — an uppercase, a
 * digit, a symbol — reliably produce {@code Password1!} and a note on a monitor;
 * NIST dropped them for that reason. Length is the property that actually costs
 * an attacker something, and the hash is what makes each guess expensive.
 *
 * <p>The one content rule kept is that the password may not contain the address
 * it protects. That pairing is the first thing any credential-stuffing list
 * tries, and it costs a user nothing to avoid.
 */
@Component
public class PasswordPolicy {

    private final AuthProperties properties;

    public PasswordPolicy(AuthProperties properties) {
        this.properties = properties;
    }

    public void check(String rawPassword, EmailAddress email) {
        int minimum = properties.passwordMinLength();
        int maximum = properties.passwordMaxLength();

        if (rawPassword.length() < minimum) {
            throw new WeakPasswordException("A password must be at least " + minimum + " characters long");
        }
        if (rawPassword.length() > maximum) {
            // Not a strength rule. Argon2 is deliberately expensive, so an
            // unbounded input is a way to spend our memory on demand.
            throw new WeakPasswordException("A password may not exceed " + maximum + " characters");
        }

        String lowercased = rawPassword.toLowerCase(Locale.ROOT);
        String localPart = email.value().substring(0, email.value().indexOf('@'));
        if (localPart.length() >= 3 && lowercased.contains(localPart)) {
            throw new WeakPasswordException("A password may not contain your email address");
        }
    }
}

package az.ideanest.auth.api;

import az.ideanest.auth.AuthProperties;
import az.ideanest.auth.application.EmailVerificationService;
import az.ideanest.auth.application.RegistrationService;
import az.ideanest.auth.application.RegistrationService.RegistrationCommand;
import az.ideanest.shared.EmailAddress;
import az.ideanest.shared.ratelimit.ClientAddress;
import az.ideanest.shared.ratelimit.RateLimiter;
import az.ideanest.shared.ratelimit.RateLimits;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Registration and email verification. */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final RegistrationService registrations;
    private final EmailVerificationService verifications;
    private final RateLimiter rateLimiter;
    private final AuthProperties properties;

    public AuthController(
            RegistrationService registrations,
            EmailVerificationService verifications,
            RateLimiter rateLimiter,
            AuthProperties properties) {
        this.registrations = registrations;
        this.verifications = verifications;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /**
     * Accepts a registration.
     *
     * <p>Always 202, whether or not the address was already registered. The
     * alternative tells anyone with a breach list which of those people have an
     * account here, which is the list you want before writing a phishing email.
     * What differs is the message the address receives, and that goes to its
     * owner rather than to whoever typed it.
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void register(@Valid @RequestBody RegistrationRequest request, HttpServletRequest httpRequest) {
        AuthProperties.RateLimit limits = properties.rateLimit();
        String clientAddress = ClientAddress.of(httpRequest);

        // Per address, so one script cannot create accounts in bulk.
        RateLimits.enforce(rateLimiter.recordAttempt(
                "register:ip:" + clientAddress, limits.registrationsPerAddress(), limits.window()));

        EmailAddress email = EmailAddress.of(request.email());

        // And per email, separately: an attacker with many source addresses is
        // still bounded when probing one account, and the per-IP limit alone
        // would not bound them at all.
        RateLimits.enforce(rateLimiter.recordAttempt(
                "register:email:" + email.value(), limits.registrationsPerEmail(), limits.window()));

        try {
            registrations.register(new RegistrationCommand(
                    email, request.password(), request.name(), request.localeOrDefault(), "AZN"));
        } catch (DataIntegrityViolationException e) {
            // Two simultaneous registrations for one address: both saw it free,
            // and the unique index refused the second. Answering identically
            // keeps the race from becoming the enumeration oracle that the rest
            // of this method exists to avoid.
            log.info("Registration lost a race on a unique constraint; answering as though accepted.");
        }
    }

    /** Redeems a verification link. Idempotent only in the sense that a second attempt is refused clearly. */
    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody VerifyEmailRequest request, HttpServletRequest httpRequest) {
        AuthProperties.RateLimit limits = properties.rateLimit();

        // Not about guessing the token — it is 256 bits. It is about not
        // letting one client spend our database on the attempt.
        RateLimits.enforce(rateLimiter.recordAttempt(
                "verify:ip:" + ClientAddress.of(httpRequest), limits.verificationsPerAddress(), limits.window()));

        verifications.verify(request.token());
    }
}

package az.ideanest.auth.application;

import az.ideanest.auth.domain.SecureTokens;
import az.ideanest.auth.domain.VerificationPurpose;
import az.ideanest.auth.domain.VerificationToken;
import az.ideanest.auth.infrastructure.VerificationTokenRepository;
import az.ideanest.user.application.UserAccounts;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Redeeming the link that proves an address exists. */
@Service
public class EmailVerificationService {

    private final VerificationTokenRepository verificationTokens;
    private final UserAccounts users;
    private final Clock clock;

    public EmailVerificationService(
            VerificationTokenRepository verificationTokens, UserAccounts users, Clock clock) {
        this.verificationTokens = verificationTokens;
        this.users = users;
        this.clock = clock;
    }

    @Transactional
    public void verify(String rawToken) {
        Instant now = clock.instant();

        VerificationToken token = verificationTokens
                .findByTokenHash(SecureTokens.hash(rawToken))
                .orElseThrow(() -> new VerificationRejectedException("This link is not valid."));

        if (token.getPurpose() != VerificationPurpose.EMAIL_VERIFICATION) {
            // A password reset token must not verify an address, and an address
            // verification must not authorise a password change. Email is the
            // channel an attacker with mailbox access already controls, so the
            // purpose is what keeps one capability from becoming the other.
            throw new VerificationRejectedException("This link is not valid.");
        }
        if (token.isExpired(now)) {
            throw new VerificationRejectedException("This link has expired. Ask for a new one.");
        }

        // Claimed with a conditional update rather than by reading and then
        // writing. Two clicks arriving together would both see an unspent token
        // and both proceed; the database decides which one wins, and it can
        // only decide if the condition is part of the statement.
        int claimed = verificationTokens.claim(token.getId(), now);
        if (claimed == 0) {
            throw new VerificationRejectedException("This link has already been used.");
        }

        users.markEmailVerified(token.getUserId(), now);
    }
}

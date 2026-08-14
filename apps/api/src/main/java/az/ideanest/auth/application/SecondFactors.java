package az.ideanest.auth.application;

import az.ideanest.auth.domain.RecoveryCode;
import az.ideanest.auth.domain.RecoveryCodes;
import az.ideanest.auth.domain.Totp;
import az.ideanest.auth.domain.TwoFactorSecret;
import az.ideanest.auth.infrastructure.RecoveryCodeRepository;
import az.ideanest.auth.infrastructure.TwoFactorSecretRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checking a second factor, and spending it.
 *
 * <p>One place, because three flows need the same answer — completing a
 * sign-in, and switching two-factor off — and a check that is written twice is
 * a check that is one day only fixed once. Both of the things that make it safe
 * live here with it: a code cannot be replayed inside its window, and a
 * recovery code cannot be spent twice.
 */
@Service
public class SecondFactors {

    private final TwoFactorSecretRepository secrets;
    private final RecoveryCodeRepository recoveryCodes;

    public SecondFactors(TwoFactorSecretRepository secrets, RecoveryCodeRepository recoveryCodes) {
        this.secrets = secrets;
        this.recoveryCodes = recoveryCodes;
    }

    /**
     * Whether the second factor was proved, spending it if so.
     *
     * <p>A code is tried first and a recovery code only when no code was sent,
     * so that presenting both is one attempt rather than two.
     */
    @Transactional
    public boolean accepts(TwoFactorSecret secret, String code, String recoveryCode, Instant now) {
        if (hasValue(code)) {
            return acceptsCode(secret, code, now);
        }
        if (hasValue(recoveryCode)) {
            return acceptsRecoveryCode(secret.getUserId(), recoveryCode, now);
        }
        return false;
    }

    private boolean acceptsCode(TwoFactorSecret secret, String code, Instant now) {
        OptionalLong step = Totp.verify(secret.getSecret(), code, now);
        if (step.isEmpty() || !secret.isStepSpendable(step.getAsLong())) {
            // The second half of that condition is the replay defence. A code
            // that was correct thirty seconds ago is still arithmetically
            // correct inside the skew window, and accepting it twice would make
            // one read over a shoulder good for a second sign-in.
            return false;
        }

        secret.spendStep(step.getAsLong());
        secrets.save(secret);
        return true;
    }

    private boolean acceptsRecoveryCode(UUID userId, String presented, Instant now) {
        Optional<RecoveryCode> code = recoveryCodes.findByCodeHash(RecoveryCodes.hash(presented));
        if (code.isEmpty() || !code.get().getUserId().equals(userId)) {
            // The owner check matters: the lookup is by hash across every user,
            // and somebody else's code must not open this account merely
            // because it exists.
            return false;
        }

        // Conditional, so that the same code arriving twice at once is spent
        // once. The row survives, so the count of remaining codes stays honest.
        return recoveryCodes.claim(code.get().getId(), now) == 1;
    }

    private static boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }
}

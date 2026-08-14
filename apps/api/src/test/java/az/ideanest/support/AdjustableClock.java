package az.ideanest.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The clock the application runs on during tests, with a handle on it.
 *
 * <p>Time-based one-time passwords, challenge expiry, and session lifetime are
 * all rules about time, and the only other way to test them is to sleep. A
 * sleeping test is slow, and worse, it is flaky exactly when the machine is
 * busy — which is on CI, in the run nobody is watching.
 *
 * <p>Free-running by default, so that every test which does not ask for control
 * behaves as it did before. {@link #freeze()} pins it to the current instant,
 * which is what makes "the same time step" mean something across three HTTP
 * calls; {@link #advance(Duration)} moves it forward.
 *
 * <p>Frozen at the real instant rather than at some fixed date on purpose: the
 * JWT decoder validates expiry against the system clock, so a token minted
 * hours away from real time would be refused for a reason that has nothing to
 * do with the test.
 */
public final class AdjustableClock extends Clock {

    private final Clock system = Clock.systemUTC();

    /** Null means "follow the system clock", which is the default. */
    private volatile Instant fixedAt;

    /** Pins the clock at this moment. */
    public void freeze() {
        fixedAt = system.instant();
    }

    /** Moves a frozen clock forward, freezing it first if it was still running. */
    public void advance(Duration by) {
        fixedAt = instant().plus(by);
    }

    /**
     * Lets it run again. Every test that freezes must call this afterwards: the
     * context — and therefore this bean — is shared with the rest of the suite.
     */
    public void reset() {
        fixedAt = null;
    }

    @Override
    public Instant instant() {
        Instant frozen = fixedAt;
        return frozen == null ? system.instant() : frozen;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        // The application is UTC everywhere and nothing asks for another zone.
        // Returning a different clock here would hand out one this test cannot
        // control, which is the one failure mode worth refusing outright.
        throw new UnsupportedOperationException("The test clock is UTC only");
    }
}

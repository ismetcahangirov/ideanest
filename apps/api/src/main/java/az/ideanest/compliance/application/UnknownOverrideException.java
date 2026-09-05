package az.ideanest.compliance.application;

import java.util.UUID;

/** No override of that identifier exists — #436. A 404, and the console's cue to reload. */
public class UnknownOverrideException extends RuntimeException {

    private final transient UUID overrideId;

    public UnknownOverrideException(UUID overrideId) {
        super("No compliance override %s.".formatted(overrideId));
        this.overrideId = overrideId;
    }

    public UUID overrideId() {
        return overrideId;
    }
}

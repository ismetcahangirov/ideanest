package az.ideanest.media.domain;

/**
 * Where an upload has got to — the media pipeline design of 2026-08-30.
 *
 * <h2>Five states, and the reason there are not three</h2>
 *
 * <p>The obvious shorter version is uploaded, ready, failed. It loses the two facts a
 * creator's screen actually needs.
 *
 * <p>{@link #PENDING} against {@link #UPLOADED} is the difference between "we issued an
 * address and nothing came back" and "the bytes are there and we have not looked yet". The
 * first is somebody who closed the tab and is swept away; the second is work. Collapsing
 * them would make an abandoned upload indistinguishable from a queue that has stopped.
 *
 * <p>{@link #PROCESSING} exists because the sweep claims a row before it does anything slow
 * with it. Without a claimed state a second pass — after a restart, or on the replica that
 * won the lease next — would start the same work again while the first was still running.
 */
public enum MediaStatus {

    /**
     * An address has been issued and nothing has arrived through it.
     *
     * <p>This is the state a row is in while the browser is uploading, which is most of the
     * wall-clock time of the whole operation.
     */
    PENDING,

    /** The client says the bytes are there. Nothing has read them yet. */
    UPLOADED,

    /** Claimed by a pass of the sweep. */
    PROCESSING,

    /**
     * Servable.
     *
     * <p>Terminal, and the database enforces what it promises: {@code media_ready_is_servable}
     * refuses a row in this state without a key, a type, a size, an extent and a placeholder.
     */
    READY,

    /**
     * Terminal, with a {@link MediaFailureReason} saying why.
     *
     * <p>Not retried. A file that is not an image will not become one, and the two failures
     * that are transient — storage being unreachable, the process being killed — leave the
     * row in {@link #PROCESSING} rather than here, so the next pass picks it up again.
     */
    FAILED;

    /** Whether a pass of the sweep should take this row on. */
    public boolean awaitsProcessing() {
        return this == UPLOADED || this == PROCESSING;
    }

    /** Whether nothing further will happen to this row on its own. */
    public boolean isTerminal() {
        return this == READY || this == FAILED;
    }
}

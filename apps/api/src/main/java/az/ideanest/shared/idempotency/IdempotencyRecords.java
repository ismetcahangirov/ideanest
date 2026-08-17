package az.ideanest.shared.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transaction boundaries idempotency needs, and nothing else.
 *
 * <p><strong>Three separate transactions, and the separation is the design.</strong>
 * {@link IdempotentRequests} orchestrates them and holds no transaction of its own,
 * because two of these have to commit independently of the work they surround.
 *
 * <ol>
 *   <li>{@link #claim} commits <em>before</em> the work starts. It has to: the
 *       claim is how a second, identical request finds out that this one exists, and
 *       a row that is still uncommitted is a row that request cannot see. Everything
 *       else about the race follows from that one insert being visible early.
 *   <li>{@link #runAndRecord} does the work and writes the answer in
 *       <em>one</em> transaction. That is what makes the pledge and the record of
 *       the response that describes it atomic: there is no interval in which the
 *       pledge exists and the recorded answer does not, so a retry can never be told
 *       "still running" about work that has finished, nor be handed a response for a
 *       pledge that was rolled back.
 *   <li>{@link #release} runs after the work has failed and its transaction has
 *       already rolled back, which is exactly why it cannot be part of it — the
 *       rollback that undoes the failed work would undo the release with it, and the
 *       key would stay claimed for a day for a request that never happened.
 * </ol>
 *
 * <p>A separate bean rather than methods on {@link IdempotentRequests} because
 * Spring's {@code @Transactional} is a proxy and a self-call would carry no
 * transaction at all — which, for the arrangement above, would mean no boundaries
 * where the whole correctness argument is about where the boundaries are.
 */
@Component
public class IdempotencyRecords {

    private final IdempotencyRecordRepository records;

    public IdempotencyRecords(IdempotencyRecordRepository records) {
        this.records = records;
    }

    /**
     * Claims the key for this account, or fails because somebody already has.
     *
     * <p>{@code REQUIRES_NEW} so that the insert commits on its own and immediately.
     * A caller inside another transaction — and #56's edit will be one — must not be
     * able to make this claim invisible to the request racing it.
     *
     * @return the record's identifier, for the two calls that follow
     * @throws org.springframework.dao.DataIntegrityViolationException when this
     *     account has already spent this key. Deliberately not caught here: catching
     *     it would leave this transaction marked rollback-only and fail at the
     *     commit instead, so the caller catches it outside the boundary, after the
     *     rollback has happened
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID claim(
            UUID accountId, String operation, IdempotencyKey key, String fingerprint, Instant expiresAt) {

        return records.saveAndFlush(
                        IdempotencyRecord.claim(accountId, operation, key, fingerprint, expiresAt))
                .getId();
    }

    /**
     * Does the work and records what it answered, together.
     *
     * <p>The body is produced inside this transaction, by the supplier, so that
     * serialising the result cannot see a state the work did not commit.
     *
     * @param body the work, and the serialisation of its result. Whatever it
     *     returns is what every replay of this key will be given, byte for byte
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RecordedResponse runAndRecord(UUID recordId, int status, Instant at, Supplier<String> body) {
        String serialised = body.get();
        IdempotencyRecord record = records
                .findById(recordId)
                // Unreachable: this transaction is the only writer of the row and the
                // claim above committed it. Reaching it would mean the sweep removed a
                // key it had just issued, which is a bug in the retention rather than a
                // condition to recover from.
                .orElseThrow(() -> new IllegalStateException("The claim on " + recordId + " has gone"));
        record.completeWith(status, serialised, at);
        return new RecordedResponse(status, serialised, false);
    }

    /**
     * Gives the key back after the work failed.
     *
     * <p><strong>Only successes are remembered.</strong> A refusal is not replayed,
     * because the reason for it can change between one request and the next — a
     * place freed by the sweep, a campaign that has since gone live — and answering
     * a client with yesterday's refusal for the next twenty-four hours would turn a
     * transient failure into a permanent one for whoever retried it with the key
     * they already had.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID recordId) {
        records.deleteById(recordId);
    }

    /** What this account did with this key, once an insert has established that it did something. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyRecord> find(UUID accountId, IdempotencyKey key) {
        return records.findByAccountIdAndIdempotencyKey(accountId, key.value());
    }

    /** §17.2's retention, one bounded pass. See {@link IdempotencyRecordRepository#deleteExpired}. */
    @Transactional
    public int removeExpired(Instant now, int batchSize) {
        return records.deleteExpired(now, batchSize);
    }
}

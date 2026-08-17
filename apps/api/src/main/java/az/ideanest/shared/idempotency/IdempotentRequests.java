package az.ideanest.shared.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * §10.3's {@code Idempotency-Key}: a payment mutation retried with the same key
 * returns the answer it gave the first time, and does the work once.
 *
 * <p><strong>The four outcomes.</strong> A request arriving with a key is in
 * exactly one of these states, and telling them apart is the whole job.
 *
 * <ul>
 *   <li><strong>New.</strong> Nobody has this key. The work runs, and its response
 *       is recorded against the key.
 *   <li><strong>A replay.</strong> The same account, the same key, the same
 *       request, and the first one finished. The recorded response is returned
 *       verbatim — same status, same bytes. Not re-executed, and emphatically not a
 *       409: a client that retried because it never saw the first answer is entitled
 *       to that answer, and a conflict would send it to build a second pledge.
 *   <li><strong>Reuse.</strong> The same key with a <em>different</em> request.
 *       {@link IdempotencyKeyReusedException}, because neither answer is safe.
 *   <li><strong>In flight.</strong> The same key, and the first request has not
 *       finished. {@link IdempotentRequestInProgressException}, which says so.
 * </ul>
 *
 * <h2>Two identical requests at the same instant</h2>
 *
 * <p><strong>The unique index decides, not a read.</strong> There is no way to write
 * "look for the key, and if it is not there do the work" that is correct under
 * concurrency: two requests arriving together both find nothing, both conclude they
 * are first, and both execute — and no amount of care in the surrounding code
 * changes that, because neither transaction can see the other's intention. So the
 * intention is written down. Both requests insert a claim on the key,
 * {@code idempotency_keys_account_key_key} lets exactly one of them exist, and the
 * loser is refused by PostgreSQL rather than by us. It then reads what the winner
 * wrote and is either replayed or told to retry.
 *
 * <p>This is the same argument, and the same mechanism, as
 * {@code RewardTierRepository#reserveOnePlace}: the condition that makes a race safe
 * is inside a statement the database serialises, and never in a check the
 * application performed a moment earlier.
 *
 * <p><strong>The loser is told to retry rather than made to wait</strong> — see
 * {@link IdempotentRequestInProgressException}, which carries the argument.
 *
 * <h2>The one window that remains</h2>
 *
 * <p>The claim commits before the work begins, so a process that dies mid-request
 * leaves a claimed key with no recorded answer, and a retry is told the first
 * request is still running until the key expires. That is deliberate and it is the
 * safe direction: the alternative — releasing a claim whose work may or may not have
 * committed — is how a pledge gets made twice. The work and its recorded response
 * commit together (see {@link IdempotencyRecords}), so the pair is never half
 * written.
 *
 * <h2>Not pledge machinery</h2>
 *
 * <p>{@code ModuleBoundaryTests} names idempotency as one of the things
 * {@code shared} exists for. #56's {@code PATCH} and {@code DELETE} and epic #59's
 * charges need this guarantee too, and each of them writing its own would be each of
 * them getting the race above right independently.
 */
@Service
public class IdempotentRequests {

    private static final Logger log = LoggerFactory.getLogger(IdempotentRequests.class);

    private static final String DIGEST = "SHA-256";

    /**
     * What is recorded for a mutation that answers with no body.
     *
     * <p>Empty rather than {@code "null"} or a JSON literal: it is what a 204 sends,
     * and {@code idempotency_keys.response_body} is documented as the exact bytes
     * that were sent. The column is {@code NOT NULL} when a status is present —
     * V18's {@code idempotency_keys_outcome_is_whole} — which an empty string
     * satisfies and a null would not.
     */
    private static final String NO_CONTENT = "";

    private final IdempotencyRecords records;
    private final ObjectMapper json;
    private final IdempotencyProperties properties;
    private final Clock clock;

    public IdempotentRequests(
            IdempotencyRecords records, ObjectMapper json, IdempotencyProperties properties, Clock clock) {
        this.records = records;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Runs {@code work} once for this key, and answers every retry of it with what
     * the first run said.
     *
     * <p><strong>Must not be called from inside a transaction that the work also
     * belongs to.</strong> The claim has to commit before the work starts — that is
     * what makes it visible to the request racing this one — so it opens its own
     * transaction, and a caller holding one that the work would have joined would be
     * asking for two transactions where the design needs three.
     *
     * @param accountId whose key this is. Keys are scoped to an account, so that one
     *     caller cannot replay another's request or be handed its response
     * @param operation what the key is being spent on, {@code pledge.draft} and the
     *     like. Part of the fingerprint, so one key cannot quietly serve two
     *     different endpoints
     * @param request the parsed request, fingerprinted rather than stored. Parsed
     *     rather than raw on purpose: {@code Money} has already normalised
     *     {@code "50"} and {@code "50.00"} to one value by the time it arrives here,
     *     so two spellings of one request are one request
     * @param status the status the first run answers with, and therefore the status
     *     every replay answers with
     * @param work the mutation. Runs at most once per key
     * @throws IdempotencyKeyReusedException when the key has been spent on a
     *     different request
     * @throws IdempotentRequestInProgressException when the first request carrying
     *     this key has not finished
     */
    public RecordedResponse execute(
            UUID accountId,
            String operation,
            IdempotencyKey key,
            Object request,
            int status,
            Supplier<?> work) {

        return run(accountId, operation, key, request, status, () -> serialise(work.get()));
    }

    /**
     * The same guarantee for a mutation that answers with no body at all.
     *
     * <p><strong>{@code DELETE /v1/pledges/{id}} is the reason this exists</strong>,
     * and it is the case §10.3 is most pointed about: a retried cancellation has to
     * answer 204 rather than 404, and there is no pledge left in an active state to
     * carry the key. So the key carries the answer, and the answer is "no content".
     *
     * <p><strong>The recorded body is the empty string, which is the truth.</strong>
     * The alternative — running the mutation through {@link #execute} with a supplier
     * that returns null — would store the four bytes {@code null} against the key and
     * then decline to send them, which makes {@code response_body}'s "the exact bytes
     * that were sent" false for every row this endpoint writes. A 204 sends zero
     * bytes, and zero bytes is what is kept, so a replay is byte-identical in the
     * only sense a 204 has.
     *
     * <p>Everything else is {@link #execute}'s: the same claim, the same race decided
     * by the same unique index, the same release when the work fails. This is not a
     * second implementation of any of that — see {@link #run}, which both go through.
     *
     * @param status the no-content status the first run answers with, and therefore
     *     the status every replay answers with
     * @param work the mutation. Runs at most once per key
     */
    public RecordedResponse executeWithoutContent(
            UUID accountId,
            String operation,
            IdempotencyKey key,
            Object request,
            int status,
            Runnable work) {

        return run(accountId, operation, key, request, status, () -> {
            work.run();
            return NO_CONTENT;
        });
    }

    /**
     * The claim, the work, and the record of what it answered — the part that is the
     * same whether or not there is a body.
     *
     * <p>Every word of this class's header is about what happens here, and there is
     * one copy of it precisely so that the four outcomes and the race between two
     * identical requests are decided once rather than once per response shape.
     *
     * @param serialisedWork the mutation and the bytes it answers with, produced
     *     together inside the transaction that records them
     */
    private RecordedResponse run(
            UUID accountId,
            String operation,
            IdempotencyKey key,
            Object request,
            int status,
            Supplier<String> serialisedWork) {

        // Truncated to what the columns hold: PostgreSQL stores microseconds, and
        // this instant is both written and compared against by the sweep.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String fingerprint = fingerprintOf(operation, request);

        UUID recordId;
        try {
            recordId = records.claim(accountId, operation, key, fingerprint, now.plus(properties.retention()));
        } catch (DataIntegrityViolationException alreadySpent) {
            // Somebody has this key: this account, on an earlier request or on one
            // still running. Which of the four outcomes it is depends on what they
            // wrote, and nothing here can know that without looking.
            return answerFromTheFirstRequest(accountId, operation, key, fingerprint);
        }

        try {
            return records.runAndRecord(recordId, status, now, serialisedWork);
        } catch (RuntimeException failed) {
            // The work rolled back, so the key must go back too: a refusal is not
            // replayed, and a claim left behind would refuse the client's next
            // twenty-four hours of retries with "still running" for a request that
            // is not.
            try {
                records.release(recordId);
            } catch (RuntimeException notReleased) {
                // Logged rather than thrown: the caller is owed the reason their
                // request failed, not the reason the cleanup after it failed. The
                // key expires by itself within §17.2's day.
                log.error("Could not release the claim on an idempotency key after {} failed.", operation, notReleased);
            }
            throw failed;
        }
    }

    /**
     * What the request that got here first left behind.
     *
     * <p>Read only after an insert has been refused. Reading before would be the
     * check-then-act that the unique index exists to replace.
     */
    private RecordedResponse answerFromTheFirstRequest(
            UUID accountId, String operation, IdempotencyKey key, String fingerprint) {

        IdempotencyRecord record = records.find(accountId, key).orElseThrow(() -> {
            // The insert was refused and the row is not there, which means the sweep
            // removed a day-old key in the moment between the two. Vanishingly
            // unlikely and harmless: the client retries and claims it cleanly.
            return new IdempotentRequestInProgressException();
        });

        if (!record.describes(fingerprint)) {
            throw new IdempotencyKeyReusedException(record.getOperation());
        }
        if (!record.isComplete()) {
            throw new IdempotentRequestInProgressException();
        }

        log.debug("Replaying the recorded response for a repeated {} request.", operation);
        return record.recordedResponse();
    }

    /**
     * A hash of what was asked for, which is all that is ever compared.
     *
     * <p>The operation is folded in so that one key used on two endpoints is a reuse
     * rather than a replay of the wrong answer.
     */
    private String fingerprintOf(String operation, Object request) {
        try {
            MessageDigest digest = MessageDigest.getInstance(DIGEST);
            digest.update(operation.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(json.writeValueAsBytes(request));
            return HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException impossible) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException(DIGEST + " is not available", impossible);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A request that cannot be serialised cannot be fingerprinted", e);
        }
    }

    /** The bytes the client is sent, and the bytes every replay is sent. */
    private String serialise(Object result) {
        try {
            return json.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A response that cannot be serialised cannot be recorded", e);
        }
    }
}

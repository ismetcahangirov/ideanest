package az.ideanest.signature.domain;

/**
 * The signature abstraction — issue #428, shaped like §9.4's {@code PaymentProvider} and for
 * the same reasons.
 *
 * <p><strong>The rule this interface exists to make enforceable is §9.4's last sentence,
 * borrowed intact: no provider SDK is called anywhere except behind this interface.</strong>
 * It is not a style preference. SİMA İmza is one national provider today and ASAN İmza is the
 * obvious second, and a second integration is only a day's work if the first one's vocabulary
 * never leaked. The moment a SİMA session identifier, status string or certificate format
 * reaches the module that submits a campaign, the second provider becomes a rewrite of
 * everything that touched it.
 *
 * <p>{@code SignatureProviderBoundaryTests} checks the rule rather than restating it, exactly
 * as {@code PaymentProviderBoundaryTests} does for charges.
 *
 * <h2>Why a signature and not another tick</h2>
 *
 * <p>A tick box is an acceptance, and {@code document_acceptances} is where a tick lives. A
 * SİMA İmza signature is made by a citizen whose identity the state has already verified and
 * carries the legal force of a handwritten one. For most of what this platform asks somebody to
 * agree to, a tick is proportionate; for the creator agreement — the document that moves
 * delivery liability onto a named person and lets the platform recover a chargeback from them
 * — it is not, and #429 is where the difference is spent.
 *
 * <h2>Failure is a first-class case</h2>
 *
 * <p>SİMA is a third party. It will be down, the citizen's phone will be off, and the session
 * will expire. §9.4 already draws the distinction this needs and it is drawn again here: a
 * citizen who cancels or lets a session expire is a <em>value</em> — an ordinary outcome, shown
 * as "not signed yet", retryable — and a SİMA that cannot be reached is a
 * <em>throw</em>. Collapsing the two produces the one failure #428 names: a submission that
 * failed for an unexplained reason, where what the creator needed to hear was that the signing
 * service is unavailable and their draft is untouched.
 *
 * <h2>What an implementation must guarantee</h2>
 *
 * <ul>
 *   <li><strong>It is a translator, not a decision maker.</strong> An adapter maps one call to
 *       one provider request and one provider response to one result. It does not decide
 *       whether the signer is the right person — #429 does that by matching the FIN — and it
 *       does not consult the database or write a row.
 *   <li><strong>A refusal is a value; not being able to ask is a throw.</strong> See
 *       {@link SignatureResult} and {@link SignatureProviderUnavailableException}.
 *   <li><strong>Certificate material never leaves it.</strong> #428: no copy of the citizen's
 *       certificate is stored, and no identity document. An adapter drops the chain and
 *       returns {@link CertificateSubject}, which is what a dispute is answered with.
 *   <li><strong>It is stateless and thread-safe.</strong> One bean serves every signing
 *       session.
 * </ul>
 */
public interface SignatureProvider {

    /** Which provider this adapter speaks to. The value stored on every row it produces. */
    SignatureProviderName name();

    /**
     * Begins a signature: the citizen completes it in their SİMA application.
     *
     * <p>Returns a session rather than a signature because the answer is not knowable
     * synchronously — there is a person and a phone in the middle. {@link SignatureSession}
     * argues the shape.
     *
     * @throws SignatureProviderUnavailableException when the provider could not be reached
     */
    SignatureSession begin(SignatureRequest request);

    /**
     * What became of it.
     *
     * <p>Asked by the polling screen and again by anything reconciling afterwards, so it must
     * be safe to ask repeatedly: a session that has resolved keeps resolving to the same
     * answer. That is also what makes V67's unique index on the session identifier the right
     * shape — a retry lands on the existing row rather than writing a second signature.
     *
     * @param sessionId {@link SignatureSession#sessionId()}
     * @throws SignatureProviderUnavailableException when the provider could not be reached
     */
    SignatureResult resolve(String sessionId);

    /**
     * Checks a signature the platform already holds, against the document it claims to sign.
     *
     * <p><strong>The hash is a parameter and not read off the signature</strong>, which is the
     * whole point of the call. Comparing a signature's own hash against itself answers nothing;
     * what a caller wants to know is whether it signs <em>this</em> text — the version in
     * {@code legal_documents} that the acceptance names. {@link Verification#hashMatches} is
     * that answer, and {@code Verification.wrongDocument} is the interesting failure.
     *
     * <p>Hex rather than {@code byte[]}, which is where this departs from #428's sketch.
     * Every place the platform holds one of these digests holds it as lower-case hex —
     * {@code legal_documents.content_hash}, V67's {@code document_hash} — so bytes here would
     * mean a decode at every call site and an array whose {@code equals} is not the one
     * anybody wants. The shape is constrained in {@link StoredSignature} and again by V67, so
     * a value that is not a digest cannot reach an adapter.
     *
     * @param documentHash SHA-256 of the document, lower-case hex, from
     *     {@code legal_documents.content_hash}
     * @throws SignatureProviderUnavailableException when the provider could not be reached.
     *     <strong>Never for a signature that does not verify</strong> — that is a
     *     {@link Verification} saying so
     */
    Verification verify(StoredSignature signature, String documentHash);
}

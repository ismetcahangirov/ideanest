package az.ideanest.verification;

import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Identity verification: what may be submitted, who has to, and how long anything is kept —
 * issue #105.
 *
 * <h2>There is no default key, and that is the design</h2>
 *
 * <p>The same position {@code PledgeManagerProperties.Addresses} takes, for the same
 * reason: a key generated at start-up would encrypt every document under a key that changes
 * on the next deploy, and a key committed to this repository would be a published key,
 * which is not encryption.
 *
 * <p>So an unconfigured deployment has no keys, starts normally, serves every other
 * endpoint, and refuses a document submission with a 503 saying so. A missing key stopping
 * the whole service would be a worse trade for a feature that is one endpoint of a hundred.
 *
 * @param required whether verification is enforced anywhere. <strong>False, and it must
 *     stay false until somebody may decide otherwise.</strong> §22.1 lists "identity
 *     verification thresholds for creators" among the questions needing a specific legal
 *     answer and #71 carries {@code status: needs-decision}. The flag exists so that the
 *     day the answer arrives is a configuration change and a wiring change rather than a
 *     migration — not so that a deployment can quietly invent a compliance position
 * @param approvalLife how long an approval counts for before it expires. Two years, which
 *     is the shortest period any of the frameworks §22.1 names would accept and short
 *     enough that a document nobody has looked at since is re-checked
 * @param documents the envelope: what may be submitted, how large, and how long a document
 *     is held after a decision
 */
@ConfigurationProperties(prefix = "ideanest.verification")
public record VerificationProperties(boolean required, Duration approvalLife, Documents documents) {

    private static final Duration DEFAULT_APPROVAL_LIFE = Duration.ofDays(730);

    public VerificationProperties {
        approvalLife = approvalLife == null ? DEFAULT_APPROVAL_LIFE : approvalLife;
        documents = documents == null ? Documents.defaults() : documents;

        if (!approvalLife.isPositive()) {
            throw new IllegalArgumentException("An approval counts for some length of time");
        }
    }

    /**
     * The document envelope.
     *
     * @param maxBytes the size cap, per document. §17.3's file-upload row. Five megabytes
     *     is a generous photograph and a small enough blob that a bounded number of them
     *     in Postgres is not a problem — see V58 on why they are there at all
     * @param maxPerVerification how many documents one submission may carry. Four: a card
     *     has two sides, and a company may need a registration extract beside an
     *     individual's document for the person signing
     * @param retention how long a document is held <strong>after a decision</strong>. Seven
     *     days: long enough that a reviewer can be asked to look again, short enough that
     *     the platform is not a store of passports. §17.4's minimisation is the rule
     * @param unreviewedRetention how long an undecided submission is held. Longer, because
     *     deleting a document nobody has looked at makes the creator submit it again for
     *     nothing — but bounded, because a queue that is never worked must not become an
     *     archive
     * @param primaryKeyId which key new documents are sealed under. Rotation is: add the
     *     new key, deploy, then move this label — so every instance can read the new key
     *     before any instance writes under it
     * @param keys label to base64 key material, 32 bytes each for AES-256. Old keys stay so
     *     that documents sealed under them can still be opened
     */
    public record Documents(
            int maxBytes,
            int maxPerVerification,
            Duration retention,
            Duration unreviewedRetention,
            String primaryKeyId,
            Map<String, String> keys) {

        private static final int DEFAULT_MAX_BYTES = 5 * 1024 * 1024;

        private static final int DEFAULT_MAX_PER_VERIFICATION = 4;

        private static final Duration DEFAULT_RETENTION = Duration.ofDays(7);

        private static final Duration DEFAULT_UNREVIEWED_RETENTION = Duration.ofDays(60);

        /** AES-256. A shorter key is a configuration mistake rather than a weaker choice. */
        private static final int KEY_BYTES = 32;

        /** The shape V58's check constraint holds {@code key_id} to. */
        private static final Pattern KEY_ID = Pattern.compile("^[a-z0-9._-]{1,64}$");

        static Documents defaults() {
            return new Documents(
                    DEFAULT_MAX_BYTES,
                    DEFAULT_MAX_PER_VERIFICATION,
                    DEFAULT_RETENTION,
                    DEFAULT_UNREVIEWED_RETENTION,
                    null,
                    Map.of());
        }

        public Documents {
            maxBytes = maxBytes == 0 ? DEFAULT_MAX_BYTES : maxBytes;
            maxPerVerification = maxPerVerification == 0 ? DEFAULT_MAX_PER_VERIFICATION : maxPerVerification;
            retention = retention == null ? DEFAULT_RETENTION : retention;
            unreviewedRetention = unreviewedRetention == null ? DEFAULT_UNREVIEWED_RETENTION : unreviewedRetention;
            keys = keys == null ? Map.of() : Map.copyOf(keys);

            if (maxBytes < 1) {
                throw new IllegalArgumentException("A document has some size");
            }
            if (maxPerVerification < 1) {
                throw new IllegalArgumentException("A submission carries at least one document");
            }
            if (!retention.isPositive() || !unreviewedRetention.isPositive()) {
                throw new IllegalArgumentException("A retention limit is a positive duration");
            }

            if (primaryKeyId != null && primaryKeyId.isBlank()) {
                primaryKeyId = null;
            }
            if (primaryKeyId != null) {
                primaryKeyId = primaryKeyId.trim().toLowerCase(Locale.ROOT);
                if (!KEY_ID.matcher(primaryKeyId).matches()) {
                    throw new IllegalArgumentException(
                            "A document key label is lowercase letters, digits, dot, dash or underscore");
                }
                if (!keys.containsKey(primaryKeyId)) {
                    // Refused at start-up rather than at the first submission: a deployment
                    // that named a primary key meant to configure one, and finding out from
                    // a creator's failed upload is finding out late.
                    throw new IllegalArgumentException(
                            "The primary document key '" + primaryKeyId + "' is not among the configured keys");
                }
            }
            for (Map.Entry<String, String> entry : keys.entrySet()) {
                if (!KEY_ID.matcher(entry.getKey()).matches()) {
                    throw new IllegalArgumentException(
                            "A document key label is lowercase letters, digits, dot, dash or underscore");
                }
                if (decode(entry.getKey(), entry.getValue()).length != KEY_BYTES) {
                    throw new IllegalArgumentException(
                            "The document key '" + entry.getKey() + "' is not " + KEY_BYTES + " bytes of base64");
                }
            }
        }

        /** Whether this deployment can store a document at all. */
        public boolean isConfigured() {
            return primaryKeyId != null;
        }

        /**
         * The key material, decoded once.
         *
         * <p>A fresh map each call rather than a cached field, so that the arrays a caller
         * holds are its own.
         */
        public Map<String, byte[]> decodedKeys() {
            Map<String, byte[]> decoded = new LinkedHashMap<>();
            keys.forEach((label, material) -> decoded.put(label, decode(label, material)));
            return Map.copyOf(decoded);
        }

        private static byte[] decode(String label, String material) {
            try {
                return Base64.getDecoder().decode(material == null ? "" : material.trim());
            } catch (IllegalArgumentException notBase64) {
                // The message names the label and never the material.
                throw new IllegalArgumentException("The document key '" + label + "' is not valid base64");
            }
        }
    }
}

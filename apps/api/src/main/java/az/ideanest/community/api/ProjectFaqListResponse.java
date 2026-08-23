package az.ideanest.community.api;

import az.ideanest.community.application.FaqList;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * A campaign's whole FAQ tab, in the creator's order.
 *
 * <p><strong>No {@code nextCursor}, because there is no next page.</strong> §10.2 gives
 * this read no cursor and {@code ProjectFaqService} caps a campaign at fifty entries, so
 * the whole list is the response. A key that was always null would tell a client there is
 * a pagination protocol here to implement, and there is not — see
 * {@code PublicProjectFaqController} for what happens if fifty ever stops being enough.
 *
 * <p><strong>Nulls are written out.</strong> A campaign with no FAQ answers
 * {@code {"faqs": []}} rather than an object with a key missing: a client should not have
 * to tell "this campaign has answered nothing" from "this server does not send that key".
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProjectFaqListResponse(List<ProjectFaqResponse> faqs) {

    /** ASCII unit separator. Cannot occur in a UUID or in text the editor accepts. */
    private static final char FIELD_SEPARATOR = (char) 0x1f;

    /** ASCII record separator, so that a row boundary is not a field boundary. */
    private static final char ROW_SEPARATOR = (char) 0x1e;

    public static ProjectFaqListResponse of(FaqList list) {
        return new ProjectFaqListResponse(
                list.entries().stream().map(ProjectFaqResponse::of).toList());
    }

    /**
     * A validator for this exact body, per §10.3.
     *
     * <p>A digest over what is serialised rather than {@code hashCode()}, for the reason
     * {@code ProjectUpdateListResponse} gives: a tag has to mean the same thing on every
     * instance of the service and after a restart, and nothing guarantees a record's hash
     * does.
     *
     * <p><strong>The text is part of the tag, not just the identifiers.</strong> An FAQ
     * entry is editable in place — that is the difference between this tab and the
     * Updates tab — so a tag over the identifiers alone would serve a 304 for a list
     * whose every answer had been rewritten.
     *
     * <p>The order is part of it too, because it is the whole content of a reorder: two
     * lists with the same entries in different orders are different bodies, and a digest
     * that ignored the sequence would tell a reader nothing had changed after the creator
     * moved the most-asked question to the top.
     */
    public String etag() {
        StringBuilder canonical = new StringBuilder();
        for (ProjectFaqResponse faq : faqs) {
            canonical
                    .append(faq.id())
                    .append(FIELD_SEPARATOR)
                    .append(faq.question())
                    .append(FIELD_SEPARATOR)
                    .append(faq.answer())
                    .append(FIELD_SEPARATOR)
                    .append(ROW_SEPARATOR);
        }
        return digestOf(canonical.toString());
    }

    private static String digestOf(String canonical) {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JVM ships SHA-256. Reaching here is not a runtime condition.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        byte[] digest = sha256.digest(canonical.getBytes(StandardCharsets.UTF_8));
        return "\"" + HexFormat.of().formatHex(digest, 0, 8) + "\"";
    }
}

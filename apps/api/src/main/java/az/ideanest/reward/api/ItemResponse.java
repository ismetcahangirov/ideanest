package az.ideanest.reward.api;

import az.ideanest.reward.domain.Item;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * An item as its creator's editor sees it.
 *
 * <p><strong>Nulls are written out.</strong> The service is configured with
 * {@code default-property-inclusion: non_null}, which is right for most responses and
 * wrong for this one: the editor binds a form to every field here, and an absent key
 * cannot be told from a field that is genuinely unset. That is the same distinction
 * the {@code PATCH} body depends on, in the other direction. {@code ProjectEdit} is
 * annotated for the same reason.
 *
 * @param imageUrl where the image is, as the client declared it. Nothing has been
 *     uploaded and nothing has been measured — there is no media pipeline (§13) — and
 *     this becomes a reference to a {@code media} row when there is one
 * @param isDigital whether the item is delivered as a file. Named with its prefix
 *     because that is the JSON key the client reads and writes
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ItemResponse(
        UUID id,
        UUID projectId,
        String name,
        String description,
        String imageUrl,
        Integer weightGrams,
        boolean isDigital,
        String sku,
        Instant createdAt,
        Instant updatedAt) {

    public static ItemResponse of(Item item) {
        return new ItemResponse(
                item.getId(),
                item.getProjectId(),
                item.getName(),
                item.getDescription(),
                item.getImageUrl(),
                item.getWeightGrams(),
                item.isDigital(),
                item.getSku(),
                item.getCreatedAt(),
                item.getUpdatedAt());
    }
}

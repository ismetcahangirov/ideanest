package az.ideanest.reward.api;

import az.ideanest.reward.application.NewItem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What a client sends to add an item to a campaign.
 *
 * <p>A name and nothing else is required. A creator writes down "hardcover book"
 * before the prototype has been weighed and before there is a photograph of it, and
 * demanding either would mean the item could not be listed until it existed
 * physically.
 *
 * @param name 1–120 characters. Checked here so the message names the field, and again
 *     in {@code ItemService} and the database, which are the checks that hold for
 *     callers that are not this endpoint
 * @param isDigital defaults to false when omitted. A {@link Boolean} rather than a
 *     {@code boolean} so that the default is stated here rather than being whatever
 *     the JVM's zero value happens to be
 */
public record CreateItemRequest(
        @NotBlank(message = "A name is required") @Size(max = 120, message = "A name may not exceed 120 characters")
                String name,
        String description,
        String imageUrl,
        Integer weightGrams,
        Boolean isDigital,
        String sku) {

    public NewItem toDraft() {
        return new NewItem(name, description, imageUrl, weightGrams, Boolean.TRUE.equals(isDigital), sku);
    }
}

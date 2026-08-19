package az.ideanest.notification.api;

import az.ideanest.notification.domain.NotificationCategory;
import az.ideanest.notification.domain.NotificationType;
import java.util.List;

/**
 * The templates there are, for AD-15's list.
 *
 * <p>Each carries the two things a screen listing them needs: the identifier the preview
 * endpoint takes, and the category it groups under. The subject is deliberately not here
 * — it depends on the sample document, so putting it in a list would mean rendering
 * twenty templates to answer one request.
 *
 * @param templates in {@link NotificationType} declaration order, which is §4.10's table
 *     order. A list rather than a map, so the order is part of the response rather than
 *     something a client has to reconstruct
 */
public record EmailTemplateListResponse(List<Template> templates) {

    public static EmailTemplateListResponse of(List<NotificationType> types) {
        return new EmailTemplateListResponse(types.stream().map(Template::of).toList());
    }

    /**
     * @param type the wire name, which is the enum constant — the same string
     *     {@code notifications.type} stores and the preview path takes
     * @param category which of §4.10's groupings it falls under
     * @param mandatory whether a recipient may switch it off. True only for
     *     {@code SECURITY}: the person who would want a sign-in alert silenced is the one
     *     who stole the account
     */
    public record Template(String type, String category, boolean mandatory) {

        static Template of(NotificationType type) {
            NotificationCategory category = type.category();
            return new Template(type.name(), category.name(), category.isMandatory());
        }
    }
}

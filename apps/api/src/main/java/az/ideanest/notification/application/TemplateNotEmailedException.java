package az.ideanest.notification.application;

import az.ideanest.notification.domain.NotificationType;

/**
 * Asked to preview or send the email for a type the platform never emails.
 *
 * <p>There is exactly one such type — {@code DEADLINE_24H}, which §4.10 gives push and
 * in-app and no email column — and it has copy in {@code messages.properties} all the
 * same, because {@code EmailComposer}'s switch is exhaustive and every branch has to
 * resolve to something. So the template renders; it is simply not one the platform will
 * ever put on the wire, and previewing it would show staff a message no recipient can
 * receive.
 *
 * <p><strong>A 400 rather than a 404.</strong> Nothing is missing: the type is real and
 * the endpoint is right. What is wrong is the request, and a 404 would suggest a template
 * that could be added.
 */
public class TemplateNotEmailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient NotificationType type;

    public TemplateNotEmailedException(NotificationType type) {
        super(type + " has no email channel, so there is no email to preview");
        this.type = type;
    }

    public NotificationType type() {
        return type;
    }
}

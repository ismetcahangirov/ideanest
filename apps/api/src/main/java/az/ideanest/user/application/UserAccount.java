package az.ideanest.user.application;

import az.ideanest.shared.EmailAddress;
import java.util.UUID;

/**
 * What another module is told about a user.
 *
 * <p>Not the entity. The entity is this module's internal shape and is free to
 * change; handing it out would make every field a published contract and every
 * caller a reason not to change one. It would also hand out a live JPA
 * instance, which another module could then modify outside this module's
 * transaction.
 */
public record UserAccount(UUID id, EmailAddress email, String name, String slug, boolean emailVerified) {
}

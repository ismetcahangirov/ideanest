package az.ideanest.admin.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Why an account is being stopped — §4.11's AD-04.
 *
 * <p>Required. The person is told this, an appeal is answered from it, and whoever
 * reviews the decision later reads it; a ban with no reason is one nobody can defend and
 * nobody can undo with confidence. Bounded at the same 2,000 characters
 * {@code users_suspension_is_whole} enforces, so a long one is refused by validation with
 * a message rather than by the database with a constraint name.
 */
public record SuspendAccountRequest(
        @NotBlank(message = "A reason is required")
                @Size(max = 2000, message = "A reason may not exceed 2000 characters")
                String reason) {
}

package az.ideanest.admin.api;

import az.ideanest.user.application.AdministeredAccount;
import java.util.List;
import java.util.UUID;

/**
 * A page of accounts, newest first.
 *
 * <p>An object with two fields rather than a bare array, as every list response here is: a
 * top-level array cannot gain a field, and the first one it needed was this cursor.
 *
 * @param nextCursor what to pass as {@code after} for the following page, or null when
 *     this page is the end. <strong>Keyset rather than an offset</strong>: accounts are
 *     created underneath the reader, and an offset would drift a moderator straight past
 *     the account they are paging towards
 */
public record AdminUserListResponse(List<AdminUserResponse> users, UUID nextCursor) {

    public static AdminUserListResponse of(List<AdministeredAccount> accounts, int requestedSize) {
        List<AdminUserResponse> page = accounts.stream().map(AdminUserResponse::of).toList();
        // A short page is the last page. A full one may or may not be, and offering a
        // cursor that turns out to be empty is cheaper than withholding one that was
        // needed -- the alternative is reading one row further on every request.
        UUID cursor = page.size() < requestedSize || page.isEmpty() ? null : page.getLast().id();
        return new AdminUserListResponse(page, cursor);
    }
}

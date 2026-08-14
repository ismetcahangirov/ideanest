package az.ideanest.auth.api;

import java.util.List;

/**
 * The recovery codes, in the only response that will ever contain them.
 *
 * <p>What is stored is a SHA-256 of each, so this cannot be re-sent later: a
 * user who loses them asks for a new set, which retires the old one. That is
 * the property worth having — a list that can be fetched again is a list that
 * can be fetched by somebody else.
 *
 * @param recoveryCodes the codes, in the order they were generated
 */
public record RecoveryCodesResponse(List<String> recoveryCodes) {
}

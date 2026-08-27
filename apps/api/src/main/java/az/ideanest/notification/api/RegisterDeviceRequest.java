package az.ideanest.notification.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What a phone says when it registers for push — issue #87.
 *
 * <h2>There is no account in the body</h2>
 *
 * <p>Whose device this is comes from the access token, exactly as it does on
 * {@code UpdateNotificationPreferencesRequest}. A field here would be the only one that
 * mattered: registering somebody else's account against a token you hold is the whole of
 * the attack, and a body that carries the account is a body that has to be checked against
 * the token on every path through the controller.
 *
 * @param token the Expo push token. Validated for shape in {@code PushDevices} rather than
 *     with a {@code @Pattern} here, because the same rule has to hold for anything that
 *     reaches the table and a bean-validation annotation only covers this one door
 * @param platform {@code ios} or {@code android}, case-insensitively. Not used for
 *     routing — Expo decides that from the token — and kept because "it stopped working on
 *     one platform" is the most common shape of a push incident
 * @param deviceName what the phone calls itself, for the sessions screen. Optional, and
 *     truncated rather than refused when it is long
 * @param appVersion the build that registered. Optional, and the only way to answer "which
 *     versions are affected" when a payload renders wrongly
 */
public record RegisterDeviceRequest(
        @NotBlank(message = "A registration is an address") @Size(max = 200) String token,
        @NotBlank(message = "A registration names its platform") @Size(max = 20) String platform,
        @Size(max = 200) String deviceName,
        @Size(max = 60) String appVersion) {}

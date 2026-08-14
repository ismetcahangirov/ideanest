package az.ideanest.auth;

import static org.assertj.core.api.Assertions.assertThat;

import az.ideanest.shared.EmailAddress;
import az.ideanest.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** The device list and remote revocation, over HTTP. */
class SessionApiTests extends AbstractIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String PASSWORD = "a-long-enough-password";

    @Autowired
    private TestRestTemplate rest;

    private record SignedIn(String accessToken, String refreshToken) {
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private EmailAddress registeredUser() {
        EmailAddress email = EmailAddress.of("device" + SEQUENCE.incrementAndGet() + "@example.com");
        rest.postForEntity(
                "/v1/auth/register",
                Map.of("email", email.value(), "password", PASSWORD, "name", "Test Person"),
                String.class);
        return email;
    }

    private SignedIn signIn(EmailAddress email, String deviceLabel) {
        ResponseEntity<Map<String, Object>> response = rest.exchange(
                "/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "email", email.value(),
                                "password", PASSWORD,
                                "deviceLabel", deviceLabel,
                                "tokenDelivery", "body"),
                        jsonHeaders()),
                new ParameterizedTypeReference<Map<String, Object>>() {});

        return new SignedIn(
                (String) response.getBody().get("accessToken"), (String) response.getBody().get("refreshToken"));
    }

    private ResponseEntity<List<Map<String, Object>>> listSessions(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return rest.exchange(
                "/v1/auth/sessions",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    }

    private ResponseEntity<String> revokeSession(String accessToken, Object sessionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return rest.exchange(
                "/v1/auth/sessions/" + sessionId, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("the list shows every device, and marks the one asking")
    void listsLiveSessionsAndMarksTheCurrentOne() {
        EmailAddress email = registeredUser();
        signIn(email, "Laptop");
        SignedIn phone = signIn(email, "Phone");

        ResponseEntity<List<Map<String, Object>>> response = listSessions(phone.accessToken());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody()).extracting(session -> session.get("deviceLabel"))
                .containsExactlyInAnyOrder("Laptop", "Phone");

        // Without this flag the list is a row of near-identical devices and the
        // user cannot tell which one revoking will sign them out of.
        List<Map<String, Object>> current = response.getBody().stream()
                .filter(session -> Boolean.TRUE.equals(session.get("current")))
                .toList();
        assertThat(current).hasSize(1);
        assertThat(current.get(0)).containsEntry("deviceLabel", "Phone");
    }

    @Test
    @DisplayName("revoking a device stops its refresh token working")
    void revokingEndsThatDevicesSession() {
        EmailAddress email = registeredUser();
        SignedIn laptop = signIn(email, "Laptop");
        SignedIn phone = signIn(email, "Phone");

        Object laptopSessionId = listSessions(phone.accessToken()).getBody().stream()
                .filter(session -> "Laptop".equals(session.get("deviceLabel")))
                .findFirst()
                .orElseThrow()
                .get("id");

        assertThat(revokeSession(phone.accessToken(), laptopSessionId).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // This is the entire point of the feature: the lost laptop cannot get
        // another access token.
        ResponseEntity<String> refreshed = rest.exchange(
                "/v1/auth/refresh",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("refreshToken", laptop.refreshToken()), jsonHeaders()),
                String.class);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(listSessions(phone.accessToken()).getBody()).hasSize(1);
    }

    @Test
    @DisplayName("one user cannot see or revoke another's devices")
    void sessionsAreScopedToTheirOwner() {
        EmailAddress mine = registeredUser();
        EmailAddress theirs = registeredUser();
        SignedIn me = signIn(mine, "Mine");
        SignedIn them = signIn(theirs, "Theirs");

        assertThat(listSessions(me.accessToken()).getBody())
                .extracting(session -> session.get("deviceLabel"))
                .containsExactly("Mine");

        Object theirSessionId =
                listSessions(them.accessToken()).getBody().get(0).get("id");

        // §17.3 calls this an insecure direct object reference, and 404 rather
        // than 403 so that the response does not confirm the identifier exists.
        assertThat(revokeSession(me.accessToken(), theirSessionId).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(listSessions(them.accessToken()).getBody()).hasSize(1);
    }

    @Test
    @DisplayName("an unknown session identifier is not found")
    void unknownSessionIsNotFound() {
        EmailAddress email = registeredUser();
        SignedIn signedIn = signIn(email, "Laptop");

        assertThat(revokeSession(signedIn.accessToken(), java.util.UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("the device list needs an access token")
    void listRequiresAuthentication() {
        assertThat(rest.getForEntity("/v1/auth/sessions", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}

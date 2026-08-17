package az.ideanest.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The distinction a partial update depends on.
 *
 * <p>The campaign editor autosaves one field at a time. If a body containing
 * only a title were read as "set the title and clear everything else", the first
 * keystroke in the title field would delete the summary, the goal, and the story
 * — and the request would look completely ordinary in a log. This test is the
 * thing standing between that and production, which is why it asserts the
 * behaviour of the Jackson hooks directly rather than through an endpoint.
 *
 * <p>A plain unit test: it needs no database and no application context.
 */
class PatchedTests {

    /** A stand-in for the real request records, kept here so the test states its own shape. */
    private record Body(Patched<String> name, Patched<BigDecimal> amount, Patched<UUID> reference) {
        Body {
            // The normalisation every request record using Patched performs. See
            // the note on Patched: it is belt and braces against a Jackson
            // version that stops consulting getAbsentValue.
            name = Patched.orAbsent(name);
            amount = Patched.orAbsent(amount);
            reference = Patched.orAbsent(reference);
        }
    }

    private final ObjectMapper json = new ObjectMapper();

    private Body read(String body) throws Exception {
        return json.readValue(body, Body.class);
    }

    @Test
    @DisplayName("a field that was not mentioned is absent")
    void anAbsentFieldIsAbsent() throws Exception {
        Body body = read("{\"name\":\"A campaign\"}");

        assertThat(body.name().isPresent()).isTrue();
        assertThat(body.name().value()).isEqualTo("A campaign");

        // The whole point. Absent means "leave what is stored alone", and a
        // caller using ifPresent cannot write over it by accident.
        assertThat(body.amount().isPresent()).isFalse();
        assertThat(body.reference().isPresent()).isFalse();
    }

    @Test
    @DisplayName("a field explicitly set to null is present and clears the value")
    void anExplicitNullIsAClear() throws Exception {
        Body body = read("{\"name\":null}");

        // RFC 7396: null means remove the member. Told apart from absence, which
        // is the one thing Optional cannot do here.
        assertThat(body.name().isPresent()).isTrue();
        assertThat(body.name().value()).isNull();
    }

    @Test
    @DisplayName("an empty document changes nothing")
    void anEmptyDocumentIsAllAbsent() throws Exception {
        Body body = read("{}");

        assertThat(body.name().isPresent()).isFalse();
        assertThat(body.amount().isPresent()).isFalse();
        assertThat(body.reference().isPresent()).isFalse();
    }

    @Test
    @DisplayName("the wrapped value is converted to its declared type")
    void theInnerTypeIsResolved() throws Exception {
        UUID reference = UUID.randomUUID();
        Body body = read("{\"amount\":\"12.34\",\"reference\":\"" + reference + "\"}");

        // Resolved from the property's type parameter, not guessed from the JSON:
        // a money amount arrives as a string and has to land in a BigDecimal.
        assertThat(body.amount().value()).isEqualTo(new BigDecimal("12.34"));
        assertThat(body.reference().value()).isEqualTo(reference);
    }

    @Test
    @DisplayName("ifPresent runs only for a field the client sent")
    void ifPresentRunsOnlyWhenPresent() {
        StringBuilder applied = new StringBuilder();

        Patched.<String>absent().ifPresent(applied::append);
        assertThat(applied).isEmpty();

        Patched.of("value").ifPresent(applied::append);
        assertThat(applied).hasToString("value");
    }

    @Test
    @DisplayName("mapping preserves absence and preserves an explicit clear")
    void mappingPreservesBothCases() {
        assertThat(Patched.<String>absent().map(String::length).isPresent()).isFalse();

        // The dangerous case: a client asking for a field to be cleared must not
        // come out of a conversion looking like a client that said nothing.
        Patched<Integer> cleared = Patched.<String>of(null).map(String::length);
        assertThat(cleared.isPresent()).isTrue();
        assertThat(cleared.value()).isNull();

        assertThat(Patched.of("four").map(String::length).value()).isEqualTo(4);
    }

    @Test
    @DisplayName("a malformed value is rejected rather than read as absent")
    void aMalformedValueFails() {
        // Silently treating this as "field not mentioned" would turn a client bug
        // into a save that appears to have worked and did nothing.
        assertThatThrownBy(() -> read("{\"reference\":\"not-a-uuid\"}")).isInstanceOf(Exception.class);
    }
}

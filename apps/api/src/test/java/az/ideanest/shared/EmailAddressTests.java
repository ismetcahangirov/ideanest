package az.ideanest.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmailAddressTests {

    @Test
    @DisplayName("addresses are normalised, so one person is one account")
    void normalises() {
        assertThat(EmailAddress.of("  Person@Example.COM ").value()).isEqualTo("person@example.com");
        assertThat(EmailAddress.of("PERSON@EXAMPLE.COM")).isEqualTo(EmailAddress.of("person@example.com"));
    }

    @Test
    @DisplayName("something that is not an address is refused at construction")
    void rejectsNonAddresses() {
        assertThatThrownBy(() -> EmailAddress.of("person")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmailAddress.of("person@localhost")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmailAddress.of("two people@example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmailAddress.of("a@b.c".repeat(60))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("printing an address masks it")
    void toStringIsMasked() {
        // This type reaches log lines and exception messages. §17.4 keeps
        // addresses out of both, and the default record toString would not.
        assertThat(EmailAddress.of("person@example.com")).hasToString("p***@example.com");
        assertThat(EmailAddress.of("person@example.com").value()).isEqualTo("person@example.com");
    }
}

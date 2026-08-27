package az.ideanest.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import az.ideanest.fx.application.PublishedRate;
import az.ideanest.fx.application.RateSourceUnavailableException;
import az.ideanest.fx.infrastructure.CentralBankRates;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * The Central Bank of Azerbaijan adapter — issue #327.
 *
 * <p>A unit test against a document on disk, which is where a parser belongs. The suite
 * never reaches cbar.az: a test that fetched a public website would fail for reasons that
 * are not ours, on somebody else's schedule, and it could not produce the cases that matter
 * — a source that is down, one serving a Friday publication on a Sunday, a rewritten
 * document, a doctype.
 *
 * <p>The fixture is the real shape, copied from the live document on 27 August 2026: a
 * {@code ValType} of bank metals before the currencies, a rouble quoted per hundred, and a
 * currency the platform does not offer.
 */
class CentralBankRatesTests {

    private static final Instant NOW = Instant.parse("2026-08-27T09:05:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String URL = "https://rates.test.invalid/{date}.xml";
    private static final String FETCHED = "https://rates.test.invalid/27.08.2026.xml";

    private static FxProperties properties() {
        return new FxProperties(
                true, URL, "AZN", List.of("USD", "EUR", "TRY", "RUB"), "-", Duration.ofDays(4), Duration.ofSeconds(10));
    }

    /**
     * The adapter, with a canned response behind it.
     *
     * <p>{@code MockRestServiceServer} rather than a stub HTTP server, because what is being
     * tested is the parsing and the URL — both of which are properties of this class — and a
     * second Jetty in the test JVM would only add a port.
     */
    private static CentralBankRates sourceAnswering(String body, MediaType type) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer.bindTo(builder)
                .build()
                .expect(requestTo(FETCHED))
                .andRespond(withSuccess(body, type));
        return new CentralBankRates(builder, properties(), CLOCK);
    }

    private static String fixture(String name) {
        try (InputStream document = CentralBankRatesTests.class.getResourceAsStream("/fx/" + name)) {
            if (document == null) {
                throw new IllegalStateException("No fixture /fx/" + name);
            }
            return new String(document.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new IllegalStateException(unreadable);
        }
    }

    @Test
    @DisplayName("reads the currencies it offers, and skips the ones it does not")
    void readsTheOfferedCurrencies() {
        List<PublishedRate> published =
                sourceAnswering(fixture("cbar-27.08.2026.xml"), MediaType.APPLICATION_XML).fetch();

        assertThat(published).extracting(PublishedRate::quoteCurrency).containsExactly("USD", "EUR", "TRY", "RUB");
        // Gold, and the Georgian lari. Both are in the document and neither is a display
        // choice: filtered before they are parsed, so nothing reaches the table that a
        // settings screen would have to hide.
        assertThat(published).extracting(PublishedRate::quoteCurrency).doesNotContain("XAU", "GEL");
    }

    /**
     * <strong>The rouble, which is where the nominal stops being academic.</strong>
     *
     * <p>The document says 2.0484 manat per <em>hundred</em> roubles. A reader that took the
     * value at face value would be out by a factor of a hundred — and in the direction that
     * makes a campaign look a hundred times cheaper, which is a figure somebody would act on
     * before anybody noticed.
     */
    @Test
    @DisplayName("normalises a rate quoted per hundred to a rate per one")
    void normalisesTheNominal() {
        List<PublishedRate> published =
                sourceAnswering(fixture("cbar-27.08.2026.xml"), MediaType.APPLICATION_XML).fetch();

        assertThat(published)
                .filteredOn(rate -> rate.quoteCurrency().equals("RUB"))
                .singleElement()
                .satisfies(rate -> assertThat(rate.rate()).isEqualByComparingTo("0.020484"));
        assertThat(published)
                .filteredOn(rate -> rate.quoteCurrency().equals("USD"))
                .singleElement()
                .satisfies(rate -> assertThat(rate.rate()).isEqualByComparingTo("1.7"));
    }

    /**
     * <strong>The date in the document, never the date in the request.</strong>
     *
     * <p>Asking cbar.az for a Sunday returns a document whose own {@code Date} is the
     * preceding Friday: it serves the last published day rather than refusing. A client that
     * assumed the requested date would write three identical rows over a weekend, each
     * claiming to be that day's official rate — and the Sunday row would be a claim the
     * central bank never made.
     */
    @Test
    @DisplayName("believes the document's publication date and not the one it asked for")
    void believesTheDocumentsDate() {
        String friday = fixture("cbar-27.08.2026.xml").replace("Date=\"27.08.2026\"", "Date=\"21.08.2026\"");

        List<PublishedRate> published = sourceAnswering(friday, MediaType.APPLICATION_XML).fetch();

        assertThat(published)
                .extracting(PublishedRate::publishedFor)
                .containsOnly(LocalDate.of(2026, 8, 21));
    }

    // ------------------------------------------------------------------
    // What is not a rate
    // ------------------------------------------------------------------

    /**
     * The parser refuses a doctype outright, which closes XXE and entity expansion.
     *
     * <p>This is the only XML in the service and it comes from outside it. A rates document
     * has no DTD, so refusing every doctype costs nothing and removes the class of attack
     * rather than mitigating it — the fixture is a billion-laughs opener, and the assertion
     * is that it never gets as far as expanding anything.
     */
    @Test
    @DisplayName("refuses a document carrying a doctype")
    void refusesADoctype() {
        CentralBankRates source = sourceAnswering(fixture("cbar-billion-laughs.xml"), MediaType.APPLICATION_XML);

        assertThatThrownBy(source::fetch)
                .isInstanceOf(RateSourceUnavailableException.class)
                .hasMessageContaining("not the document it promised");
    }

    @Test
    @DisplayName("treats an HTML error page served with a 200 as an outage")
    void refusesSomethingThatIsNotTheDocument() {
        CentralBankRates source = sourceAnswering("<html><body>Service unavailable</body></html>", MediaType.TEXT_HTML);

        // The failure mode a public website actually has. Parsed successfully as XML and
        // carrying no rates, which is why "published nothing" has to be refused rather than
        // read as a quiet day.
        assertThatThrownBy(source::fetch).isInstanceOf(RateSourceUnavailableException.class);
    }

    @Test
    @DisplayName("treats an empty body as an outage rather than as an unchanged rate")
    void refusesAnEmptyBody() {
        CentralBankRates source = sourceAnswering("", MediaType.APPLICATION_XML);

        assertThatThrownBy(source::fetch)
                .isInstanceOf(RateSourceUnavailableException.class)
                .hasMessageContaining("empty body");
    }

    @Test
    @DisplayName("reports a failed request as an outage, naming the URL")
    void reportsAFailedRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer.bindTo(builder).build().expect(requestTo(FETCHED)).andRespond(withServerError());
        CentralBankRates source = new CentralBankRates(builder, properties(), CLOCK);

        assertThatThrownBy(source::fetch)
                .isInstanceOf(RateSourceUnavailableException.class)
                .hasMessageContaining(FETCHED);
    }

    @Test
    @DisplayName("skips one unreadable entry rather than losing every rate")
    void skipsOneBadEntry() {
        String broken = fixture("cbar-27.08.2026.xml").replace("<Value>1.9877</Value>", "<Value>not a number</Value>");

        List<PublishedRate> published = sourceAnswering(broken, MediaType.APPLICATION_XML).fetch();

        // The document carries about forty entries and one of them being malformed is not a
        // reason to have no rates at all: the platform would rather price three currencies
        // than none.
        assertThat(published).extracting(PublishedRate::quoteCurrency).containsExactly("USD", "TRY", "RUB");
    }
}

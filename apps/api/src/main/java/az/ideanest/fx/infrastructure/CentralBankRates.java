package az.ideanest.fx.infrastructure;

import az.ideanest.fx.FxProperties;
import az.ideanest.fx.application.PublishedRate;
import az.ideanest.fx.application.RateSourceUnavailableException;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.XMLConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The Central Bank of Azerbaijan's daily rates — issue #327.
 *
 * <h2>The document</h2>
 *
 * {@code https://www.cbar.az/currencies/27.08.2026.xml}, public and unauthenticated:
 *
 * <pre>{@code
 * <ValCurs Date="27.08.2026" Name="AZN məzənnələri" Description="...">
 *   <ValType Type="Bank metalları"> … </ValType>
 *   <ValType Type="Xarici valyutalar">
 *     <Valute Code="USD"><Nominal>1</Nominal><Name>1 ABŞ dolları</Name><Value>1.7</Value></Valute>
 *     <Valute Code="RUB"><Nominal>100</Nominal><Name>100 Rusiya rublu</Name><Value>2.0484</Value></Valute>
 *   </ValType>
 * </ValCurs>
 * }</pre>
 *
 * <p><strong>{@code Value} is in manat, per {@code Nominal} units.</strong> So the rouble
 * above is 2.0484 manat per hundred roubles, and {@code PublishedRate} carries
 * {@code 0.020484} — normalised here, once, rather than in every reader.
 *
 * <h2>THE DATE IN THE DOCUMENT IS BELIEVED AND THE DATE IN THE REQUEST IS NOT</h2>
 *
 * Asking for a Sunday returns a document whose own {@code Date} attribute is the preceding
 * Friday: the source serves the last published day rather than refusing. A client that
 * assumed the requested date would write three identical rows a weekend, each claiming to be
 * that day's official rate — and the row for Sunday would be a claim the central bank never
 * made. So {@code @Date} is what is stored, which also makes the hourly refresh idempotent
 * against V59's unique index.
 *
 * <h2>THE PARSER IS HARDENED, AND ON THIS ENDPOINT IT MATTERS</h2>
 *
 * This is the only XML in the service and it comes from outside it. The factory disables
 * external general and parameter entities, disallows doctype declarations entirely, and sets
 * {@code XMLConstants.FEATURE_SECURE_PROCESSING} — which together answer XXE and the
 * billion-laughs expansion. A DTD is not something a rates document has, so refusing one
 * outright costs nothing and removes the class of attack rather than mitigating it.
 *
 * <h2>Bank metals are skipped, and by their code rather than by their heading</h2>
 *
 * {@code XAU}, {@code XAG}, {@code XPT}, {@code XPD} — gold, silver, platinum, palladium.
 * They are filtered by {@code ideanest.fx.display-currencies} anyway, which is a closed set,
 * so this is belt and braces rather than the control: the point of the filter upstream is
 * that nothing reaches the table that a settings screen would have to hide.
 */
@Component
public class CentralBankRates implements az.ideanest.fx.application.RateSource {

    private static final Logger log = LoggerFactory.getLogger(CentralBankRates.class);

    /** The path's date format, and the document's. Both {@code dd.MM.yyyy}. */
    private static final DateTimeFormatter SOURCE_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ROOT);

    private final RestClient http;
    private final FxProperties properties;
    private final Clock clock;

    public CentralBankRates(RestClient.Builder http, FxProperties properties, Clock clock) {
        this.http = http.build();
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public az.ideanest.fx.domain.RateSource name() {
        return az.ideanest.fx.domain.RateSource.CBAR;
    }

    @Override
    public List<PublishedRate> fetch() {
        String url = properties
                .sourceUrl()
                .replace("{date}", SOURCE_DATE.format(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)));

        byte[] document;
        try {
            document = http.get()
                    .uri(url)
                    .header("accept", "application/xml, text/xml")
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientException failure) {
            throw new RateSourceUnavailableException("Could not reach the rate source at " + url, failure);
        }

        if (document == null || document.length == 0) {
            // A 200 with nothing in it. Not a day with no publication — that is a document
            // saying so — and treating it as one would let an empty response mean "the
            // rates have not changed" for ever.
            throw new RateSourceUnavailableException("The rate source at " + url + " answered with an empty body");
        }
        return parse(document, url);
    }

    private List<PublishedRate> parse(byte[] document, String url) {
        Element root;
        try {
            root = secureBuilder().parse(new ByteArrayInputStream(document)).getDocumentElement();
        } catch (Exception malformed) {
            // Deliberately broad. A rewritten document, an HTML error page served with a
            // 200, a truncated response -- every one of them is the same operational fact,
            // and none of them is a rate.
            throw new RateSourceUnavailableException("The rate source at " + url + " is not the document it promised", malformed);
        }

        LocalDate publishedFor = publicationDateOf(root, url);
        List<PublishedRate> published = new ArrayList<>();

        NodeList valutes = root.getElementsByTagName("Valute");
        for (int index = 0; index < valutes.getLength(); index++) {
            Node node = valutes.item(index);
            if (!(node instanceof Element valute)) {
                continue;
            }
            rateOf(valute, publishedFor).ifPresent(published::add);
        }

        if (published.isEmpty()) {
            throw new RateSourceUnavailableException(
                    "The rate source at " + url + " published no readable currency");
        }
        return List.copyOf(published);
    }

    /**
     * The day the central bank says these are in force from.
     *
     * <p>From the document, never from the request — see the class note, which is where the
     * consequence of getting this wrong is spelled out.
     */
    private static LocalDate publicationDateOf(Element root, String url) {
        String date = root.getAttribute("Date");
        try {
            return LocalDate.parse(date, SOURCE_DATE);
        } catch (DateTimeParseException unreadable) {
            throw new RateSourceUnavailableException(
                    "The rate source at " + url + " carries no readable Date attribute: " + date, unreadable);
        }
    }

    /**
     * One {@code <Valute>}, normalised to a nominal of one.
     *
     * <p>Returns empty rather than throwing for a single unreadable entry. The document
     * carries about forty and one of them being malformed is not a reason to have no rates
     * at all — the platform would rather price four currencies than none. A document in
     * which <em>every</em> entry is unreadable is a different matter and {@link #parse}
     * refuses it.
     */
    private java.util.Optional<PublishedRate> rateOf(Element valute, LocalDate publishedFor) {
        String code = valute.getAttribute("Code").trim().toUpperCase(Locale.ROOT);
        if (code.isEmpty() || !properties.displayCurrencies().contains(code)) {
            // Not a currency this deployment offers. Skipped before it is parsed, so a
            // malformed entry for a currency nobody can choose costs nothing.
            return java.util.Optional.empty();
        }

        try {
            BigDecimal nominal = new BigDecimal(textOf(valute, "Nominal"));
            BigDecimal value = new BigDecimal(textOf(valute, "Value"));
            if (nominal.signum() <= 0 || value.signum() <= 0) {
                log.warn("The rate source published a non-positive {} rate; skipping it.", code);
                return java.util.Optional.empty();
            }
            /*
             * Ten decimal places, matching V59's column exactly, and HALF_EVEN because every
             * rounding on this platform is (§21.2). The division is here rather than in a
             * reader for the reason PublishedRate gives: a reader that forgot it would be
             * out by a factor of a hundred on the rouble.
             */
            BigDecimal perUnit = value.divide(nominal, 10, java.math.RoundingMode.HALF_EVEN);
            return java.util.Optional.of(new PublishedRate(code, perUnit, publishedFor));
        } catch (RuntimeException unreadable) {
            log.warn("The rate source published an unreadable {} entry; skipping it.", code, unreadable);
            return java.util.Optional.empty();
        }
    }

    private static String textOf(Element valute, String tag) {
        NodeList found = valute.getElementsByTagName(tag);
        if (found.getLength() == 0) {
            throw new IllegalArgumentException("No <" + tag + "> in this entry");
        }
        String text = found.item(0).getTextContent();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("An empty <" + tag + "> in this entry");
        }
        return text.trim();
    }

    /**
     * A parser that will not fetch anything, expand anything, or accept a doctype.
     *
     * <p>Built per call rather than shared: {@code DocumentBuilder} is not thread-safe, and
     * a shared one behind a job that could one day run on two replicas is a defect that
     * appears as a corrupt parse under load rather than as an error.
     */
    private static DocumentBuilder secureBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        // The one that closes XXE outright rather than mitigating it: a rates document has
        // no DTD, so refusing every doctype costs nothing and removes the class.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }
}

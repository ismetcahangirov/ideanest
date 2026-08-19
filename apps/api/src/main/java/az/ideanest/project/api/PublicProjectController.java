package az.ideanest.project.api;

import az.ideanest.project.application.PublicProjectPage;
import az.ideanest.project.application.PublicProjects;
import az.ideanest.project.application.Taxonomy;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The campaign page, to anybody — §10.2's
 * {@code GET /v1/projects/{creatorSlug}/{projectSlug}}.
 *
 * <p><strong>Addressed by two slugs, not by an identifier, and that is the whole point
 * of the path.</strong> It is the URL a campaign is shared under, indexed under, and
 * linked to from every card in discovery; {@code projects_creator_slug_key} makes the
 * pair unique, which is why two creators may both launch a "coffee-table-book" without
 * either being renamed. Every other read of a campaign in this module takes the
 * identifier, because every other read is somebody's own view of their own campaign.
 *
 * <p><strong>A separate controller from {@link ProjectController}, for
 * {@link PrelaunchController}'s reason:</strong> everything on that one requires a bearer
 * token and answers a stranger with 404, and this requires nothing at all. Keeping the
 * difference in the file layout is what stops a public endpoint inheriting an
 * authenticated controller's shape by being added next to one.
 *
 * <h2>Why this endpoint exists now</h2>
 *
 * <p>#119. §4.4's page had no read behind it, so {@code apps/web} had nothing to render
 * on the server and a campaign page would have had to assemble itself in the browser —
 * which puts the campaign's title, summary and story outside the first byte of HTML, and
 * that is precisely what a crawler and a link unfurler are given. The endpoint is the
 * server-render's data source before it is anything else, which is why it carries the
 * story document: a page that fetched its own body separately would still fail the thing
 * #119 is about.
 *
 * <h2>Caching</h2>
 *
 * <p>§10.3 asks for {@code ETag} and {@code Cache-Control} on public reads, and this one
 * has an unusually good case for both: it is the most-requested read on the platform,
 * every visitor gets the same bytes, and a campaign near its deadline is refreshed by
 * people watching a number move. A minute of shared caching turns a link on social media
 * into one origin request rather than one per visitor, and the tag turns each subsequent
 * poll into a 304 with no body.
 *
 * <p>{@code Vary: Accept-Language}, because the category and subcategory names are
 * resolved per language: without it a cache would serve one reader's Azerbaijani
 * breadcrumb to another reader's English page. The tag is derived over the language as
 * well, for the same reason {@code PublicReads.etagOf} hashes it first.
 */
@RestController
public class PublicProjectController {

    /**
     * How long the page may be held.
     *
     * <p>A minute, matching {@code /v1/discover}: the mutable parts are the pledged total
     * and the backer count, and those are exactly what somebody refreshing a nearly
     * funded campaign is watching. Longer would show a number a visitor could tell was
     * wrong; shorter would give up the caching on the one read that most needs it.
     *
     * <p>Public rather than private. There is nothing in this response that belongs to
     * the person reading it — no session, no personalisation, not even a "have you backed
     * this" flag, which is deliberate: adding one would make this response
     * per-visitor and cost the shared cache that pays for the whole page.
     */
    private static final long MAX_AGE_SECONDS = 60;

    /**
     * ASCII unit separator, between the language and the body in the digest below.
     *
     * <p>The same character {@code discovery.api.PublicReads} uses, and for the same
     * reason: it cannot occur in a locale code or in JSON, so no pair of (locale, body)
     * can be re-cut into a different pair that hashes the same.
     */
    private static final char LOCALE_SEPARATOR = (char) 0x1f;

    private final PublicProjects projects;
    private final ObjectMapper json;

    public PublicProjectController(PublicProjects projects, ObjectMapper json) {
        this.projects = projects;
        this.json = json;
    }

    /**
     * The campaign at this URL, or 404.
     *
     * <p>404 covers three different facts — no such creator, no such campaign, and a
     * campaign in a state the public may not see — and {@link PublicProjects#page} is
     * where that decision is made and argued. A 403 for the third would confirm that a
     * campaign exists at a URL somebody guessed, which on a suspended campaign is a
     * statement about an open investigation.
     *
     * <p><strong>The two path variables are slugs and are never interpreted as
     * anything else.</strong> They reach the query as bound parameters, and a campaign
     * addressed by identifier resolves to a different mapping — {@code /{id}/edit} and its
     * siblings are literal-segment patterns, which Spring MVC prefers over two variables.
     *
     * @param acceptLanguage §10.3's localisation header; narrowed to one of §21.1's four
     *     by {@link Taxonomy#localeFor}, which also decides what an unsupported or absent
     *     header means
     * @return {@code 304} when the caller already holds this page in this language
     */
    @GetMapping("/v1/projects/{creatorSlug}/{projectSlug}")
    public ResponseEntity<ProjectPageResponse> page(
            WebRequest request,
            HttpServletResponse response,
            @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
            @PathVariable String creatorSlug,
            @PathVariable String projectSlug) {

        String locale = Taxonomy.localeFor(acceptLanguage);
        PublicProjectPage page = projects.page(creatorSlug, projectSlug, locale);
        ProjectPageResponse body = ProjectPageResponse.of(page, storyOf(page));

        // On the raw response, because the 304 below is written by checkNotModified and
        // never passes through a ResponseEntity — the same arrangement DiscoveryController
        // uses, and for the same reason.
        response.setHeader(HttpHeaders.VARY, HttpHeaders.ACCEPT_LANGUAGE);

        String etag = etagOf(locale, body);
        if (request.checkNotModified(etag)) {
            return null;
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.maxAge(MAX_AGE_SECONDS, TimeUnit.SECONDS).cachePublic())
                .body(body);
    }

    /**
     * The story document as JSON, not as a string containing JSON.
     *
     * <p>{@code ProjectEditResponses} does the same for the creator's own view and
     * explains the parse: the column is {@code jsonb}, the projection holds it as text,
     * and returning the text would hand every client an escaped document to parse a
     * second time.
     */
    private JsonNode storyOf(PublicProjectPage page) {
        String story = page.story();
        if (story == null) {
            return null;
        }
        try {
            return json.readTree(story);
        } catch (JacksonException e) {
            // Unreachable: PostgreSQL validates jsonb on the way in, so a row that fails
            // here was written by something that bypassed the column type. Serving it as
            // though it were fine would spread the problem onto a public page.
            throw new IllegalStateException("Project " + page.id() + " holds a story that is not JSON", e);
        }
    }

    /**
     * A tag derived from what is actually served.
     *
     * <p><strong>Over the serialised body rather than over a hand-written canonical
     * string.</strong> {@code discovery.api.PublicReads} builds one field by field, which
     * is right for a feed of small cards and wrong here: this response has seventeen
     * fields, four of them nested and one of them a document of arbitrary depth, so a
     * canonical form would be a second serialiser to keep in step — and a tag that fails
     * to change when the part it forgot does is a visitor being told 304 about a page that
     * moved. Serialising once and hashing the bytes cannot forget a field.
     *
     * <p>Deterministic: a record serialises its components in declaration order, so the
     * same content produces the same bytes on every instance and after a restart. Never
     * {@code hashCode()}, for the reason {@code PublicReads} gives — nothing guarantees a
     * record's hash is stable across JVMs.
     *
     * <p>The locale is hashed first, so two languages of one campaign cannot share a tag.
     */
    private String etagOf(String locale, ProjectPageResponse body) {
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every JVM ships SHA-256. Reaching here is not a runtime condition.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        byte[] content =
                (locale + LOCALE_SEPARATOR + json.writeValueAsString(body)).getBytes(StandardCharsets.UTF_8);
        return "\"" + HexFormat.of().formatHex(sha256.digest(content), 0, 8) + "\"";
    }
}

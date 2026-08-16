package az.ideanest.support;

import az.ideanest.project.infrastructure.CategoryRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Puts a campaign into the state a test needs to start from, by writing the row.
 *
 * <p><strong>For suites that are not testing the lifecycle.</strong> The honest way
 * into {@code LIVE} is the four requests a creator actually makes — submit,
 * approve, launch, and a moderator to do the middle one — and
 * {@code ProjectLifecycleApiTests} takes it, because that path is what it is
 * checking. A suite about reward tiers is not: driving every one of its fixtures
 * through moderation would make each of its tests depend on the moderation
 * configuration and on a second account sharing one rate limiter, for a campaign
 * whose state is a precondition rather than a subject.
 *
 * <p>What is written is exactly what the launch transition writes — the state, the
 * launch instant, and the deadline computed from the duration — so the row that
 * results is one the application could have produced. Every check constraint on
 * {@code projects} still applies, including
 * {@code projects_public_states_are_fully_specified}, which is why the goal and the
 * duration are filled in here when the campaign has none.
 *
 * <p>What is <em>not</em> written is the audit row. A campaign launched this way has
 * a history that does not mention its launch, so a test that reads
 * {@code project_state_transitions} must not use this.
 */
public final class Campaigns {

    /** What a campaign gets when a test did not care. Inside §5.3's 1–60 days. */
    private static final int DEFAULT_DURATION_DAYS = 30;

    private Campaigns() {
    }

    /** Takes the campaign live, as of now, keeping any goal and duration it already has. */
    public static void launch(DataSource dataSource, UUID projectId) {
        int updated = new JdbcTemplate(dataSource)
                .update(
                        """
                        UPDATE projects
                           SET state = 'LIVE',
                               goal_amount = COALESCE(goal_amount, 5000.00),
                               duration_days = COALESCE(duration_days, ?),
                               launched_at = now(),
                               deadline = now() + make_interval(days => COALESCE(duration_days, ?))
                         WHERE id = ?
                        """,
                        DEFAULT_DURATION_DAYS,
                        DEFAULT_DURATION_DAYS,
                        projectId);

        if (updated != 1) {
            // A fixture that silently launched nothing would leave every assertion
            // after it passing against a draft.
            throw new IllegalStateException("No campaign " + projectId + " to launch");
        }
    }

    /**
     * The one patch that takes a fresh draft to a campaign §5.3 will accept for
     * submission.
     *
     * <p>Here rather than copied into each suite because three test classes create
     * campaigns in order to submit them, and a fixture that is complete in two of
     * them and out of date in the third produces a failure about the fixture wearing
     * the name of the behaviour being tested. When §5.3 gains a requirement, this is
     * the one place the suites have to be taught about it.
     *
     * <p><strong>Deliberately only what blocks.</strong> No subcategory, no scheduled
     * launch, no reward tiers, and a story with no pictures in it — so a campaign
     * built from this is submittable and is not finished, which is exactly the
     * distinction the completeness checklist exists to draw and what
     * {@code ProjectChecklistApiTests} uses to tell advice apart from refusal.
     *
     * <p>Sent as a single body because that is what the editor's autosave would
     * eventually produce field by field, and because a fixture made of six requests
     * is a fixture whose failures are six times harder to read.
     */
    public static Map<String, Object> completeBasics(CategoryRepository categories) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("blurb", "A summary that fits inside a hundred and thirty-five characters.");
        body.put("categoryId", categories.findBySlug("games").orElseThrow().getId().toString());
        body.put("goal", Map.of("amount", "5000.00", "currency", "AZN"));
        body.put("durationDays", DEFAULT_DURATION_DAYS);
        // §5.3: at least 1024×576. The recorded dimensions are what the checklist
        // measures, because nothing on the server has ever seen the file.
        body.put("coverImage", Map.of("url", "https://cdn.example.com/cover.jpg", "width", 1600, "height", 900));
        body.put("story", story(600));
        // §5.3 requires two hundred characters, emphatically. A campaign that says
        // nothing about what could go wrong is the one that produces the refunds.
        body.put("risks", "The main risk is manufacturing capacity. ".repeat(6));
        return body;
    }

    /**
     * Removes every campaign and every tag, so a suite starts from a known set.
     *
     * <p>Discovery is the one module whose assertions are about <em>which</em>
     * campaigns come back and <em>how many</em>, so it cannot share a database with
     * whatever another suite happened to leave behind. Every foreign key into
     * {@code projects} cascades — rewards, items, story versions, collaborators,
     * reminders, tag edges — so this one statement is the whole cleanup.
     *
     * <p>Safe because JUnit runs test classes one at a time here: nothing is
     * configured for parallel execution, so no other suite is mid-run.
     */
    public static void clear(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM projects");
        jdbc.update("DELETE FROM tags");
    }

    /**
     * An account to file campaigns under.
     *
     * <p>The minimum {@code users} will accept. No credentials row and no verified
     * email: discovery never authenticates anybody, and a fixture that signed in
     * would make every one of its tests depend on the rate limiter.
     *
     * <p>Idempotent for one handle. {@link #clear} removes campaigns and not accounts
     * — an account is referenced by sessions, credentials, and provider identities
     * that belong to other suites — so a {@code @BeforeEach} that asks for the same
     * creator twice gets the same row rather than a duplicate-key failure on the
     * second test in the class.
     *
     * @param handle becomes the slug and the local part of the email, so one argument
     *     keeps both unique
     */
    public static UUID creator(DataSource dataSource, String handle) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update(
                "INSERT INTO users (id, email, name, slug) VALUES (?, ?, ?, ?) ON CONFLICT (email) DO NOTHING",
                UUID.randomUUID(),
                handle + "@example.com",
                "Creator " + handle,
                handle);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, handle + "@example.com");
    }

    /**
     * A campaign written straight into the table, in whatever state a test needs.
     *
     * <p><strong>Why this exists beside {@link #launch}.</strong> Discovery is read
     * only and has to be tested against campaigns in all sixteen states of §6.1,
     * including the seven it must never return. Seven of those are unreachable through
     * the API without a moderator, an approval, and a suspension — and
     * {@code SUSPENDED} in particular is reachable only from {@code LIVE} through trust
     * and safety. Driving each fixture through that path would make a suite about
     * filtering depend on the whole moderation configuration.
     *
     * <p>Every check constraint on {@code projects} still applies, which is what keeps
     * these rows ones the application could have produced —
     * {@code projects_public_states_are_fully_specified} in particular, which is why
     * {@link Seed#state} fills in a goal, a duration, a launch, and a deadline for the
     * states that require all four.
     *
     * <p>As with {@link #launch}, no audit row is written. A test that reads
     * {@code project_state_transitions} must not use this.
     */
    public static Seed seed(DataSource dataSource, UUID creatorId, String slug) {
        return new Seed(dataSource, creatorId, slug);
    }

    /** @see #seed */
    public static final class Seed {

        /** Everything from LIVE onwards; §6.1 and projects_public_states_are_fully_specified. */
        private static final List<String> LAUNCHED_STATES = List.of(
                "LIVE", "SUSPENDED", "CANCELED", "SUCCESSFUL", "UNSUCCESSFUL",
                "COLLECTING", "LATE_PLEDGE", "FULFILLING", "COMPLETED");

        private final JdbcTemplate jdbc;
        private final UUID creatorId;
        private final String slug;
        private final UUID id = UUID.randomUUID();

        private String title;
        private String state = "DRAFT";
        private String categorySlug;
        private String subcategorySlug;
        private BigDecimal goal;
        private BigDecimal pledged = BigDecimal.ZERO;
        private int backers;
        private Instant launchedAt;
        private Instant deadline;
        private boolean withCover = true;
        private final List<String> tags = new ArrayList<>();

        private Seed(DataSource dataSource, UUID creatorId, String slug) {
            this.jdbc = new JdbcTemplate(dataSource);
            this.creatorId = creatorId;
            this.slug = slug;
            this.title = slug;
        }

        public Seed title(String value) {
            this.title = value;
            return this;
        }

        /** One of the sixteen of §6.1. */
        public Seed state(String value) {
            this.state = value;
            return this;
        }

        public Seed category(String value) {
            this.categorySlug = value;
            return this;
        }

        /** Also sets the category if none was given, because the pair has to agree. */
        public Seed subcategory(String categorySlug, String value) {
            this.categorySlug = categorySlug;
            this.subcategorySlug = value;
            return this;
        }

        /** Money as a string, never a double — the fixture is held to the same rule as the code. */
        public Seed goal(String amount) {
            this.goal = new BigDecimal(amount);
            return this;
        }

        public Seed pledged(String amount) {
            this.pledged = new BigDecimal(amount);
            return this;
        }

        public Seed backers(int value) {
            this.backers = value;
            return this;
        }

        public Seed launchedAt(Instant value) {
            this.launchedAt = value;
            return this;
        }

        public Seed deadline(Instant value) {
            this.deadline = value;
            return this;
        }

        /** No cover image, for the card that has to render without one. */
        public Seed withoutCover() {
            this.withCover = false;
            return this;
        }

        /** Tag slugs; the rows in {@code tags} are created on demand and shared. */
        public Seed tags(String... slugs) {
            this.tags.addAll(List.of(slugs));
            return this;
        }

        public UUID insert() {
            Instant now = Instant.now();
            if (LAUNCHED_STATES.contains(state)) {
                // The constraint requires all four together, so defaulting them
                // individually would produce a row the application could not have
                // written.
                if (goal == null) {
                    goal = new BigDecimal("5000.00");
                }
                if (launchedAt == null) {
                    launchedAt = now.minus(Duration.ofDays(1));
                }
                if (deadline == null) {
                    deadline = launchedAt.plus(Duration.ofDays(DEFAULT_DURATION_DAYS));
                }
            }
            jdbc.update(
                    """
                    INSERT INTO projects (
                        id, creator_id, slug, title, blurb, category_id, subcategory_id, state,
                        goal_amount, pledged_amount, backers_count, duration_days,
                        launched_at, deadline,
                        cover_image_url, cover_image_width, cover_image_height)
                    VALUES (
                        ?, ?, ?, ?, ?,
                        (SELECT id FROM categories WHERE slug = ?),
                        (SELECT s.id FROM subcategories s JOIN categories c ON c.id = s.parent_id
                          WHERE c.slug = ? AND s.slug = ?),
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    creatorId,
                    slug,
                    title,
                    "A summary that fits inside a hundred and thirty-five characters.",
                    categorySlug,
                    categorySlug,
                    subcategorySlug,
                    state,
                    goal,
                    pledged,
                    backers,
                    launchedAt == null ? null : DEFAULT_DURATION_DAYS,
                    launchedAt == null ? null : OffsetDateTime.ofInstant(launchedAt, ZoneOffset.UTC),
                    deadline == null ? null : OffsetDateTime.ofInstant(deadline, ZoneOffset.UTC),
                    withCover ? "https://cdn.example.com/" + slug + ".jpg" : null,
                    withCover ? Integer.valueOf(1600) : null,
                    withCover ? Integer.valueOf(900) : null);

            for (String tag : tags) {
                // ON CONFLICT because tags are a free vocabulary shared between
                // campaigns; a fixture asking for a tag another fixture already made
                // must attach to the same row, or the facet counts would split.
                jdbc.update(
                        "INSERT INTO tags (id, slug, label) VALUES (?, ?, ?) ON CONFLICT (slug) DO NOTHING",
                        UUID.randomUUID(),
                        tag,
                        tag);
                jdbc.update(
                        "INSERT INTO project_tags (project_id, tag_id)"
                                + " SELECT ?, id FROM tags WHERE slug = ? ON CONFLICT DO NOTHING",
                        id,
                        tag);
            }
            return id;
        }
    }

    /** A valid story document holding one paragraph of the requested length. */
    public static Map<String, Object> story(int characters) {
        return Map.of(
                "version",
                1,
                "blocks",
                List.of(Map.of(
                        "type",
                        "paragraph",
                        "spans",
                        List.of(Map.of("text", "b".repeat(characters), "marks", List.of())))));
    }
}

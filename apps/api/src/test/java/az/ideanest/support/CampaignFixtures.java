package az.ideanest.support;

import az.ideanest.project.infrastructure.CategoryRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one description of "a campaign §5.3 is satisfied with".
 *
 * <p>Here rather than copied into each suite because three test classes create
 * campaigns in order to submit them, and a fixture that is complete in two of them
 * and out of date in the third produces a failure about the fixture wearing the
 * name of the behaviour being tested. When §5.3 gains a requirement, this is the
 * one place the suites have to be taught about it.
 *
 * <p><strong>Deliberately only what blocks.</strong> No subcategory, no scheduled
 * launch, no reward tiers, and a story with no pictures in it — so a campaign
 * built from this is submittable and is not finished, which is exactly the
 * distinction the checklist exists to draw.
 */
public final class CampaignFixtures {

    private CampaignFixtures() {
    }

    /**
     * The one patch that takes a fresh draft to submittable.
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
        body.put("durationDays", 30);
        // §5.3: at least 1024×576. The recorded dimensions are what the checklist
        // measures, because nothing on the server has ever seen the file.
        body.put("coverImage", Map.of("url", "https://cdn.example.com/cover.jpg", "width", 1600, "height", 900));
        body.put("story", story(600));
        // §5.3 requires two hundred characters, emphatically. A campaign that says
        // nothing about what could go wrong is the one that produces the refunds.
        body.put("risks", "The main risk is manufacturing capacity. ".repeat(6));
        return body;
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

package az.ideanest.pledgemanager.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One question on a survey — §4.8's PM-01, PM-02 and PM-03.
 *
 * <h2>PM-02 is one nullable column</h2>
 *
 * <p>{@code rewardTierId} null asks everybody; a tier asks only the backers who chose
 * it. That is the whole of "questions conditional on reward tier", and the alternative
 * — a rule language, or a list of tiers per question — was rejected on what creators
 * actually ask for: "what size, for the people who bought a t-shirt". A question that
 * applies to two tiers is two questions, which is more typing and is legible on the
 * response export in a way an OR-list is not.
 *
 * <p>A backer with no reward (§4.5's PL-02) is asked only the unconditional questions,
 * which follows from the same column without a second rule.
 *
 * <h2>The options are an array, not a table</h2>
 *
 * <p>Nothing joins to an individual option and every read of a question wants all of
 * them, so a fifth table would buy a foreign key that {@code SurveyAnswer}
 * deliberately does not want — see that class on why an answer stores the words and
 * not a reference.
 */
@Entity
@Table(name = "survey_questions")
public class SurveyQuestion {

    private static final int MAX_PROMPT = 300;

    private static final int MAX_HELP = 300;

    private static final int MAX_CHOICE = 120;

    /** V35 holds the same bounds. Two is the smallest number of options that is a choice. */
    private static final int MIN_CHOICES = 2;

    private static final int MAX_CHOICES = 50;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "survey_id", nullable = false, updatable = false)
    private UUID surveyId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "prompt", nullable = false)
    private String prompt;

    @Column(name = "help_text")
    private String helpText;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false)
    private QuestionType type;

    @Column(name = "required", nullable = false)
    private boolean required;

    /**
     * {@code text[]}, mapped as an array rather than as a serialised blob so that
     * PostgreSQL can hold V35's per-element check. A {@code jsonb} column would make
     * the same data unreadable to the constraint and to anybody running a query.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "choices")
    private String[] choices;

    @Column(name = "reward_tier_id")
    private UUID rewardTierId;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected SurveyQuestion() {
        // JPA.
    }

    /**
     * @throws SurveyContentInvalidException when the prompt is blank or too long, when
     *     a type that needs options has none or too many, when a type that has no
     *     options was given some, or when an ADDRESS question was marked required —
     *     each of which V35 also refuses, and each of which is something the builder
     *     can highlight
     */
    public static SurveyQuestion of(
            UUID id,
            UUID surveyId,
            UUID projectId,
            int position,
            String prompt,
            String helpText,
            QuestionType type,
            boolean required,
            List<String> choices,
            UUID rewardTierId) {

        SurveyQuestion question = new SurveyQuestion();
        question.id = Objects.requireNonNull(id, "A question has an identifier");
        question.surveyId = Objects.requireNonNull(surveyId, "A question belongs to a survey");
        question.projectId = Objects.requireNonNull(projectId, "A question belongs to a campaign");
        question.type = Objects.requireNonNull(type, "A question has an answer shape");
        question.position = position;
        question.prompt = required(prompt, "prompt", MAX_PROMPT);
        question.helpText = optional(helpText, "helpText", MAX_HELP);
        question.rewardTierId = rewardTierId;

        if (type == QuestionType.ADDRESS && required) {
            // Whether an address is needed is decided by the reward tier's shipping
            // type — a fact about what was promised — rather than by how the survey was
            // drawn. A required flag here would be a second answer to that, free to
            // disagree with the tier.
            throw new SurveyContentInvalidException(
                    "required", "An address question is answered on the pledge, so it cannot be marked required.");
        }
        question.required = required;
        question.choices = normalisedChoices(type, choices);
        return question;
    }

    /**
     * The options, de-duplicated and in the order the creator gave them.
     *
     * <p>Duplicates are refused rather than folded away: two identical options in a
     * radio group are two buttons that do the same thing, and the responses would be
     * split between them with no way to tell afterwards which one somebody meant.
     */
    private static String[] normalisedChoices(QuestionType type, List<String> choices) {
        List<String> given = choices == null ? List.of() : choices;
        if (!type.hasChoices()) {
            if (!given.isEmpty()) {
                throw new SurveyContentInvalidException(
                        "choices", "A " + type.name().toLowerCase(Locale.ROOT) + " question has no options.");
            }
            return null;
        }

        Set<String> unique = new LinkedHashSet<>();
        for (String choice : given) {
            String trimmed = choice == null ? "" : choice.trim();
            if (trimmed.isEmpty() || trimmed.length() > MAX_CHOICE) {
                throw new SurveyContentInvalidException(
                        "choices", "Each option is between 1 and " + MAX_CHOICE + " characters.");
            }
            if (!unique.add(trimmed)) {
                throw new SurveyContentInvalidException("choices", "\"" + trimmed + "\" is listed twice.");
            }
        }
        if (unique.size() < MIN_CHOICES || unique.size() > MAX_CHOICES) {
            throw new SurveyContentInvalidException(
                    "choices", "A choice question has between " + MIN_CHOICES + " and " + MAX_CHOICES + " options.");
        }
        return unique.toArray(String[]::new);
    }

    /** Whether this question is asked of a backer who chose this tier — PM-02. */
    public boolean appliesTo(UUID backersRewardTierId) {
        return rewardTierId == null || rewardTierId.equals(backersRewardTierId);
    }

    /** Whether a value is one of this question's options. Always true for a question with none. */
    public boolean offers(String value) {
        return choices == null || Arrays.asList(choices).contains(value);
    }

    public UUID getId() {
        return id;
    }

    public UUID getSurveyId() {
        return surveyId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public int getPosition() {
        return position;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getHelpText() {
        return helpText;
    }

    public QuestionType getType() {
        return type;
    }

    public boolean isRequired() {
        return required;
    }

    /** Cloned, so a caller cannot reorder the entity's options through the reference. */
    public List<String> getChoices() {
        return choices == null ? List.of() : List.of(choices);
    }

    public UUID getRewardTierId() {
        return rewardTierId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Moves the question within its survey.
     *
     * <p>The only mutation a question has. Everything else about it is replaced by
     * rewriting the list, which is what the builder does — and after the send nothing
     * is mutable at all, which {@code SurveyService} enforces.
     */
    public void moveTo(int position) {
        this.position = position;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof SurveyQuestion question && Objects.equals(id, question.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "SurveyQuestion[" + id + ", " + type + ", position=" + position + "]";
    }

    private static String required(String value, String field, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new SurveyContentInvalidException(field, "This is required.");
        }
        if (trimmed.length() > max) {
            throw new SurveyContentInvalidException(field, "This is longer than " + max + " characters.");
        }
        return trimmed;
    }

    private static String optional(String value, String field, int max) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > max) {
            throw new SurveyContentInvalidException(field, "This is longer than " + max + " characters.");
        }
        return trimmed;
    }
}

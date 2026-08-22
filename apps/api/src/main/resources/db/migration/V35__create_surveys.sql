-- §4.8's PM-01 to PM-06 (#73, #74): the questions a creator has to ask before
-- they can manufacture and ship, and the answers a backer gives back.
--
-- ---------------------------------------------------------------------------
-- FOUR TABLES, AND WHY THE QUESTIONS ARE ROWS
-- ---------------------------------------------------------------------------
--
-- The cheap version of this feature is one `jsonb` document per survey holding
-- its questions and one per response holding its answers. It was rejected, and
-- the reason is the read that matters: "what size did each backer choose", over
-- four thousand pledges, exported to a factory. With rows that is a join and an
-- index; with documents it is a sequential scan that unpacks every response and
-- then trusts that every one of them spells the question the same way.
--
-- Rows also give the database something to refuse. A response to a question
-- belonging to another campaign's survey, an answer to a question that was
-- deleted, a survey sent with no questions in it -- each is a foreign key or a
-- check here, and each is a support ticket that never gets opened.
--
-- ---------------------------------------------------------------------------
-- `sent_at` IS THE WHOLE OF "DRAFT" AND "SENT"
-- ---------------------------------------------------------------------------
--
-- V22 made the same decision for `project_updates.published_at` and it is made
-- again here for the same reason: a state column beside a timestamp is two
-- facts that can disagree, and the one that gets updated by a support script is
-- never the one the reads filter on. A survey with `sent_at IS NULL` is a
-- draft; it is editable, it is invisible to backers, and it has no responses
-- because nothing could have answered it.
--
-- Once `sent_at` is set the questions freeze. That is enforced in the service
-- rather than here -- PostgreSQL cannot express "these child rows are immutable
-- once their parent's column is non-null" without a trigger, and a trigger that
-- silently rejects an edit is harder to debug than a refusal with a sentence in
-- it. What is here is the consequence that matters: `survey_responses` cascades
-- from `survey_questions` on nothing, so a question cannot be deleted while an
-- answer to it exists.
--
-- ---------------------------------------------------------------------------
-- THE CUT-OFF IS A COLUMN AND NOT A JOB
-- ---------------------------------------------------------------------------
--
-- PM-06 lets a backer edit until a stated cut-off. `respond_by` is that
-- instant, and nothing sweeps it: the service compares it to the clock on every
-- write. A job that closed surveys would be a job that can be late, and being
-- late here means accepting an answer after the creator placed the order.
--
-- NULL means no cut-off, which is the honest default for a creator who has not
-- decided one. It does not mean "closed".
--
-- ---------------------------------------------------------------------------
-- ADDRESS IS A QUESTION TYPE THAT STORES NO ANSWER
-- ---------------------------------------------------------------------------
--
-- PM-03 lists `address` among the answer types and #75 gives the platform a
-- `shipping_addresses` table that is encrypted at rest, validated, and lockable.
-- An ADDRESS question therefore records that the survey *asks* for a postal
-- address; the answer is the pledge's row in that table and is not copied here.
--
-- Copying it would put an unencrypted postal address in a table with none of
-- that machinery, would give the platform two addresses per backer that can
-- disagree, and would make §17.4's erasure a search across two schemas. The
-- cost is that "has this backer finished the survey" has to consult both, which
-- `SurveyService` does in one place.
--
-- Reverse:
--   DROP TABLE IF EXISTS survey_answers;
--   DROP TABLE IF EXISTS survey_responses;
--   DROP TABLE IF EXISTS survey_nudges;
--   DROP TABLE IF EXISTS survey_questions;
--   DROP TABLE IF EXISTS surveys;
--   DROP FUNCTION IF EXISTS every_element_is_labelled(text[], integer);
--   -- Safe only before a survey has been sent. Afterwards these rows are what a
--   -- creator manufactures from -- the sizes, the colours, the language a
--   -- thousand people chose -- and there is no second copy anywhere. Dropping
--   -- them after a send is not a rollback, it is asking every backer again.
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- every_element_is_labelled
-- ---------------------------------------------------------------------------

-- "No element of this array is null, blank, or longer than n characters."
--
-- A function because a CHECK constraint may not contain a subquery, and
-- `unnest` in a scalar context is one. The alternatives were both worse: array
-- operators can say `array_position(a, NULL) IS NULL` and can test for the empty
-- string, but cannot express "not only spaces" or a length bound, so half the
-- rule would have lived in Java with nothing in the schema to say the other half
-- was ever checked; and a trigger would refuse the row from somewhere a reader
-- of the table definition cannot see.
--
-- IMMUTABLE and STRICT, which is what makes it usable in a check at all: it
-- reads nothing outside its arguments, so a restore re-validates the same rows
-- the original insert did. STRICT returns NULL for a NULL array, and a NULL
-- check passes -- which is correct here, because whether the column may be NULL
-- at all is the column's own constraint to state.
CREATE FUNCTION every_element_is_labelled(elements text[], max_length integer)
    RETURNS boolean
    LANGUAGE sql
    IMMUTABLE
    STRICT
    PARALLEL SAFE
AS $$
    SELECT bool_and(element IS NOT NULL AND length(btrim(element)) BETWEEN 1 AND max_length)
      FROM unnest(elements) AS element
$$;

COMMENT ON FUNCTION every_element_is_labelled(text[], integer) IS
    'True when no element is null, blank or over max_length. Exists because a CHECK constraint may not contain a subquery.';

-- ---------------------------------------------------------------------------
-- surveys
-- ---------------------------------------------------------------------------

CREATE TABLE surveys (
    id          uuid        PRIMARY KEY,
    -- Cascades with the campaign, like every other child of `projects`: §7.3
    -- only hard deletes a campaign that never launched, and a campaign that
    -- never launched has no backers to have surveyed.
    project_id  uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    title       text        NOT NULL,
    -- The covering note the backer reads above the questions. Optional: a
    -- three-question survey about a t-shirt size does not need a paragraph, and
    -- a required field a creator has nothing to put in is a field they fill with
    -- "n/a".
    message     text,
    -- PM-06's cut-off. NULL means the creator has not set one, which is not the
    -- same as closed -- see the header.
    respond_by  timestamptz,
    -- NULL is a draft. See the header for why there is no state column beside
    -- this one.
    sent_at     timestamptz,
    -- How many backers it went to, frozen at send, for `campaign_messages`'
    -- reason: the campaign's backers move, so a count derived later reports a
    -- different send from the one that happened.
    sent_to     integer,
    -- No ON DELETE: §17.4 anonymises in place, and who built a survey is a fact
    -- the record needs even when that account is no longer anybody.
    created_by  uuid        NOT NULL REFERENCES users (id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT surveys_title_length CHECK (length(btrim(title)) BETWEEN 1 AND 150),
    CONSTRAINT surveys_message_length CHECK (
        message IS NULL OR length(btrim(message)) BETWEEN 1 AND 2000
    ),
    -- Whole or absent together, in the voice of `campaign_messages_segment_is_whole`:
    -- a recipient count on a draft claims a send that did not happen, and a send
    -- with no count is a send nobody can report on.
    CONSTRAINT surveys_send_is_whole CHECK ((sent_at IS NULL) = (sent_to IS NULL)),
    CONSTRAINT surveys_sent_to_is_not_negative CHECK (sent_to IS NULL OR sent_to >= 0),
    -- A survey whose cut-off is before it was sent is closed on arrival: every
    -- backer would receive an invitation to a form that refuses them. Checked
    -- here as well as in the service because a cut-off can be edited afterwards,
    -- and the edit is the likelier mistake.
    CONSTRAINT surveys_cut_off_follows_the_send CHECK (
        sent_at IS NULL OR respond_by IS NULL OR respond_by > sent_at
    ),

    -- Referenced by `survey_questions`, so that a question's two foreign keys
    -- are composite and a question cannot be attached to another campaign's
    -- survey. The same shape `reward_tier_items` uses, and for the same reason.
    CONSTRAINT surveys_id_project_key UNIQUE (id, project_id)
);

-- "The surveys on this campaign", newest first, which is the creator's list.
CREATE INDEX surveys_project_idx ON surveys (project_id, created_at DESC, id DESC);

-- V10's convention: the database stamps `updated_at`, so an application that
-- forgot to set it cannot leave a row claiming it has not changed since it was
-- created.
CREATE TRIGGER surveys_set_updated_at
    BEFORE UPDATE ON surveys
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE surveys IS
    'PM-01: a set of questions a creator asks their backers after funding closes. sent_at NULL is a draft.';
COMMENT ON COLUMN surveys.respond_by IS
    'PM-06 cut-off. NULL means none has been set, which is not the same as closed. Nothing sweeps it; every write compares it to the clock.';

-- ---------------------------------------------------------------------------
-- survey_questions
-- ---------------------------------------------------------------------------

-- PM-01, PM-02 and PM-03 in one table: the prompt, what shape the answer takes,
-- and which backers are asked it at all.
CREATE TABLE survey_questions (
    id             uuid        PRIMARY KEY,
    survey_id      uuid        NOT NULL,
    -- Carried so that both foreign keys below are composite. Without it a
    -- question on one campaign's survey could be made conditional on another
    -- campaign's reward tier, and no single-column reference can refuse that.
    project_id     uuid        NOT NULL,
    -- Dense and zero-based, maintained by the service, which rewrites the whole
    -- list on every edit. Not a float or a gap-leaving integer: the list is
    -- short, it is always sent whole, and "insert between 3 and 4" is a
    -- rewrite of four rows rather than a numbering scheme to reason about.
    position       integer     NOT NULL,
    prompt         text        NOT NULL,
    -- The smaller line under the prompt: "we print this on the parcel", "metric
    -- sizes". Optional for the same reason `surveys.message` is.
    help_text      text,
    type           text        NOT NULL,
    required       boolean     NOT NULL DEFAULT false,
    -- The options, for the two types that have them. A `text[]` rather than a
    -- child table: the options of a question are read only ever as a whole and
    -- always with the question, nothing joins to an individual option, and a
    -- fifth table would buy a foreign key from `survey_answers` that the answer
    -- deliberately does not want -- see that table's comment on why an answer
    -- stores the text and not a reference.
    choices        text[],
    -- PM-02. NULL means every backer is asked; a tier means only the backers who
    -- chose it. No ON DELETE clause of its own -- the composite reference below
    -- carries one -- and it is RESTRICT rather than CASCADE, because deleting a
    -- tier that a live survey is conditional on would silently widen the
    -- question to every backer.
    reward_tier_id uuid,
    created_at     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT survey_questions_survey_fkey
        FOREIGN KEY (survey_id, project_id) REFERENCES surveys (id, project_id) ON DELETE CASCADE,
    CONSTRAINT survey_questions_reward_tier_fkey
        FOREIGN KEY (reward_tier_id, project_id) REFERENCES reward_tiers (id, project_id)
        ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,

    CONSTRAINT survey_questions_position_is_not_negative CHECK (position >= 0),
    CONSTRAINT survey_questions_position_key UNIQUE (survey_id, position) DEFERRABLE INITIALLY DEFERRED,
    -- Referenced by `survey_answers`, so that an answer's two foreign keys are
    -- composite and an answer cannot name a question from a different survey.
    CONSTRAINT survey_questions_id_survey_key UNIQUE (id, survey_id),
    CONSTRAINT survey_questions_prompt_length CHECK (length(btrim(prompt)) BETWEEN 1 AND 300),
    CONSTRAINT survey_questions_help_text_length CHECK (
        help_text IS NULL OR length(btrim(help_text)) BETWEEN 1 AND 300
    ),
    -- PM-03's five, and no sixth without a migration. An unconstrained type
    -- column is how a client typo becomes a question nothing knows how to
    -- render and nothing knows how to validate an answer to.
    CONSTRAINT survey_questions_type CHECK (
        type IN (
            'TEXT',          -- one line or a paragraph; the answer is what they typed
            'CHOICE',        -- exactly one of `choices`
            'MULTI_CHOICE',  -- one or more of `choices`
            'DATE',          -- an ISO 8601 calendar date
            'ADDRESS'        -- answered by the pledge's shipping address; see the header
        )
    ),
    -- Options exactly when the type has options. Both halves are real mistakes:
    -- a CHOICE with no options is a question with no answer, and a TEXT with
    -- options is a creator who picked the wrong type and will not find out until
    -- the responses come back as free prose.
    CONSTRAINT survey_questions_choices_match_the_type CHECK (
        CASE
            WHEN type IN ('CHOICE', 'MULTI_CHOICE')
                THEN choices IS NOT NULL AND cardinality(choices) BETWEEN 2 AND 50
            ELSE choices IS NULL
        END
    ),
    -- An empty or blank option renders as a radio button with no label beside
    -- it, which a backer can select and nobody can interpret afterwards. The
    -- empty-array case is covered by the cardinality bound above, which is why
    -- this may leave it to `every_element_is_labelled` returning NULL.
    CONSTRAINT survey_questions_choices_are_labelled CHECK (
        choices IS NULL OR every_element_is_labelled(choices, 120)
    ),
    -- An ADDRESS question stores no answer, so "required" would be a flag
    -- nothing reads: whether an address is needed is decided by the reward tier's
    -- shipping type, which is a fact about what was promised rather than about
    -- how the survey was drawn.
    CONSTRAINT survey_questions_address_is_not_required CHECK (
        type <> 'ADDRESS' OR NOT required
    )
);

-- Every read of a survey wants its questions in order, and there is no read that
-- wants one question alone.
CREATE INDEX survey_questions_survey_idx ON survey_questions (survey_id, position);

-- "Which surveys ask about this tier", for the refusal when a creator deletes a
-- tier a survey is conditional on.
CREATE INDEX survey_questions_reward_tier_idx
    ON survey_questions (reward_tier_id) WHERE reward_tier_id IS NOT NULL;

COMMENT ON TABLE survey_questions IS
    'PM-01 to PM-03: one question. reward_tier_id is PM-02 -- NULL asks everybody, a tier asks only the backers who chose it.';
COMMENT ON COLUMN survey_questions.type IS
    'One of TEXT, CHOICE, MULTI_CHOICE, DATE, ADDRESS. ADDRESS stores no answer: it points at the pledge''s shipping_addresses row.';

-- ---------------------------------------------------------------------------
-- survey_responses
-- ---------------------------------------------------------------------------

-- PM-05: one backer's answers to one survey. The row exists as soon as they
-- save anything, and `submitted_at` moves every time they change it.
CREATE TABLE survey_responses (
    id           uuid        PRIMARY KEY,
    survey_id    uuid        NOT NULL REFERENCES surveys (id) ON DELETE CASCADE,
    -- The pledge and not the account, and that is the identity that matters:
    -- what a backer is asked depends on the tier they chose (PM-02), and what
    -- the creator ships is per pledge. `pledges` already carries the account.
    --
    -- Cascades, because a pledge that no longer exists is a pledge with nothing
    -- to fulfil, and an orphaned response would be an answer nobody can attach
    -- to an order.
    pledge_id    uuid        NOT NULL REFERENCES pledges (id) ON DELETE CASCADE,
    -- Denormalised from the pledge so that "my surveys" is one index rather than
    -- a join through `pledges` on every read of GET /v1/me/surveys. No ON
    -- DELETE, for `surveys.created_by`'s reason.
    backer_id    uuid        NOT NULL REFERENCES users (id),
    submitted_at timestamptz NOT NULL DEFAULT now(),
    created_at   timestamptz NOT NULL DEFAULT now(),

    -- One response per pledge per survey. PM-06 is an edit of this row and never
    -- a second one: two rows would make "what did they answer" a question with
    -- an ordering in it, and the ordering would decide what gets manufactured.
    CONSTRAINT survey_responses_one_per_pledge UNIQUE (survey_id, pledge_id),
    -- Referenced by `survey_answers`, so that an answer's two foreign keys are
    -- composite and an answer cannot be filed under another survey's response.
    CONSTRAINT survey_responses_id_survey_key UNIQUE (id, survey_id)
);

-- The creator's read: "who has answered", newest first.
CREATE INDEX survey_responses_survey_idx ON survey_responses (survey_id, submitted_at DESC, id DESC);

-- The backer's read: "what am I being asked", across every campaign they backed.
CREATE INDEX survey_responses_backer_idx ON survey_responses (backer_id, survey_id);

COMMENT ON TABLE survey_responses IS
    'PM-05: one pledge''s answers to one survey. Keyed by pledge rather than by account because what is asked depends on the tier.';

-- ---------------------------------------------------------------------------
-- survey_answers
-- ---------------------------------------------------------------------------

-- One answer to one question. A row rather than a key in a document, for the
-- reason the header gives: the export is per question across every backer.
CREATE TABLE survey_answers (
    response_id uuid        NOT NULL,
    question_id uuid        NOT NULL,
    survey_id   uuid        NOT NULL,
    -- Always an array, whatever the type. TEXT, CHOICE and DATE hold exactly one
    -- element and MULTI_CHOICE holds one or more, which makes every reader one
    -- shape rather than a `value` column and a `values` column with a check
    -- saying exactly one of them is set.
    --
    -- **The cardinality per type is not a constraint here**, and that is a
    -- limitation stated rather than hidden: a check cannot join to
    -- `survey_questions` to find out which type this answers. `SurveyService`
    -- refuses a two-element answer to a CHOICE with a sentence; what this table
    -- guarantees is that an answer is non-empty and that none of its elements is
    -- blank.
    --
    -- **The text and not a reference into `choices`.** An index into the options
    -- array would break the moment a creator reordered them, and a foreign key
    -- would need the fifth table `survey_questions.choices` deliberately avoids.
    -- What a backer chose is the words they saw.
    value       text[]      NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT survey_answers_pkey PRIMARY KEY (response_id, question_id),
    CONSTRAINT survey_answers_response_fkey
        FOREIGN KEY (response_id, survey_id) REFERENCES survey_responses (id, survey_id) ON DELETE CASCADE,
    -- **No ON DELETE.** Deleting a question somebody has answered is refused, and
    -- that is the point: the alternative is a creator tidying a sent survey and
    -- silently discarding four hundred answers to the question they removed.
    -- Editing a sent survey is refused by the service for the same reason; this
    -- is what refuses it under a support script.
    CONSTRAINT survey_answers_question_fkey
        FOREIGN KEY (question_id, survey_id) REFERENCES survey_questions (id, survey_id),

    CONSTRAINT survey_answers_is_not_empty CHECK (cardinality(value) BETWEEN 1 AND 50),
    CONSTRAINT survey_answers_elements_are_present CHECK (every_element_is_labelled(value, 2000))
);

-- The export: one question, every backer's answer to it.
CREATE INDEX survey_answers_question_idx ON survey_answers (question_id);

CREATE TRIGGER survey_answers_set_updated_at
    BEFORE UPDATE ON survey_answers
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE survey_answers IS
    'One answer. Always an array so every reader has one shape; the per-type cardinality is the service''s, since a check cannot join to the question.';

-- ---------------------------------------------------------------------------
-- survey_nudges
-- ---------------------------------------------------------------------------

-- PM-24 and §8.4's `survey-nudge`: who has already been chased, so that a daily
-- sweep does not chase them daily.
--
-- **The row is the claim**, exactly as `deadline_notices` is: it is inserted in
-- the same transaction as the outbox event that reminds somebody, so a crash
-- leaves them either unchased and unclaimed or chased and claimed. Without it
-- the sweep's question -- "who has not answered" -- stays true for as long as
-- they do not answer, and every one of those days is another email.
CREATE TABLE survey_nudges (
    survey_id uuid        NOT NULL REFERENCES surveys (id) ON DELETE CASCADE,
    pledge_id uuid        NOT NULL REFERENCES pledges (id) ON DELETE CASCADE,
    -- Which reminder this was. One is a nudge and three is a campaign of its
    -- own, so the sweep is bounded by a configured maximum and this is what it
    -- counts.
    attempt   integer     NOT NULL,
    sent_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT survey_nudges_pkey PRIMARY KEY (survey_id, pledge_id, attempt),
    CONSTRAINT survey_nudges_attempt_is_positive CHECK (attempt >= 1)
);

COMMENT ON TABLE survey_nudges IS
    'PM-24: a reminder that was sent to a non-responder. The row is the claim -- written with the outbox event, so a retry cannot double-chase.';

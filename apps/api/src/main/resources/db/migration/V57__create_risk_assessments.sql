-- §17.2's "Fraud signals: velocity, geography mismatch, new-account risk", and
-- the half of §4.11's AD-02 that was named as not built. Issue #108.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS risk_assessments;
--
--   Nothing references it and nothing is decided by it -- see the header below
--   on why it advises rather than blocks -- so dropping it loses a record of
--   what was noticed and changes no money and no state. What is lost is the
--   ability to answer "was this flagged at the time", which is the question
--   asked after a chargeback rather than before one.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHY THE ASSESSMENT IS A ROW AND NOT A COLUMN ON `pledges`
-- ---------------------------------------------------------------------------
--
-- A score is a judgement made at a moment, from facts that move. The same
-- pledge assessed tomorrow scores differently because the account is a day
-- older and because five more pledges have been made from that address. A
-- column would be overwritten by the second assessment and the first would be
-- gone -- which is exactly the record somebody wants when they are asking why a
-- charge that was flagged was allowed through.
--
-- So this is append-only in practice: one row per assessment, and a re-run
-- writes another. The unique index is on (subject_type, subject_id,
-- assessed_at) rather than on the subject alone, for the same reason.
--
-- ---------------------------------------------------------------------------
-- WHY `findings` IS jsonb AND THE SCORE IS NOT
-- ---------------------------------------------------------------------------
--
-- The score and the decision are queried -- the trust and safety queue orders
-- by one and filters on the other -- so they are columns with indexes. The
-- findings are read one row at a time by a person looking at a specific pledge,
-- and their shape changes every time a signal is added or reweighted.
--
-- A table of findings would be the alternative, and it would be the right shape
-- if anything aggregated over them. Nothing does: "how many pledges fired the
-- velocity signal this week" is a question about the score, and answering it
-- from a jsonb array with a GIN index is cheaper than the join.
--
-- ---------------------------------------------------------------------------
-- WHY A SIGNAL CAN BE "UNAVAILABLE" AND WHY THAT IS NOT ZERO
-- ---------------------------------------------------------------------------
--
-- §17.2 names geography mismatch. Answering it needs an IP-to-country source,
-- and this repository has none -- no vendor is chosen and no database ships
-- with the service. A signal that quietly scored zero would be indistinguishable
-- from one that looked and found nothing, and the difference matters: the first
-- means "we cannot check this", the second means "we checked and it is fine".
--
-- So `findings` records an outcome per signal -- FIRED, CLEAR or UNAVAILABLE --
-- and `signals_unavailable` counts the third. A queue that shows a low score
-- with two unavailable signals is telling the truth about what it knows.

CREATE TABLE risk_assessments (
    id uuid PRIMARY KEY,

    -- What was assessed. Only 'pledge' today; the column exists because §17.2's
    -- other candidates -- a registration, a payout destination change -- are the
    -- same shape, and adding the column later is a migration over a table that
    -- will by then have a row per pledge on the platform.
    subject_type text NOT NULL
        CONSTRAINT risk_assessments_subject_known CHECK (subject_type IN ('pledge')),

    subject_id uuid NOT NULL,

    -- Denormalised so the queue can be filtered by campaign without a join to a
    -- table in another module. Nullable because a future subject type may not
    -- belong to a campaign.
    project_id uuid,

    -- Whose action was assessed. CASCADE, and the argument is §17.4 rather than
    -- convenience: an assessment is a statement about one person's behaviour and
    -- has no value once that person is gone. Keeping it would leave the platform
    -- holding "this account looked like a card tester" about an account that no
    -- longer exists, with no retention rule and nobody it could be shown to.
    --
    -- This is where `payouts.creator_id` differs and correctly so: a payout is a
    -- record that money moved, which outlives the account by law. A risk score is
    -- a triage aid.
    subject_user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    -- 0 to 100. An integer rather than a numeric: it is an ordering, not an
    -- amount, and a fractional risk score invites arithmetic nobody should do.
    score smallint NOT NULL
        CONSTRAINT risk_assessments_score_in_range CHECK (score BETWEEN 0 AND 100),

    -- ALLOW: nothing worth a person's time.
    -- REVIEW: a person should look before the money is collected.
    -- BLOCK: reserved, and nothing produces it yet -- see the class comment on
    --        RiskAssessments for why a fraud score does not stop a pledge today.
    decision text NOT NULL
        CONSTRAINT risk_assessments_decision_known CHECK (decision IN ('ALLOW', 'REVIEW', 'BLOCK')),

    -- One object per signal: {"signal": "...", "outcome": "...", "weight": n,
    -- "detail": "..."}. See the header.
    findings jsonb NOT NULL DEFAULT '[]'::jsonb
        CONSTRAINT risk_assessments_findings_is_an_array CHECK (jsonb_typeof(findings) = 'array'),

    -- How many signals could not be evaluated. See the header: this is what
    -- stops a low score being read as a clean bill of health.
    signals_unavailable smallint NOT NULL DEFAULT 0
        CONSTRAINT risk_assessments_unavailable_is_not_negative CHECK (signals_unavailable >= 0),

    -- Set when somebody has looked. Null is the queue.
    reviewed_at timestamptz,

    -- SET NULL rather than CASCADE: the reviewer leaving the company must not
    -- delete the record that the row was reviewed. What survives is "somebody
    -- looked at this, on this date", which is the half that matters to the next
    -- person to open the queue.
    reviewed_by uuid REFERENCES users (id) ON DELETE SET NULL,

    -- A reviewer implies a time. A time does NOT imply a reviewer, because the
    -- reviewer's account can be erased out from under it -- which is why this is
    -- an implication rather than the equality it would otherwise be.
    CONSTRAINT risk_assessments_review_has_a_time CHECK (
        reviewed_by IS NULL OR reviewed_at IS NOT NULL),

    assessed_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT risk_assessments_one_per_moment UNIQUE (subject_type, subject_id, assessed_at)
);

-- The trust and safety queue: what needs looking at, worst first. A partial
-- index, because the queue is only ever the unreviewed rows and the reviewed
-- ones outnumber them within a week.
CREATE INDEX risk_assessments_queue_idx
    ON risk_assessments (score DESC, assessed_at DESC)
    WHERE reviewed_at IS NULL AND decision <> 'ALLOW';

-- "What was noticed about this pledge", which is the question asked from the
-- payment log rather than from the queue.
CREATE INDEX risk_assessments_subject_idx ON risk_assessments (subject_type, subject_id, assessed_at DESC);

-- "Everything flagged on this campaign", for a creator reporting a pattern.
CREATE INDEX risk_assessments_project_idx ON risk_assessments (project_id, assessed_at DESC)
    WHERE project_id IS NOT NULL;

-- "How often did this signal fire" -- the one aggregate over `findings`, and the
-- reason it is jsonb rather than a table.
CREATE INDEX risk_assessments_findings_idx ON risk_assessments USING gin (findings jsonb_path_ops);

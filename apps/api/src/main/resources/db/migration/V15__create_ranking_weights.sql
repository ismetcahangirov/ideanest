-- The ranking weights of §11.2 (#44), as data rather than as constants.
--
-- §11.2 gives the composite and then gives the requirement that decides this
-- migration: "Weights are configuration and must be tunable without a
-- deployment, so ranking can be measured rather than argued about." A constant
-- in Java is neither tunable nor measurable -- changing it is a build, a
-- deployment, and a rollback, which is exactly the cost that makes people argue
-- about ranking instead of measuring it.
--
-- Nothing here removes or narrows anything: two new tables and nine seeded rows.
-- Both halves of a rolling deployment are therefore safe -- the previous release
-- does not know these tables exist, and nothing outside the discovery module
-- reads them.
--
-- Reverse:
--   DROP TABLE IF EXISTS ranking_weight_changes;
--   DROP TABLE IF EXISTS ranking_weights;
--   Reversing destroys every tuning decision the platform has taken and the
--   append-only record of who took it. It also makes `sort=relevance`
--   unanswerable, so it would have to be refused again through
--   DiscoveryCapability -- which is the previous build of the application. As
--   everywhere else in this directory, the way back from a bad release is that
--   build, and this block is here because it is genuinely the whole of the undo.
--
-- ---------------------------------------------------------------------------
-- WHAT §11.2 ASKS FOR, AND WHAT THERE IS DATA FOR
-- ---------------------------------------------------------------------------
--
-- The formula has eight terms. Five of them have no data source in this schema,
-- and saying so out loud is the whole reason `active` and `blocked_by` are
-- columns rather than comments:
--
--   w1 pledge velocity, 48h   NO DATA. `projects.pledged_amount` is a running
--                             total with no time series behind it, and the
--                             pledge ledger is epic #50. A 48-hour velocity
--                             cannot be computed from a total, and inventing one
--                             from `pledged / age` is the `popularity` sort --
--                             which already exists, is named honestly, and says
--                             in its own comment that it is not this.
--   w2 backer velocity, 48h   NO DATA, for the same reason: `backers_count` is
--                             also a running total.
--   w3 completion             COMPUTABLE. `pledged_amount` and `goal_amount`.
--   w4 editorial bonus        COMPUTABLE. #48's `project_editorial_badges`.
--   w5 view-to-pledge         NO DATA. Needs the analytics aggregation of #95;
--                             nothing records a view.
--   w6 personalisation        NO DATA. Needs D-07 and per-caller signals, and
--                             the feed is anonymous and publicly cached today.
--   w7 recency decay          COMPUTABLE. `launched_at`.
--   w8 spam signal            NO DATA. Needs the automated fraud signals of
--                             #108.
--
-- A ninth row, `text_match`, is not in §11.2's list and is deliberately here.
-- §4.3 and #43 settled it: "`best_match` becomes its text term rather than being
-- replaced by it". Without a text term, `sort=relevance` with `?q=` would rank a
-- campaign that matches the words the reader typed exactly as it ranks one that
-- does not, which is not a composite anybody would call relevance. docs/
-- architecture.md §11.2 is updated in the same pull request to say so.
--
-- A TERM WITH NO DATA IS VISIBLY ZERO, NOT SILENTLY ABSENT. Its row exists, its
-- weight is tunable, the diagnostic reports it, and `blocked_by` names what has
-- to land first. `ranking_weights_inert_terms_are_not_active` is the constraint
-- that stops the gap being papered over: a term nothing can compute cannot be
-- switched on, so the failure mode "somebody set the weight and nothing
-- happened" is a constraint violation rather than a quiet nothing.

-- ---------------------------------------------------------------------------
-- ranking_weights
-- ---------------------------------------------------------------------------

CREATE TABLE ranking_weights (
    -- The term, and the primary key. A vocabulary rather than a surrogate id:
    -- there is exactly one row per term for ever, the code names them, and an
    -- identifier would only give the table a second way to say the same thing
    -- plus a way to write two rows for one term.
    term        text        PRIMARY KEY,
    -- The multiplier. `numeric`, not `double precision`, and it is not
    -- negotiable: the composite score is the keyset cursor's sort key, and the
    -- keyset predicate compares it for exact equality. A weight that two
    -- evaluations of the same expression rounded differently would make a scroll
    -- skip a row. (It is not money -- see the note on `w3` in
    -- PostgresSearchService about where the money boundary is -- but it is
    -- arithmetic whose exactness something depends on, which is the same rule.)
    weight      numeric     NOT NULL,
    -- Whether the term is in the sum at all. Separate from a weight of zero on
    -- purpose: zero says "this term is measured and currently counts for
    -- nothing", and false says "this term is not computed". They look the same
    -- in a feed and mean completely different things to somebody tuning, and the
    -- diagnostic reports them differently.
    active      boolean     NOT NULL DEFAULT false,
    -- What has to exist before this term can be computed at all, named so that
    -- somebody reading the table learns where the gap is rather than guessing.
    -- NULL means the data exists today.
    blocked_by  text,
    -- What this term measures, in a sentence, for whoever is tuning it. §11.2's
    -- purpose is that ranking be measured rather than argued about, and a table
    -- of nine numbers with no prose is a table nobody can argue about correctly
    -- either.
    description text        NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    -- Who last changed it. No ON DELETE clause, for the reason
    -- `collections.created_by` has none: §17.4 anonymises a departing account in
    -- place, and a cascade would delete the platform's ranking configuration
    -- because a member of staff left.
    updated_by  uuid        REFERENCES users (id),

    -- The nine terms and nothing else. A text column with a CHECK rather than a
    -- native enum, for the reason `projects_state_known` and
    -- `collections_kind_known` give: adding a term is then an ordinary migration
    -- that runs inside a transaction, whereas ALTER TYPE ... ADD VALUE cannot be
    -- used by the same transaction that adds it.
    CONSTRAINT ranking_weights_term_known CHECK (
        term IN (
            'text_match',       -- #43's ts_rank, as the composite's text term (§4.3)
            'pledge_velocity',  -- §11.2 w1
            'backer_velocity',  -- §11.2 w2
            'completion',       -- §11.2 w3
            'editorial',        -- §11.2 w4
            'conversion',       -- §11.2 w5
            'personalisation',  -- §11.2 w6
            'recency',          -- §11.2 w7
            'spam'              -- §11.2 w8, subtracted
        )
    ),
    -- Non-negative. §11.2 writes the spam term with a minus sign in front of it,
    -- so the sign belongs to the term and not to the number: a weight column
    -- that accepted negatives would let somebody invert a term by typing a
    -- character, and "why does a nearly-funded campaign rank last" is not a
    -- question worth having.
    CONSTRAINT ranking_weights_weight_is_not_negative CHECK (weight >= 0),
    -- And bounded above, because a weight is a multiplier over a term that is
    -- normalised into [0, 1] and a hundred is not a stronger opinion than a one
    -- -- it is a term that has silently become the only term. Ten leaves two
    -- orders of magnitude of room over the seeded values, which is more than any
    -- honest tuning needs.
    CONSTRAINT ranking_weights_weight_is_bounded CHECK (weight <= 10),
    -- THE CONSTRAINT THAT KEEPS THE TABLE HONEST. A term nothing can compute
    -- cannot be switched on. Without it, setting `pledge_velocity` active would
    -- be a change that looks like it did something, produces no difference in
    -- any feed, and is indistinguishable from a weight that is simply too small
    -- -- which is the exact failure §11.2's last sentence exists to prevent.
    --
    -- Clearing `blocked_by` is therefore part of the pull request that computes
    -- the term, and never a configuration change on its own.
    CONSTRAINT ranking_weights_inert_terms_are_not_active CHECK (
        blocked_by IS NULL OR NOT active
    ),
    CONSTRAINT ranking_weights_blocked_by_is_not_blank CHECK (
        blocked_by IS NULL OR length(btrim(blocked_by)) > 0
    ),
    CONSTRAINT ranking_weights_description_is_not_blank CHECK (length(btrim(description)) > 0)
);

CREATE TRIGGER ranking_weights_set_updated_at
    BEFORE UPDATE ON ranking_weights
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE ranking_weights IS
    'One row per term of §11.2''s composite. Tunable without a deployment; the running application '
    're-reads it on a bounded TTL. A term with no data source is inert and says so in blocked_by.';
COMMENT ON COLUMN ranking_weights.active IS
    'Whether the term is in the sum. Not the same as a weight of zero: zero counts for nothing, '
    'inactive is not computed at all.';
COMMENT ON COLUMN ranking_weights.blocked_by IS
    'What has to land before this term can be computed. NULL means the data exists today.';

-- ---------------------------------------------------------------------------
-- ranking_weight_changes
-- ---------------------------------------------------------------------------

-- Append-only. Never updated, never deleted.
--
-- WHY TUNING IS A PRIVILEGED ACTION. CLAUDE.md requires every privileged action
-- to be audited, and the question is whether this is one. It is, and by a wider
-- margin than curation: putting a campaign in a collection changes what one
-- campaign gets, and changing `w4` changes what every campaign in every
-- collection gets, in every feed, for every reader, at once. It is the single
-- highest-leverage commercial lever in the product, and "who moved the editorial
-- weight to three the week before the platform's own campaign trended" is a
-- question a creator, a journalist, and eventually a regulator all ask.
--
-- The shape is `curation_events`', which is `project_state_transitions`': who
-- acted, what they changed, from what to what, and the note saying why -- so the
-- decision can be reviewed by somebody who was not there. AD-14's platform-wide
-- audit log is a different table owned by epic #100; this is the module's own
-- record, exactly as the other two are their modules'.
--
-- The before and after are both stored rather than only the after. An audit
-- trail of "it is now 0.4" cannot answer "what was it during the experiment",
-- and reconstructing the previous value by reading the row before is only
-- correct if no row was ever missed.
CREATE TABLE ranking_weight_changes (
    id         uuid        PRIMARY KEY,
    -- No foreign key to `ranking_weights`. Deliberate, and the opposite of what
    -- `curation_events.collection_id` does: a term is a fixed vocabulary rather
    -- than a row somebody created, and a term withdrawn from §11.2 in a future
    -- migration must not take the record of how it was tuned with it. The CHECK
    -- below is what keeps the value meaningful without coupling the two
    -- lifetimes.
    term       text        NOT NULL,
    -- NULL on the first change to a term whose row was seeded and never touched,
    -- which is not the same as a change from zero.
    old_weight numeric,
    new_weight numeric     NOT NULL,
    old_active boolean,
    new_active boolean     NOT NULL,
    -- No ON DELETE, for the reason `curation_events.actor_id` has none: nulling
    -- it would be an update to a row this table promises never to update, and a
    -- cascade would delete the evidence of a ranking decision along with the
    -- account of the person who took it.
    actor_id   uuid        REFERENCES users (id),
    actor_role text        NOT NULL,
    -- Why. Required by the write path on every change, because a year later this
    -- row is the only place the reason still exists -- and because a weight
    -- change with no stated hypothesis is the thing §11.2 calls arguing rather
    -- than measuring.
    note       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ranking_weight_changes_term_known CHECK (
        term IN (
            'text_match', 'pledge_velocity', 'backer_velocity', 'completion', 'editorial',
            'conversion', 'personalisation', 'recency', 'spam'
        )
    ),
    -- The same three roles `curation_events` accepts, for the same reason: only
    -- MODERATOR is ever written today because the interim moderator directory is
    -- a list of addresses and cannot tell a moderator from an admin, and ADMIN is
    -- declared now so that recording the distinction is an INSERT rather than a
    -- migration on the day epic #100 can draw it.
    CONSTRAINT ranking_weight_changes_actor_role_known CHECK (
        actor_role IN ('MODERATOR', 'ADMIN', 'SYSTEM')
    ),
    -- SYSTEM is the only role permitted to have no actor. A human decision
    -- recorded without saying whose it was is not an audit trail.
    CONSTRAINT ranking_weight_changes_human_actions_name_the_actor CHECK (
        actor_id IS NOT NULL OR actor_role = 'SYSTEM'
    ),
    CONSTRAINT ranking_weight_changes_note_is_not_blank CHECK (length(btrim(note)) > 0)
);

-- "How has this term been tuned, in order" -- every review of a ranking decision
-- starts here, and so does every attempt to correlate a weight with a metric.
CREATE INDEX ranking_weight_changes_term_idx ON ranking_weight_changes (term, created_at);
-- "What has this member of staff changed" -- asked when a decision is disputed.
CREATE INDEX ranking_weight_changes_actor_idx ON ranking_weight_changes (actor_id, created_at);

COMMENT ON TABLE ranking_weight_changes IS
    'Append-only audit of every ranking weight change (§11.2, CLAUDE.md). Never updated, never '
    'deleted. Stores both the before and the after, so "what was it during the experiment" is answerable.';

-- ---------------------------------------------------------------------------
-- The seed
-- ---------------------------------------------------------------------------

-- WHERE THESE NUMBERS CAME FROM, PLAINLY: nowhere. They are not measured, and
-- presenting them as if they were would be the exact dishonesty §11.2's last
-- sentence is about. Nothing on this platform has ever been ranked by relevance,
-- so there is no click-through, no conversion, and no held-out set to fit
-- against -- and there cannot be until the sort has been serving for a while.
--
-- What they are is a defensible starting point, chosen against two rules:
--
--   1. THE FOUR LIVE WEIGHTS SUM TO EXACTLY 1. Every live term is normalised
--      into [0, 1] (see RelevanceScore), so the composite is then in [0, 1] too.
--      That is worth having for a reason beyond neatness: a score with a known
--      range can be read, compared between two campaigns, and put in front of
--      somebody tuning it, whereas an unbounded sum can only be compared with
--      itself.
--   2. NO TERM CAN WIN ALONE. The largest is 0.35, so no single term can
--      outrank the sum of the other three. A ranking in which one signal
--      dominates is a ranking with one signal and three decorations.
--
-- Within those, the ordering is a product judgement and is stated so it can be
-- argued with:
--
--   text_match 0.35  When somebody typed words, what they typed is the strongest
--                    thing known about what they want. It is also zero for every
--                    campaign on a feed with no query, so on the browsing feed
--                    the other three effectively renormalise to themselves.
--   recency    0.30  Discovery's job is to circulate attention. §4.3 makes
--                    newest the default sort for exactly this reason: an order
--                    with no recency term opens on the same campaigns for
--                    everybody for ever, which is the failure mode of every
--                    ranked feed that has one.
--   completion 0.20  Momentum is real social proof and it is the weakest of the
--                    three signals here, because it compounds: a campaign ranked
--                    highly raises more, which ranks it more highly. Held below
--                    recency deliberately.
--   editorial  0.15  The platform's own opinion, and the smallest on purpose. It
--                    is the one term a human sets directly, so it is the one that
--                    most needs to be a nudge rather than a decision -- §22.3's
--                    transparency and the reason V14 made the badge an audited
--                    editorial act rather than a boolean.
--
-- The five inert terms are seeded at zero and inactive. Zero rather than a
-- guessed value so that the day one of them starts computing, nothing changes
-- until somebody deliberately weights it -- a term that switched on with an
-- invented weight would reshuffle every feed on the platform as a side effect of
-- a deployment.
INSERT INTO ranking_weights (term, weight, active, blocked_by, description) VALUES
    ('text_match', 0.35, true, NULL,
     'How well the campaign''s text matches what the reader typed: #43''s ts_rank over the folded '
     'search vector, clamped to [0,1]. Zero for every campaign when there is no query.'),

    ('pledge_velocity', 0, false, '#50 (pledge ledger)',
     '§11.2 w1: money raised in the last 48 hours, normalised. INERT: projects.pledged_amount is a '
     'running total with no time series behind it, so a 48-hour window cannot be computed from it.'),

    ('backer_velocity', 0, false, '#50 (pledge ledger)',
     '§11.2 w2: backers gained in the last 48 hours, normalised. INERT for the same reason as w1: '
     'projects.backers_count is a running total.'),

    ('completion', 0.20, true, NULL,
     '§11.2 w3: how close the campaign is to its goal, through a sigmoid that saturates above it. '
     'Midpoint at exactly 100%; see RelevanceScore for the curve and why.'),

    ('editorial', 0.15, true, NULL,
     '§11.2 w4: whether the campaign carries an editorial badge, from #48''s project_editorial_badges '
     'view. One or zero -- being in two staff lists is not twice endorsed.'),

    ('conversion', 0, false, '#95 (analytics aggregation)',
     '§11.2 w5: view-to-pledge conversion, normalised. INERT: nothing records a view, so neither '
     'half of the ratio exists.'),

    ('personalisation', 0, false, 'D-07 (personalised feed) and per-caller signals',
     '§11.2 w6: how well the campaign fits this reader. INERT: the discovery feed is anonymous and '
     'publicly cached, and no per-caller signal is stored. This is also what keeps showOnly=recommended refused.'),

    ('recency', 0.30, true, NULL,
     '§11.2 w7: how recently the campaign launched, decaying towards zero. Half-value at seven days; '
     'see RelevanceScore. Zero for a campaign that has never launched.'),

    ('spam', 0, false, '#108 (automated fraud signals)',
     '§11.2 w8, SUBTRACTED from the composite: how much the campaign looks like abuse. INERT: no '
     'automated fraud signal is computed anywhere in the platform.');

-- No audit row for the seed. `ranking_weight_changes` records decisions somebody
-- took, and a migration is not somebody -- a SYSTEM row here would put nine
-- entries at the head of every term's history saying only that the table was
-- created, which is what the migration log already says.

-- ---------------------------------------------------------------------------
-- WHAT IS DELIBERATELY NOT HERE: AN INDEX FOR sort=relevance
-- ---------------------------------------------------------------------------
--
-- For the reason V12 gives about `sort=popularity`, and one more. The composite
-- is an expression over several columns, a request parameter (the instant the
-- scroll started), AND a configuration this table holds -- so an expression
-- index would have to bake in one instant and one set of weights, and would be
-- invalidated by the very tuning this migration exists to make possible.
-- PostgreSQL sorts the matching rows instead.
--
-- MEASURED rather than asserted, on postgres:16-alpine against 20,000 publicly
-- visible campaigns -- twice §11.1's tier-1 ceiling -- with 200 of them in a
-- published, badge-granting collection, after ANALYZE and with the cache warm.
-- EXPLAIN (ANALYZE, BUFFERS), first page, limit 25:
--
--   sort=relevance, no query        62.1 ms   1844 shared hits, 0 read
--   sort=relevance, category filter 69.6 ms   1846 shared hits, 0 read
--   sort=popularity  (#42, same table, for comparison)  137.9 ms
--   sort=newest      (index-served, for comparison)       0.44 ms
--
-- Two things in that plan are the design working:
--
--   * The editorial term plans as a HASHED SubPlan, not as a correlated probe.
--     `p.id IN (SELECT project_id FROM project_editorial_badges)` is
--     uncorrelated, so the badge set is built once (200 rows, 9 buffers) and
--     every campaign is a hash lookup. Written as a correlated EXISTS it would
--     have been 20,000 nested-loop probes.
--   * Relevance is CHEAPER than the popularity sort already shipped, which is
--     what the arithmetic predicted: four numeric multiplications and two
--     divisions per row against numeric `sqrt`, plus the planner parallelised
--     it. §11.2's sigmoid is a Hill function rather than a logistic precisely
--     so that no `exp` or `power` appears here -- see RelevanceScore.
--
-- A SEEDED DATABASE IS NOT A LOAD TEST (#141). 62ms is one query on an idle
-- container with a warm cache; it says the shape is right and the constant is
-- small, and it says nothing about 1,000 rps (§20), about concurrency, about
-- what the plan does when the table no longer fits in shared buffers, or about
-- what two parallel workers per query cost when a hundred of them arrive at
-- once. What it does establish is the ceiling §11.1 already names: this is
-- linear in publicly visible campaigns, no index removes that, and the step
-- after it is tier 2 rather than another CREATE INDEX.

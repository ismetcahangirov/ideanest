-- §4.7's CD-03 and §7.2's `referrers` (#94): where a pledge came from, and the
-- visits that are the evidence for the answer.
--
-- ---------------------------------------------------------------------------
-- TWO TABLES, BECAUSE THERE ARE TWO FACTS
-- ---------------------------------------------------------------------------
--
-- §7.2 names one table, `referrers`, and one table cannot hold this. A visit and
-- an attribution have different lifetimes, different privacy weight, and
-- different truth conditions:
--
--   * A **touch** is a visit that carried a source. It is evidence, it is only
--     evidence for as long as the attribution window says it is, and most of them
--     never lead to anything. There are as many of these as there is traffic.
--   * An **attribution** is the answer for one pledge. It is a financial report
--     line, it is decided once, and it must not change afterwards — a creator who
--     read "the newsletter brought forty pledges" last week must read the same
--     number this week.
--
-- Keeping them in one table would mean either deleting rows a report is made of,
-- or retaining browsing evidence for as long as the platform retains financial
-- records. Both are wrong, and in opposite directions.
--
-- The attribution row therefore **copies** the source it resolved to rather than
-- joining to the touch for it. That denormalisation is the point: the touch is
-- allowed to be pruned when its window closes, and the report does not move when
-- it is.
--
-- ---------------------------------------------------------------------------
-- THE RULE THIS SCHEMA IS SHAPED FOR: LAST NON-DIRECT TOUCH, INSIDE THE WINDOW
-- ---------------------------------------------------------------------------
--
-- Among the touches recorded for one visitor on one campaign, the winner is the
-- most recent one that is not `DIRECT`, ignoring any whose window had already
-- closed and any recorded after the pledge. A pledge with no such touch is
-- attributed to `DIRECT` and appears in the report as such rather than being
-- left out of it.
--
-- "Non-direct" is what makes the rule worth stating. Somebody who arrives from a
-- newsletter, thinks about it overnight and then types the address in has still
-- been brought here by the newsletter, and a plain "last touch" rule would
-- report every considered purchase as unattributable — which is the shape of
-- report that makes a creator stop spending on the thing that is working.
--
-- **The window is stored on the row, not applied from configuration at read
-- time.** `expires_at` is written when the touch is recorded, so changing
-- `ideanest.analytics.referral.attribution-window` changes what happens to
-- visits from then on and cannot retroactively rewrite a report. A window
-- applied at read time would mean shortening it silently deleted history and
-- lengthening it silently invented some.
--
-- ---------------------------------------------------------------------------
-- WHAT IDENTIFIES A VISITOR, AND WHAT IS DELIBERATELY NOT HERE
-- ---------------------------------------------------------------------------
--
-- `visitor_hash` is the SHA-256 of an opaque 256-bit token the server minted and
-- handed to the client — `shared.SecureTokens`, the same construction as a
-- verification link. Two properties follow, and both are the reason it is not
-- something simpler:
--
--   * **It is not derived from an account.** A referral code or a visitor
--     identifier computed from `users.id` — hashed, encoded, sequential, any of
--     them — turns "guess a code" into "enumerate the platform's users", which
--     is exactly what #94 must not build. Nothing here is derived from anything;
--     it is randomness, and possession of a token says nothing about who holds
--     it.
--   * **The token itself is never stored.** A leaked copy of this table does not
--     let the reader forge a visit or claim somebody else's touches, because the
--     column is a hash of a value it does not contain.
--
-- `backer_id` is the one identity here and it is nullable, because it is absent
-- for the whole part of the journey that matters most: a visitor is anonymous
-- until they sign in. It is filled in when an authenticated caller presents a
-- token they were already holding, which is what links the browsing to the
-- account without ever asking the client to tell us who it is.
--
-- **There is no backer identifier on `referral_attributions`, and that is
-- deliberate.** The report this table exists for is read by a creator, and a
-- creator who can see that a pledge attributed to "instagram, launch-week" is
-- the pledge of a named backer has been told something §4.5's PL-12 spent a
-- column on not telling them. The row has nowhere to put one, which is a
-- stronger guarantee than remembering to leave it out of a projection.
--
-- ---------------------------------------------------------------------------
-- FOREIGN KEYS, AND THE ONE THAT IS ABSENT
-- ---------------------------------------------------------------------------
--
-- `project_id` references `projects` and cascades: a campaign that is hard
-- deleted takes its traffic and its attribution with it, and neither is a
-- financial record — the pledges are, and they are elsewhere.
--
-- `pledge_id` deliberately has **no** foreign key, for V19's reason about
-- `outbox_events.aggregate_id`. This row is written by a consumer of a domain
-- event and not by the transaction that created the pledge, so a reference would
-- couple the analytics module's writes to the pledge module's table — the
-- coupling `ModuleBoundaryTests` exists to keep out of the Java, arriving through
-- the schema instead. `pledge_id UNIQUE` is what the correctness actually needs:
-- see below.
--
-- ---------------------------------------------------------------------------
-- REDELIVERY
-- ---------------------------------------------------------------------------
--
-- `OutboxMessage`'s contract is at-least-once, in those words: "handlers must
-- tolerate redelivery". `referral_attributions_pledge_key` is how this one does.
-- The listener checks first and the index decides — two deliveries of the same
-- `pledge.confirmed` produce one row, and the second attempt is refused by the
-- database rather than by a check that happened to run first. `event_id` records
-- which delivery won, so an operator can tell a redelivered event from a second
-- event.
--
-- ---------------------------------------------------------------------------
-- RETENTION, WHICH IS NOT BUILT HERE
-- ---------------------------------------------------------------------------
--
-- A touch past its `expires_at` can never win another attribution: the query
-- excludes it, and no configuration change brings it back. It is therefore
-- deletable the moment it expires, and `referral_touches_expiry_idx` is the
-- index a sweep would use.
--
-- **The sweep is not in this migration and there is no job that runs it.** §8.4's
-- schedule table owns when work happens, every `ScheduledJob` in the service reads
-- its cron from `application.yml`, and adding an entry there is outside this
-- change. Said plainly rather than implied: until that job exists, this table
-- grows with traffic and holds expired rows for ever. It is the largest thing
-- #94 leaves undone.
--
-- ---------------------------------------------------------------------------
-- Rolling deployment
-- ---------------------------------------------------------------------------
--
-- Two new tables, four new indexes, and nothing existing touched. No column is
-- dropped, no constraint is added to an existing table, and no previous release
-- reads or writes anything here. Both halves of a rolling deploy are safe in
-- either order. This is an EXPAND with no contract half.
--
-- Reverse:
--   DROP TABLE IF EXISTS referral_attributions;
--   DROP TABLE IF EXISTS referral_touches;
--
--   In that order — the attribution references the touch. Safe while nothing
--   writes here, which is true of every release before this one. Afterwards it
--   discards every attributed pledge, and those rows cannot be recomputed: the
--   evidence they were derived from expires, so a rebuild would attribute
--   everything older than the window to DIRECT.

-- ---------------------------------------------------------------------------
-- referral_touches — the evidence
-- ---------------------------------------------------------------------------
CREATE TABLE referral_touches (
    -- UUID v7, minted by the application like every other key here. Time-ordered,
    -- which is also what breaks a tie between two touches recorded in the same
    -- millisecond: the later row is the greater identifier.
    id             uuid        PRIMARY KEY,

    project_id     uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,

    -- SHA-256 of the visitor token. See the header for why it is a hash of
    -- randomness rather than anything derived from an account.
    visitor_hash   bytea       NOT NULL,

    -- The account this visitor turned out to be, once they sign in. Null for the
    -- anonymous part of the journey, which is most of it. No foreign key for the
    -- reason `pledge_id` has none: this row is not part of the account's
    -- lifecycle, and §17.4 anonymises an account in place rather than deleting
    -- it, so there is nothing for a cascade to do that pruning does not.
    backer_id      uuid,

    -- What kind of source this was, from a closed vocabulary. A check constraint
    -- rather than an enum type, for V19's reason about `event_type`: the set can
    -- grow, and a new channel should be a migration and not a deployment-ordering
    -- problem. `analytics.domain.ReferralChannel` is the closed set on the
    -- writing side.
    channel        text        NOT NULL,

    -- The place, folded: `twitter`, `newsletter`. Folded so that "Twitter" and
    -- "twitter" are one row in the report rather than two shares of the same
    -- traffic.
    source         text,

    -- Which push, when the source runs more than one: `launch-week`.
    campaign       text,

    -- The creator's own share link, when the visit came through one. Bounded at
    -- 64 to match `pledges_referrer_code_length`, because the same code is what
    -- a pledge carries and the two must be comparable.
    referrer_code  text,

    -- Passed in from the application's Clock rather than defaulted to now(), for
    -- V17's reason: this instant drives a rule — whether the touch is still open
    -- at the moment of a pledge — and a rule about time needs one home and a
    -- clock a test can move.
    occurred_at    timestamptz NOT NULL,

    -- occurred_at + the attribution window in force when the visit happened. See
    -- the header for why the window is frozen onto the row.
    expires_at     timestamptz NOT NULL,

    CONSTRAINT referral_touches_channel_known CHECK (
        channel IN ('DIRECT', 'REFERRAL_LINK', 'SOCIAL', 'EMAIL', 'SEARCH', 'PAID', 'OTHER')
    ),
    -- A direct visit is the absence of a source. A row that claims both is one
    -- the attribution rule would have to guess about, and it would guess in the
    -- direction that inflates whatever was named.
    CONSTRAINT referral_touches_direct_names_nothing CHECK (
        channel <> 'DIRECT'
        OR (source IS NULL AND campaign IS NULL AND referrer_code IS NULL)
    ),
    -- And the other direction: a non-direct visit the report cannot label is a
    -- row that can only ever become an unlabelled share of somebody's traffic.
    CONSTRAINT referral_touches_named_unless_direct CHECK (
        channel = 'DIRECT' OR source IS NOT NULL OR referrer_code IS NOT NULL
    ),
    CONSTRAINT referral_touches_source_length CHECK (
        source IS NULL OR length(btrim(source)) BETWEEN 1 AND 64
    ),
    CONSTRAINT referral_touches_campaign_length CHECK (
        campaign IS NULL OR length(btrim(campaign)) BETWEEN 1 AND 64
    ),
    CONSTRAINT referral_touches_referrer_code_length CHECK (
        referrer_code IS NULL OR length(btrim(referrer_code)) BETWEEN 1 AND 64
    ),
    -- A window that closed before it opened would make every touch expired, and
    -- a zero-length one would make attribution impossible for reasons no query
    -- would explain.
    CONSTRAINT referral_touches_window_is_open CHECK (expires_at > occurred_at),
    -- 32 bytes. Not a length the application could get wrong by accident, and a
    -- column holding something shorter is a column holding something that is not
    -- a SHA-256.
    CONSTRAINT referral_touches_visitor_hash_is_sha256 CHECK (octet_length(visitor_hash) = 32)
);

-- The attribution query, exactly: this campaign, this backer, most recent first.
-- Partial, because a touch with no account attached can never be selected by it —
-- and for most campaigns the anonymous rows are the overwhelming majority.
CREATE INDEX referral_touches_attribution_idx
    ON referral_touches (project_id, backer_id, occurred_at DESC)
    WHERE backer_id IS NOT NULL;

-- The two writes: finding a visitor's recent touches to deduplicate against, and
-- claiming their earlier anonymous ones when they sign in.
CREATE INDEX referral_touches_visitor_idx
    ON referral_touches (project_id, visitor_hash, occurred_at DESC);

-- What a retention sweep would claim. See the header for why the sweep itself is
-- not here.
CREATE INDEX referral_touches_expiry_idx ON referral_touches (expires_at);

COMMENT ON TABLE referral_touches IS
    'CD-03 (#94): one row per visit that carried a source. Evidence for the attribution rule, valid until expires_at, and prunable after it.';
COMMENT ON COLUMN referral_touches.visitor_hash IS
    'SHA-256 of an opaque token the server minted. Never derived from an account: see V24 for why a derivable code would be a user enumeration.';
COMMENT ON COLUMN referral_touches.expires_at IS
    'The attribution window as it stood when the visit happened, frozen onto the row so that reconfiguring it cannot rewrite a report.';

-- ---------------------------------------------------------------------------
-- referral_attributions — the answer
-- ---------------------------------------------------------------------------
CREATE TABLE referral_attributions (
    id             uuid        PRIMARY KEY,

    -- Which pledge. Unique, and no foreign key; see the header for both.
    pledge_id      uuid        NOT NULL,

    project_id     uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,

    -- Which touch decided it, for an operator asking "why does this say that".
    -- Nullable in two cases that are not the same: a pledge attributed to DIRECT
    -- had no touch to name, and a touch that has since been pruned leaves the
    -- answer standing without its evidence.
    touch_id       uuid        REFERENCES referral_touches (id) ON DELETE SET NULL,

    -- The source, copied rather than joined. The header says why.
    channel        text        NOT NULL,
    source         text,
    campaign       text,
    referrer_code  text,

    -- §7.3's numeric(14,2), and the currency beside it as every other money row
    -- in this schema carries it. Never a float: this column is summed into the
    -- figure a creator uses to decide where to spend.
    amount         numeric(14,2) NOT NULL,
    -- `text` with a shape constraint, as `pledges.currency` is: a char(3) would be
    -- blank-padded by PostgreSQL and would have to be trimmed by every reader.
    currency       text        NOT NULL,

    -- When the pledge was confirmed, which is the instant the rule is applied
    -- against — not when this row was written, which is whenever the event was
    -- delivered.
    pledged_at     timestamptz NOT NULL,
    attributed_at  timestamptz NOT NULL,

    -- The outbox event this row was written from. Kept so that a redelivery and a
    -- genuine second event can be told apart during an incident; the uniqueness
    -- above is what makes the two harmless either way.
    event_id       uuid        NOT NULL,

    CONSTRAINT referral_attributions_pledge_key UNIQUE (pledge_id),
    CONSTRAINT referral_attributions_channel_known CHECK (
        channel IN ('DIRECT', 'REFERRAL_LINK', 'SOCIAL', 'EMAIL', 'SEARCH', 'PAID', 'OTHER')
    ),
    CONSTRAINT referral_attributions_direct_names_nothing CHECK (
        channel <> 'DIRECT'
        OR (source IS NULL AND campaign IS NULL AND referrer_code IS NULL)
    ),
    CONSTRAINT referral_attributions_named_unless_direct CHECK (
        channel = 'DIRECT' OR source IS NOT NULL OR referrer_code IS NOT NULL
    ),
    CONSTRAINT referral_attributions_source_length CHECK (
        source IS NULL OR length(btrim(source)) BETWEEN 1 AND 64
    ),
    CONSTRAINT referral_attributions_campaign_length CHECK (
        campaign IS NULL OR length(btrim(campaign)) BETWEEN 1 AND 64
    ),
    CONSTRAINT referral_attributions_referrer_code_length CHECK (
        referrer_code IS NULL OR length(btrim(referrer_code)) BETWEEN 1 AND 64
    ),
    -- A pledge is money somebody committed. Zero is possible in no flow the
    -- platform has, and a negative attributed value would make a share of the
    -- total meaningless for every other row in the report.
    CONSTRAINT referral_attributions_amount_is_positive CHECK (amount > 0),
    CONSTRAINT referral_attributions_currency_shape CHECK (currency ~ '^[A-Z]{3}$')
);

-- The report: this campaign, grouped by source. The whole of CD-03 is one scan of
-- this index for one project.
CREATE INDEX referral_attributions_report_idx
    ON referral_attributions (project_id, channel, source, campaign, referrer_code);

COMMENT ON TABLE referral_attributions IS
    'CD-03 (#94): one row per attributed pledge, and never more than one — referral_attributions_pledge_key is how the listener tolerates redelivery. Carries no backer identifier, deliberately.';
COMMENT ON COLUMN referral_attributions.touch_id IS
    'The touch that won, when there was one. Null for a pledge attributed to DIRECT, and null again once the touch has been pruned; the answer does not move either way.';
COMMENT ON COLUMN referral_attributions.event_id IS
    'The pledge.confirmed delivery this row was written from. Diagnostic: uniqueness on pledge_id is what makes a redelivery harmless.';

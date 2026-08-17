-- Replay protection for every payment mutation, and the add-on lines a pledge
-- was quoted from (#52).
--
-- Two tables, and they are here together because they are the two things
-- `POST /v1/pledges/draft` writes that V17 did not create.
--
-- ---------------------------------------------------------------------------
-- WHY `idempotency_keys` IS A TABLE OF ITS OWN, WHEN `pledges` ALREADY HAS THE
-- COLUMN
-- ---------------------------------------------------------------------------
--
-- V17 gave `pledges` an `idempotency_key` and a partial unique index over it,
-- and said the index "is what makes a retried request return the original
-- pledge rather than create a second one". That is true and it is kept, but it
-- is only half of §10.3's requirement, for three reasons.
--
--   1. **A replay has to return the original response, not the original row.**
--      §10.3 requires the header on `POST /v1/pledges/{id}/confirm`,
--      `PATCH /v1/pledges/{id}` and `DELETE /v1/pledges/{id}` as well as on the
--      draft, and none of those three creates a row to find. A retried
--      cancellation has to answer 204 rather than 404 — the contract for epic
--      #50 says so explicitly — and there is no pledge in a deleted state to
--      carry the key.
--
--   2. **The same key with a different body is a conflict**, and nothing on
--      `pledges` can notice that. Refusing it needs the request that was made
--      the first time, which is what `request_fingerprint` below is.
--
--   3. **§17.2 retains keys for 24 hours.** A pledge is retained for as long as
--      there is money to account for, so the key on it cannot be swept without
--      writing to a financial record for a reason that has nothing to do with
--      the pledge.
--
-- So this is §7.2's `idempotency_keys` — listed there as "Replay protection" —
-- and it is deliberately not pledge-specific: it names the operation and the
-- account, and nothing about what was bought. Epic #59's charges and #56's
-- edits use the same table without a second implementation of the same rule.
--
-- ---------------------------------------------------------------------------
-- Rolling deployment
-- ---------------------------------------------------------------------------
--
-- Two new tables, one new unique constraint on `pledges`, and one index on
-- `pledges` replaced by a wider one. Nothing in the previous release reads
-- either table; the constraint is over `(id, project_id)` where `id` is already
-- the primary key, so it cannot refuse a row the old build could have written;
-- and the index that is replaced is partial over a column that is null on every
-- row written before this release. Both halves of a rolling deploy are safe.
-- This is an EXPAND with no contract half.
--
-- Reverse:
--   DROP TABLE IF EXISTS pledge_addons;
--   DROP TABLE IF EXISTS idempotency_keys;
--   ALTER TABLE pledges DROP CONSTRAINT IF EXISTS pledges_identity_within_project;
--   DROP INDEX IF EXISTS pledges_backer_idempotency_key_key;
--   CREATE UNIQUE INDEX pledges_idempotency_key_key
--       ON pledges (idempotency_key) WHERE idempotency_key IS NOT NULL;
--
--   Putting V17's index back can fail, and the failure is the interesting part:
--   it refuses if two backers have both used the same key, which is exactly the
--   case this migration widened the index to allow. That is a real row and not a
--   corruption; whoever reverses this has to decide what to do with it.
--
--   Dropping `idempotency_keys` is safe at any time and costs exactly one
--   thing: for the 24 hours the sweep would have kept them, a retried payment
--   mutation is executed a second time instead of replaying its first answer.
--   That is the failure §10.3 exists to prevent, so the reverse is a decision
--   to accept it, not a tidy-up.
--
--   Dropping `pledge_addons` destroys what a backer chose beyond their reward
--   tier. The amount survives on `pledges.addons_amount`, so no total changes
--   and no card is charged differently — but the creator no longer knows what
--   to send. Safe only before the platform has taken a pledge with an add-on
--   on it; afterwards, as everywhere else in this directory, the way back from
--   a bad release is the previous build of the application.

-- ---------------------------------------------------------------------------
-- idempotency_keys
-- ---------------------------------------------------------------------------

CREATE TABLE idempotency_keys (
    id                  uuid        PRIMARY KEY,
    -- **Keys are scoped to the account that spent them.** Without this column
    -- in the unique key below, one client could replay another's request by
    -- guessing a key, and worse, could be handed the response body of somebody
    -- else's pledge — which names the campaign, the amount, and the reward.
    --
    -- Cascades, unlike `pledges.backer_id`. A replay record is a note about a
    -- request made in the last day and nothing is accounted for against it, so
    -- an account that can be hard deleted at all takes its keys with it.
    account_id          uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Which mutation the key was spent on: `pledge.draft`, `pledge.confirm`,
    -- and whatever epic #59 adds. Recorded rather than derived because the
    -- table is shared, and an operator looking at a stuck key needs to know
    -- what it was for without joining to anything.
    --
    -- It is also part of the fingerprint below, which is what makes reusing one
    -- key for two different operations a conflict rather than a silent replay
    -- of the wrong answer.
    operation           text        NOT NULL,
    -- The client's key, verbatim. §10.3 calls for a UUID and the application
    -- refuses anything else; the bound here is the same one V17 put on
    -- `pledges.idempotency_key`, because this column and that one hold the same
    -- string.
    idempotency_key     text        NOT NULL,

    -- **A fingerprint of the request, not the request.**
    --
    -- The only question ever asked of it is "is this the same request as last
    -- time", and a SHA-256 answers that exactly. Keeping the body itself would
    -- put what somebody is buying, how much they chose to give, and where they
    -- live into a second table with a different retention rule from the pledge
    -- it describes — a copy of financial and personal data that no reader
    -- needs, kept for a day, for the sake of an equality check.
    request_fingerprint text        NOT NULL,

    -- **Null until the operation has succeeded**, which is what makes the row a
    -- claim on the key as well as a record of it. The unique index below is what
    -- decides a race between two identical requests arriving at once: the loser's
    -- insert is refused, it reads this row, and the two states are the two
    -- answers it can be given — a response to replay, or "the first one is still
    -- running, retry".
    response_status     integer,
    -- **text, deliberately, and not jsonb.** §10.3's guarantee is that a replay
    -- returns *the original response*, and jsonb is a parsed representation: it
    -- discards key order and whitespace and re-serialises on the way out, so a
    -- client that compared the two would find the bytes had changed. This column
    -- holds the bytes that were sent.
    response_body       text,
    completed_at        timestamptz,

    created_at          timestamptz NOT NULL DEFAULT now(),
    -- §17.2: "keys retained 24 hours". Written by the application from the
    -- injected Clock and a configured retention, not by a DEFAULT here, for
    -- V17's reason: a default would be a second source of a rule that already
    -- exists in one place, and it would be unreachable from a test that needs to
    -- ask what happens a day later without waiting a day.
    expires_at          timestamptz NOT NULL,

    CONSTRAINT idempotency_keys_key_length CHECK (
        length(btrim(idempotency_key)) BETWEEN 8 AND 255
    ),
    CONSTRAINT idempotency_keys_operation_length CHECK (
        length(btrim(operation)) BETWEEN 1 AND 64
    ),
    -- Lowercase hexadecimal SHA-256. Pinned so that a caller cannot store the
    -- request itself in this column by accident, which is the one thing the
    -- comment above says it is for not doing.
    CONSTRAINT idempotency_keys_fingerprint_shape CHECK (
        request_fingerprint ~ '^[0-9a-f]{64}$'
    ),
    -- A half-written outcome is the one state that would be actively harmful: a
    -- status with no body replays as an empty 201, and a body with no status
    -- cannot be replayed at all. Either the row is a claim on a key or it is a
    -- recorded answer, and this is what stops it being neither.
    CONSTRAINT idempotency_keys_outcome_is_whole CHECK (
        (response_status IS NULL AND response_body IS NULL AND completed_at IS NULL)
        OR (response_status IS NOT NULL AND response_body IS NOT NULL AND completed_at IS NOT NULL)
    ),
    -- Only a success is ever recorded. A failure is not replayed — the
    -- application releases the key instead, so that a client whose request was
    -- refused for a reason that has since changed (a place freed by the sweep)
    -- can retry it rather than being handed yesterday's refusal for a day.
    CONSTRAINT idempotency_keys_outcome_is_a_success CHECK (
        response_status IS NULL OR response_status BETWEEN 200 AND 299
    )
);

-- **This index is the concurrency control**, and not an optimisation.
--
-- Two identical requests arriving at the same instant both find no row, both
-- decide they are the first, and both would execute — a read followed by a
-- write cannot prevent that, however carefully it is written, because there is
-- no moment at which either transaction can see the other's intent. The insert
-- is the intent, and PostgreSQL is what serialises it: exactly one of the two
-- inserts succeeds, and the other is refused by this index and has to look at
-- what the winner wrote.
--
-- Scoped by account for the reason on `account_id`: one caller must never be
-- able to reach another's recorded response by choosing their key.
CREATE UNIQUE INDEX idempotency_keys_account_key_key
    ON idempotency_keys (account_id, idempotency_key);

-- §17.2's 24 hours, swept by an in-process @Scheduled job on §8.4's terms — the
-- same arrangement `reservation-cleaner` has, and for the same reason: there is
-- no durable scheduler until #134. Partial over nothing, because every row
-- expires and the sweep looks at all of them.
CREATE INDEX idempotency_keys_expiring_idx ON idempotency_keys (expires_at);

COMMENT ON TABLE idempotency_keys IS
    '§10.3 and §17.2: a payment mutation retried with the same Idempotency-Key returns its original response. Retained 24 hours.';
COMMENT ON COLUMN idempotency_keys.request_fingerprint IS
    'SHA-256 of the operation and the request body. A fingerprint rather than the body: the only question asked of it is whether the request is the same one.';
COMMENT ON COLUMN idempotency_keys.response_body IS
    'The exact bytes that were sent. text and not jsonb, so that a replay is byte-identical rather than re-serialised.';

-- ---------------------------------------------------------------------------
-- pledge_addons
-- ---------------------------------------------------------------------------
--
-- §4.5's PL-04: add-ons, with the quantity the backer chose.
--
-- **§7.2 gives `pledges` an `addons_amount` and nothing else**, and a sum is not
-- enough to run the platform on. Three readers need the lines and not the total:
-- the backer, who is shown what they selected on every screen after the draft;
-- #56's `PATCH`, which re-quotes a changed selection and cannot re-quote a
-- number; and the creator, who has to put the right things in the box. So this
-- table is an addition to §7.2 rather than a reading of it, and `docs/architecture.md`
-- is updated in the same change to say so.
--
-- A table rather than a jsonb column on `pledges`, because these are references
-- to `reward_tiers` rows: a jsonb array cannot be a foreign key, and a tier
-- deleted out from under a pledge that names it is exactly what V17's composite
-- reference exists to refuse for the reward tier.

-- ---------------------------------------------------------------------------
-- pledges.idempotency_key: scoped to the backer, as the rule always was
-- ---------------------------------------------------------------------------
--
-- V17 created `pledges_idempotency_key_key` over the column alone, which was the
-- right shape for a column nothing wrote. Now that #52 writes it, the index is
-- one field narrower than the rule it is meant to hold.
--
-- **A key belongs to the account that minted it.** `idempotency_keys` above is
-- unique over `(account_id, idempotency_key)` because a key that were global
-- would let one caller reach another's recorded response by guessing theirs.
-- The column on `pledges` is the second line under the same guarantee, so it has
-- to be the same rule: over the key alone, two different backers who happened to
-- generate the same UUID — improbable, and not impossible, and entirely within
-- their rights — would have the second of them refused by a constraint violation
-- rather than served.
--
-- What the index still does, and is here for, is unchanged: even a total failure
-- of the machinery in `shared` cannot produce two pledges from one backer's one
-- key.
--
-- Rolling deployment: both indexes are partial over a column that is null on
-- every row the previous release wrote, so neither can refuse anything an older
-- build does. Dropping one and creating the other is an EXPAND in practice, and
-- the window between them is a table with no rows in it to protect.
DROP INDEX pledges_idempotency_key_key;

CREATE UNIQUE INDEX pledges_backer_idempotency_key_key
    ON pledges (backer_id, idempotency_key) WHERE idempotency_key IS NOT NULL;

COMMENT ON COLUMN pledges.idempotency_key IS
    'Which request made this pledge. Unique per backer, matching idempotency_keys. The full guarantee, including the recorded response, is that table.';

-- What the composite reference below points at. `id` is already unique — it is the
-- primary key — so this constrains nothing new about `pledges` and exists only to
-- give the reference a key to name. The same device, for the same reason, as V7's
-- `reward_tiers_identity_within_project`.
--
-- Before the table that references it, because PostgreSQL resolves a foreign key's
-- target when the table is created and not when a row is written.
ALTER TABLE pledges
    ADD CONSTRAINT pledges_identity_within_project UNIQUE (id, project_id);

CREATE TABLE pledge_addons (
    pledge_id      uuid    NOT NULL,
    reward_tier_id uuid    NOT NULL,
    -- Denormalised from both parents so that the two composite references below
    -- can exist. It is not a third opinion about which campaign this is: the
    -- references make it *the same* project_id as the pledge's and as the tier's,
    -- so a row where they disagreed cannot be written.
    project_id     uuid    NOT NULL,
    quantity       integer NOT NULL,

    -- One row per tier per pledge. Two rows for one add-on would be a quantity
    -- expressed in two places, and the total would depend on whether whoever
    -- read it remembered to sum them.
    PRIMARY KEY (pledge_id, reward_tier_id),

    CONSTRAINT pledge_addons_quantity_is_positive CHECK (quantity >= 1),

    -- Cascades from the pledge: an add-on line has no meaning without the pledge
    -- that selected it, and #56's edit replaces the set wholesale.
    CONSTRAINT pledge_addons_belong_to_a_pledge_of_the_same_project
        FOREIGN KEY (pledge_id, project_id) REFERENCES pledges (id, project_id)
        ON DELETE CASCADE,

    -- The composite reference of V17's `pledges_reference_a_tier_of_the_same_project`,
    -- for exactly its reason: without `project_id` in the key, a pledge on one
    -- campaign could name an add-on from another, and no single-column reference
    -- can refuse that.
    --
    -- NO ACTION so that a tier somebody has selected cannot be deleted out from
    -- under them, and DEFERRABLE INITIALLY DEFERRED because deleting a campaign
    -- cascades to `pledges`, to this table, and to `reward_tiers` from one
    -- statement — and a check performed the moment the tier row goes would refuse
    -- it on the strength of a row the same statement is about to remove.
    CONSTRAINT pledge_addons_reference_a_tier_of_the_same_project
        FOREIGN KEY (reward_tier_id, project_id) REFERENCES reward_tiers (id, project_id)
        ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED
);

-- "Which pledges selected this add-on", asked by the creator's fulfilment
-- report, and asked by PostgreSQL on every attempt to delete a tier — which
-- without an index is a sequential scan of every add-on line ever written.
CREATE INDEX pledge_addons_reward_tier_idx ON pledge_addons (reward_tier_id, project_id);

COMMENT ON TABLE pledge_addons IS
    '§4.5''s PL-04: the add-on tiers a pledge selected and how many of each. The lines behind pledges.addons_amount.';

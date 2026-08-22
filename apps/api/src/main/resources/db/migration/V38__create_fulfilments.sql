-- §4.8's PM-20 to PM-22 (#80): where a backer's parcel is, imported in bulk by
-- the creator and read by both of them.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS fulfilments;
--   -- Costs every tracking number a creator has imported. Those numbers exist
--   -- in the carrier's system and in whatever spreadsheet the creator uploaded,
--   -- so unlike V36's addresses this is recoverable -- by asking the creator to
--   -- import the file again. Nothing else in the schema references the table.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- ONE ROW PER PLEDGE, KEYED BY THE PLEDGE
-- ---------------------------------------------------------------------------
--
-- The same shape as V36's `shipping_addresses` and for the same reason: there is
-- exactly one fulfilment per pledge and every read arrives holding the pledge, so
-- a surrogate key would create only the possibility of two.
--
-- **A split shipment is therefore not representable, and that is deliberate.** A
-- pledge whose reward ships in three parcels would need a row per parcel, a
-- status per parcel, and an answer to "what is the status of the pledge" that is
-- a fold over them. Nothing in §4.8 asks for it, PM-22's "fulfilment status" is
-- one status, and a table shaped for the case nobody has would make the case
-- everybody has -- one parcel, one number -- a join. When a campaign needs it,
-- the expand is a `parcels` table referencing this one, not a rewrite of it.
--
-- ---------------------------------------------------------------------------
-- WHY `project_id` IS HERE AT ALL
-- ---------------------------------------------------------------------------
--
-- It is denormalised from the pledge. It could be reached by a join every time,
-- and it is stored because the creator's list -- "every fulfilment on my
-- campaign" -- is the read this table exists to serve, and it would otherwise be
-- a join to `pledges` on every page. The composite foreign key below is what
-- stops the copy naming a different campaign than the pledge does.
--
-- ---------------------------------------------------------------------------
-- THE TWO TIMESTAMPS ARE FACTS ABOUT THE CURRENT STATUS
-- ---------------------------------------------------------------------------
--
-- `shipped_at` is set exactly when the status is not PREPARING, and
-- `delivered_at` exactly when it is DELIVERED. Both are check constraints rather
-- than a service convention, because the failure they prevent is a row that says
-- a parcel is still being packed and was delivered on Tuesday -- which a backer
-- reads as a delivery that did not happen.
--
-- The consequence is that **a correction erases the earlier claim**: a creator who
-- marks a pledge DELIVERED by mistake and puts it back to SHIPPED clears
-- `delivered_at`, because a delivery instant on a parcel that has not been
-- delivered is the contradiction the constraint exists to refuse. What survives
-- the correction is `audit_logs`, which records the import that made each claim.

CREATE TABLE fulfilments (
    -- The pledge is the identity. See the header. Its reference to `pledges` is
    -- the composite one below rather than an inline one here: two foreign keys
    -- to the same table would be two cascade paths to keep in step, and the
    -- composite is the one that carries the campaign check.
    pledge_id       uuid        PRIMARY KEY,
    project_id      uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    -- PM-22. Text with a check rather than a PostgreSQL enum type, like every
    -- other state column in this schema: adding a value to an enum type is a
    -- migration that cannot run inside a transaction on older servers, and the
    -- check gives the same refusal without that.
    --
    -- Four values and no more. RETURNED is not a failure to be folded into
    -- SHIPPED -- it is the one outcome a backer has to act on, and a creator who
    -- cannot record it answers the same email four hundred times.
    status          text        NOT NULL,
    -- Free text, because carriers are not a list this platform can hold: a
    -- creator in Baku ships with Azerpoct, one in Berlin with DHL, and one in
    -- Shenzhen with a freight forwarder whose name is a person's. A closed
    -- vocabulary here would mean a creator whose carrier is missing types the
    -- nearest wrong one.
    carrier         text,
    tracking_number text,
    -- Where the backer clicks. Optional and separate from the number, because a
    -- tracking URL is per carrier and often per parcel, and deriving one from the
    -- carrier name would mean a table of URL templates that is wrong the first
    -- time a carrier changes its site.
    tracking_url    text,
    shipped_at      timestamptz,
    delivered_at    timestamptz,
    -- Who last imported this row. Not an audit trail -- `audit_logs` is that --
    -- but the answer to "who set this" without a query across a table holding
    -- every privileged action on the platform.
    updated_by      uuid,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fulfilments_status_is_known
        CHECK (status IN ('PREPARING', 'SHIPPED', 'DELIVERED', 'RETURNED')),
    -- The pledge and the campaign have to be the pair the pledge itself carries,
    -- so an import cannot file another campaign's pledge under this one.
    CONSTRAINT fulfilments_pledge_project_fkey
        FOREIGN KEY (pledge_id, project_id) REFERENCES pledges (id, project_id) ON DELETE CASCADE,
    -- See the header: the timestamps are facts about the status, not a second
    -- opinion about it.
    CONSTRAINT fulfilments_shipped_at_matches_status
        CHECK ((status <> 'PREPARING') = (shipped_at IS NOT NULL)),
    CONSTRAINT fulfilments_delivered_at_matches_status
        CHECK ((status = 'DELIVERED') = (delivered_at IS NOT NULL)),
    -- A tracking number nobody can look up is worse than none: the backer reads
    -- it as something they can act on. The carrier is what makes it actionable,
    -- so the number requires one and not the other way round.
    CONSTRAINT fulfilments_tracking_number_names_a_carrier
        CHECK (tracking_number IS NULL OR carrier IS NOT NULL),
    CONSTRAINT fulfilments_carrier_length
        CHECK (carrier IS NULL OR length(btrim(carrier)) BETWEEN 1 AND 60),
    CONSTRAINT fulfilments_tracking_number_length
        CHECK (tracking_number IS NULL OR length(btrim(tracking_number)) BETWEEN 1 AND 64),
    -- https only. A tracking link is a URL a creator typed that several thousand
    -- backers click, and http would put the whole of that traffic on the wire.
    CONSTRAINT fulfilments_tracking_url_shape
        CHECK (tracking_url IS NULL OR (tracking_url ~ '^https://' AND length(tracking_url) <= 300))
);

-- The creator's list: every fulfilment on one campaign, oldest pledge first so
-- the page is stable while an import is running.
CREATE INDEX fulfilments_project_idx ON fulfilments (project_id, pledge_id);

-- "What is still not shipped", which is the question a creator asks of this table
-- more than any other. Partial, because a finished campaign's rows are almost all
-- DELIVERED and none of those is ever the answer.
CREATE INDEX fulfilments_project_open_idx
    ON fulfilments (project_id, pledge_id)
    WHERE status <> 'DELIVERED';

CREATE TRIGGER fulfilments_set_updated_at
    BEFORE UPDATE ON fulfilments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE fulfilments IS
    'PM-20 to PM-22 (#80): one parcel per pledge -- its status, its carrier, and its tracking number.';
COMMENT ON COLUMN fulfilments.status IS
    'PREPARING, SHIPPED, DELIVERED, RETURNED. The timestamps beside it are facts about this value, enforced by check.';

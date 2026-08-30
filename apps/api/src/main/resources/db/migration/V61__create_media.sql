-- §13.1's ingestion, for the two surfaces that need it — the media pipeline
-- design of 2026-08-30.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   ALTER TABLE projects
--       DROP CONSTRAINT IF EXISTS projects_cover_media_id_fkey,
--       DROP COLUMN IF EXISTS cover_media_id;
--   DROP TABLE IF EXISTS media;
--
--   Safe under a rolling deployment. `cover_media_id` is nullable with no
--   default and nothing joins on it: an instance running the previous release
--   reads `cover_image_url` and never looks at the column, which is the whole
--   reason those three columns are still here (see below). What is lost is the
--   association between a campaign and its uploaded file -- the object survives
--   in storage, orphaned, and would have to be re-attached by hand.
--
--   Reversing this while an instance of the *new* release is still serving is
--   the unsafe direction: that instance writes `cover_media_id` on every cover
--   change and would fail on a column that is no longer there. Drain first.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHY THERE IS A TABLE AT ALL, AFTER SIX MIGRATIONS SAID THERE WAS NOT
-- ---------------------------------------------------------------------------
--
-- V6, V7 and V14 each carry a comment saying there is no `media` table, no
-- uploader and no transcoding state, and each stores an image as a text URL a
-- creator typed. That was right while §13.1 was unbuilt: a foreign key to a
-- table with no rows in it is not an improvement over a URL.
--
-- What changed is that the checklist's cover minimum was blocking submission on
-- dimensions the *browser* reported. `SubmissionChecklist` says so in its own
-- header -- a client could claim any size it liked. The rule was therefore
-- strict against honest creators and inert against anybody else, and the only
-- way to make it a measurement is for the server to hold the file.
--
-- So this table exists to make one rule honest, and the upload it brings with
-- it is what stops creators having to go and host a photograph somewhere else
-- first.
--
-- ---------------------------------------------------------------------------
-- WHY THE THREE COLUMNS ON `projects` DO NOT GO
-- ---------------------------------------------------------------------------
--
-- `cover_image_url`, `cover_image_width` and `cover_image_height` stay, and a
-- reader prefers `cover_media_id` when it is set. §1 of CLAUDE.md: expand, then
-- contract, never both in one release. Between this migration and the deploy
-- that follows it, instances of two releases are serving at once and the older
-- one has never heard of `cover_media_id`.
--
-- Dropping them is a later migration, and it is not merely bookkeeping: every
-- campaign that exists today has a typed URL and no media row, so the contract
-- cannot happen until something has walked the table.
--
-- ---------------------------------------------------------------------------
-- WHY THE OWNER CASCADES
-- ---------------------------------------------------------------------------
--
-- `ON DELETE CASCADE` on `owner_user_id`, and it is not a preference about what
-- should happen to somebody's uploads when their account goes. The suites
-- truncate `users`; a foreign key with `NO ACTION` breaks roughly twenty tests
-- in suites that have nothing to do with media, and the failure surfaces three
-- frames away from anything that names this table.
--
-- The product answer happens to agree. A media row is a file somebody uploaded
-- and §17.4's minimisation says it goes with them. `projects` is different --
-- it soft-deletes, because financial records refer to it -- which is why
-- `cover_media_id` is `ON DELETE SET NULL` rather than cascading: a campaign
-- losing its cover is a campaign that renders without one, and a campaign
-- disappearing because an uploader closed their account would take a funding
-- record with it.
--
-- ---------------------------------------------------------------------------
-- WHY `status` IS TEXT WITH A CHECK AND NOT AN ENUM TYPE
-- ---------------------------------------------------------------------------
--
-- The same choice every other status column here makes. A PostgreSQL enum type
-- cannot have a value removed and adding one is a DDL statement that takes a
-- lock; a check constraint is replaced in a migration like anything else. The
-- five values are also a state machine the application owns, and duplicating it
-- as a database type would put the definition in two places that can disagree.

CREATE TABLE media (
    id             uuid        PRIMARY KEY,

    -- Who uploaded it. Not "what it belongs to": a media row is created before
    -- anybody has said where the image will be used, because the upload has to
    -- start before the creator has finished the form. Attachment is the other
    -- direction -- `projects.cover_media_id` points here.
    owner_user_id  uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    status         text        NOT NULL,

    -- The key of the *derived* object, not the raw upload. Null until processing
    -- has written one. The raw object is deleted at that point and its key is
    -- never recorded, because a key that is only ever used once inside one
    -- method is state that can go stale.
    storage_key    text,

    -- The type processing decided from the magic bytes, never the one the client
    -- declared. `image/jpeg` or `image/png`; those are the only two the pipeline
    -- writes, whatever it accepted as input.
    content_type   text,

    -- Of the derived object. The size of what was uploaded is not kept: it is
    -- interesting for about four seconds and then it is a number that describes
    -- a file that no longer exists.
    byte_size      bigint,

    -- Measured, not reported. This is the whole point of the table.
    width          int,
    height         int,

    -- §13.1's sixteen-pixel sample, base64 in a data URL, so a blur placeholder
    -- arrives in the same response as the image and costs no extra request.
    -- Sixteen pixels is small enough that no recognisable detail survives, which
    -- matters because a placeholder is shown before moderation has looked at
    -- anything.
    blur_data_url  text,

    -- Why processing gave up, as a machine-readable code the editor translates.
    -- Not a message: a message in a column is a message that cannot be
    -- translated, and this one is shown to a creator in their own language.
    failure_reason text,

    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT media_status_known
        CHECK (status IN ('PENDING', 'UPLOADED', 'PROCESSING', 'READY', 'FAILED')),

    -- Both or neither. A width without a height is a measurement that half
    -- happened, and it would reach a layout as a division by null.
    CONSTRAINT media_extent_is_complete
        CHECK ((width IS NULL) = (height IS NULL)),
    CONSTRAINT media_extent_is_positive
        CHECK (width IS NULL OR (width > 0 AND height > 0)),

    CONSTRAINT media_byte_size_positive
        CHECK (byte_size IS NULL OR byte_size > 0),

    -- A data URL, because the column is rendered straight into a `src`. The
    -- shape is checked here rather than only in the application for the reason
    -- every check in this schema exists: the application is one of the writers,
    -- and a backfill is another.
    CONSTRAINT media_blur_data_url_shape
        CHECK (blur_data_url IS NULL OR blur_data_url LIKE 'data:image/%;base64,%'),

    -- READY is a promise that the row can be served. Everything a renderer needs
    -- is present, or the row is not READY. Without this a processing step that
    -- failed halfway through leaves a row that looks finished and renders as a
    -- broken image on a campaign page.
    CONSTRAINT media_ready_is_servable
        CHECK (status <> 'READY' OR (
            storage_key IS NOT NULL
            AND content_type IS NOT NULL
            AND byte_size IS NOT NULL
            AND width IS NOT NULL
            AND blur_data_url IS NOT NULL)),

    -- A reason belongs to a failure. A row carrying one in any other state is a
    -- row whose status was overwritten without clearing it -- which is how a
    -- recovered upload keeps telling the creator it is broken.
    CONSTRAINT media_failure_reason_belongs_to_failure
        CHECK (failure_reason IS NULL OR status = 'FAILED')
);

-- What a creator's own media library reads, newest first.
CREATE INDEX media_owner_idx ON media (owner_user_id, created_at DESC);

-- The sweep that picks up work. Partial, because the rows that need attention
-- are a vanishing fraction of the table five minutes after they are written --
-- and a full index on `status` would be almost entirely READY.
CREATE INDEX media_awaiting_processing_idx
    ON media (created_at)
    WHERE status IN ('UPLOADED', 'PROCESSING');

-- The expand half. See the header for why the three text columns stay.
ALTER TABLE projects
    ADD COLUMN cover_media_id uuid REFERENCES media (id) ON DELETE SET NULL;

CREATE INDEX projects_cover_media_idx
    ON projects (cover_media_id)
    WHERE cover_media_id IS NOT NULL;

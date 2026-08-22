-- §4.8's PM-07 and PM-08 (#75): where a reward is posted, and the moment the
-- creator freezes that answer so they can print labels.
--
-- ---------------------------------------------------------------------------
-- ONE ROW PER PLEDGE, NOT PER ACCOUNT
-- ---------------------------------------------------------------------------
--
-- An address book on `users` was the obvious alternative and is wrong for this
-- platform. A backer supports three campaigns in a year and moves house between
-- the second and the third; the first two ship to where they lived when they
-- answered. An address attached to the account would silently rewrite the answer
-- they gave the earlier campaigns, and the creator who already printed a label
-- would never learn that the parcel is going to the wrong street.
--
-- Per pledge also makes PM-08's lock meaningful: one campaign freezing its
-- addresses must not freeze a backer's ability to answer a different campaign.
--
-- ---------------------------------------------------------------------------
-- ENCRYPTED AT REST, WITH APPLICATION-MANAGED KEYS
-- ---------------------------------------------------------------------------
--
-- §17.2 names this table specifically, and this is the table where the platform
-- holds the home addresses of everybody who ever backed anything. Disk
-- encryption does not answer it: a backup, a read replica, a `SELECT *` in a
-- support console and an SQL injection all see plaintext through it.
--
-- So the address arrives here as one AES-256-GCM ciphertext over the whole
-- structured document, and the key never goes near PostgreSQL. `pgcrypto` was
-- considered and rejected for exactly that reason -- `pgp_sym_encrypt` puts the
-- passphrase in the query text, which lands in `pg_stat_statements`, in the slow
-- query log, and in any statement log an operator turns on during an incident.
--
-- What that costs is stated rather than discovered: **nothing in this table can
-- be searched, sorted or filtered by the database.** No index on postcode, no
-- "backers in Berlin" query, no fuzzy duplicate detection. The two things the
-- platform genuinely needs to query on are kept outside the envelope --
--
--   * the destination country, which already lives on `pledges.shipping_country`
--     because §4.5's PL-05 prices shipping from it, and is not repeated here;
--   * whether an address has been given at all, which is the existence of the
--     row.
--
-- -- and everything else is opaque. CD-11's export decrypts row by row in the
-- service, which is why that export is audited and rate limited.
--
-- ---------------------------------------------------------------------------
-- `key_id` IS WHY ROTATION IS POSSIBLE
-- ---------------------------------------------------------------------------
--
-- Each row records which key encrypted it. Without it, rotating the key means
-- decrypting and rewriting every row in one transaction before anything can
-- start, and a failure halfway through leaves a table nobody can read. With it,
-- a deployment configures the new key as primary, keeps the old one available
-- for decryption, and rows migrate as they are touched.
--
-- It is a short opaque label rather than the key itself or a hash of it: a hash
-- would be a fixed value an attacker with the ciphertext could grind against.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS shipping_addresses;
--   -- After a campaign has surveyed its backers this is the only record of
--   -- where several thousand parcels are meant to go, and the ciphertext cannot
--   -- be reconstructed from anything else. Reversing means asking every backer
--   -- for their address again. For a database that has served no traffic.
-- ---------------------------------------------------------------------------

CREATE TABLE shipping_addresses (
    -- The pledge is the identity of the row, so there is no separate id: there
    -- is exactly one address per pledge and every read arrives holding the
    -- pledge. A surrogate key would only create the possibility of two.
    pledge_id   uuid        PRIMARY KEY REFERENCES pledges (id) ON DELETE CASCADE,
    -- Carried so the creator's fulfilment reads do not have to join through
    -- `pledges`, and so PM-08's lock can be applied per campaign in one
    -- statement. Denormalised and never edited -- a pledge does not change
    -- campaign.
    project_id  uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    -- Who gave it. No ON DELETE, for `saves.user_id`'s reason: §17.4 anonymises
    -- in place, and this row is then erased by the anonymiser rather than
    -- orphaned by a cascade nobody audited.
    backer_id   uuid        NOT NULL REFERENCES users (id),

    -- The whole structured address -- name, lines, city, region, postcode,
    -- country, phone -- as one AES-256-GCM ciphertext. One envelope rather than
    -- a column per field because the fields are only ever read together, and
    -- seven ciphertexts would leak seven lengths where one leaks one.
    ciphertext  bytea       NOT NULL,
    -- The GCM nonce, stored beside the ciphertext rather than prepended to it,
    -- so that a reader of the schema can see it exists and a reader of the code
    -- cannot forget to split it off. Twelve bytes, which is what GCM is
    -- specified for; anything else forces an extra hash inside the cipher.
    nonce       bytea       NOT NULL,
    -- Which key. See the header: this column is the whole of key rotation.
    key_id      text        NOT NULL,

    -- PM-08. NULL means the backer may still edit. Set by the creator when they
    -- start manufacturing, and it is a per-address instant rather than a flag on
    -- the campaign so that a creator can unlock one backer who wrote in.
    locked_at   timestamptz,
    -- Who locked it, for the support conversation that begins "I can't change my
    -- address". Whole or absent together with the instant.
    locked_by   uuid        REFERENCES users (id),

    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT shipping_addresses_lock_is_whole CHECK ((locked_at IS NULL) = (locked_by IS NULL)),
    -- A zero-length ciphertext is an encryption that produced nothing, which
    -- would decrypt to an address that looks deliberately empty.
    CONSTRAINT shipping_addresses_ciphertext_is_present CHECK (length(ciphertext) > 0),
    -- GCM's specified nonce length. A row with a different one was written by
    -- something that is not this application.
    CONSTRAINT shipping_addresses_nonce_length CHECK (length(nonce) = 12),
    CONSTRAINT shipping_addresses_key_id_shape CHECK (key_id ~ '^[a-z0-9][a-z0-9._-]{0,62}$')
);

-- The creator's fulfilment read: every address on this campaign, and the bulk
-- lock. Partial on the lock for the sweep that answers "how many are still
-- editable", which is the number a creator watches before they order.
CREATE INDEX shipping_addresses_project_idx ON shipping_addresses (project_id);
CREATE INDEX shipping_addresses_unlocked_idx
    ON shipping_addresses (project_id) WHERE locked_at IS NULL;

-- §17.4's erasure, which reaches this table by account rather than by campaign.
CREATE INDEX shipping_addresses_backer_idx ON shipping_addresses (backer_id);

-- V10's convention. Here it matters more than usual: `updated_at` is the only
-- unencrypted evidence that an address changed, and a creator who has already
-- printed a label reads it to find out whether they have to reprint.
CREATE TRIGGER shipping_addresses_set_updated_at
    BEFORE UPDATE ON shipping_addresses
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE shipping_addresses IS
    'PM-07: where one pledge''s reward is posted. Encrypted at rest with an application-managed key; nothing in the envelope is queryable.';
COMMENT ON COLUMN shipping_addresses.key_id IS
    'Which application key encrypted this row. Rotation configures a new primary key and keeps the old one readable; rows migrate as they are written.';
COMMENT ON COLUMN shipping_addresses.locked_at IS
    'PM-08. NULL means the backer may still edit. Per address rather than per campaign so a creator can reopen one backer.';

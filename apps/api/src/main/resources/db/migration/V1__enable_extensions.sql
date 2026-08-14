-- Extensions the schema depends on. Enabling them is the first migration
-- because a later migration that uses citext or gen_random_uuid() would
-- otherwise fail on a database where nobody had run these by hand.
--
-- Reverse:
--   DROP EXTENSION IF EXISTS pg_trgm;
--   DROP EXTENSION IF EXISTS pgcrypto;
--   DROP EXTENSION IF EXISTS citext;

-- Case-insensitive text. Email addresses are compared, and stored, as one
-- value regardless of how the person typed them; doing that with lower() in
-- every query is one forgotten call away from two accounts for one person.
CREATE EXTENSION IF NOT EXISTS citext;

-- gen_random_uuid() and the digest functions. Used for identifiers and for
-- hashing opaque tokens before they are stored.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Trigram indexes for fuzzy matching and for LIKE '%...%' predicates that a
-- B-tree cannot serve. Search is built on it.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

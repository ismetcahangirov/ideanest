-- §4.2's P-07 (#274): whether an account has a public profile at all.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   ALTER TABLE users DROP COLUMN profile_visibility;
--   -- Every account becomes publicly visible again, including the ones that had
--   -- chosen otherwise. That is a privacy regression rather than a data loss,
--   -- which is why the reverse is here and not something to run casually: the
--   -- column is the only record of the choice.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHY A COLUMN AND NOT A ROW SOMEWHERE ELSE
-- ---------------------------------------------------------------------------
--
-- Every read of a public profile asks this question, and every one of them has
-- already loaded the `users` row to answer any of the others. A preferences
-- table would add a join to the platform's most-read profile query in order to
-- store one enum, and a missing row there would have to mean something -- which
-- is a default expressed as an absence, and the kind of thing that is read as
-- PUBLIC by one caller and PRIVATE by the next.
--
-- ---------------------------------------------------------------------------
-- WHY THE DEFAULT IS PUBLIC
-- ---------------------------------------------------------------------------
--
-- Not because public is the safer default -- it is not -- but because it is the
-- one that is already true. Every account on this platform has had a slug in a
-- URL since V2, `GET /v1/projects/{creatorSlug}/{projectSlug}` publishes the
-- creator's name and avatar on every campaign page, and #90 lets anyone follow
-- an account by slug. Backfilling PRIVATE would claim to have hidden something
-- that has been published all along, and would silently break the follow button
-- on every creator.
--
-- What the column adds is the ability to *choose*, and the choice it protects is
-- the one §4.2 actually names: the backed-projects archive and the about tab,
-- which are new surfaces and have never been public.
--
-- ---------------------------------------------------------------------------
-- WHY TEXT WITH A CHECK, AND NOT AN ENUM TYPE
-- ---------------------------------------------------------------------------
--
-- The convention this schema already follows for `projects.state`, `pledges.state`
-- and every other closed set: a PostgreSQL enum type cannot have a value removed
-- and reorders awkwardly, and adding one takes a lock that a text column with a
-- check constraint does not. Hibernate maps both the same way.

ALTER TABLE users
    ADD COLUMN profile_visibility text NOT NULL DEFAULT 'PUBLIC';

ALTER TABLE users
    ADD CONSTRAINT users_profile_visibility_is_known
        CHECK (profile_visibility IN ('PUBLIC', 'PRIVATE'));

COMMENT ON COLUMN users.profile_visibility IS
    'P-07 (#274). PUBLIC by default because every account already had a public slug; PRIVATE hides the profile page.';

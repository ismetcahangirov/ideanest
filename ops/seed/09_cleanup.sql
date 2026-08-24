-- Removes what the seed needed while it ran and nothing else.
--
-- The helper functions are seed machinery, not schema. Leaving them behind
-- means the next person to read \df on a local database finds four functions
-- that no migration created and no code calls.

-- The account registered through /v1/auth/register to obtain an Argon2id hash
-- this service's own verifier accepts. Its only purpose was that hash, which is
-- now in 00_helpers.sql.
DELETE FROM users WHERE email = 'seed-probe@ideanest.local';

DROP FUNCTION IF EXISTS seed_story(text, text, text, text, text, text, text, text[]);
DROP FUNCTION IF EXISTS seed_photo(text, int, int);
DROP FUNCTION IF EXISTS seed_rand(text);
DROP FUNCTION IF EXISTS seed_subcategory(text, int);
DROP FUNCTION IF EXISTS seed_category(text);
DROP FUNCTION IF EXISTS seed_location(text);
DROP FUNCTION IF EXISTS seed_password();
DROP FUNCTION IF EXISTS seed_id(text);

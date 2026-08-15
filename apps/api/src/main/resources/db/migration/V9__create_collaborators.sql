-- Collaborators: the people a creator invites onto a campaign, and exactly what
-- each of them may do.
--
-- One row is an invitation and a grant at the same time, because they are the
-- same thing seen at two moments: an address was invited with a set of
-- capabilities, and once that address accepted, the same row is what authorises
-- the account behind it. Splitting them into `invitations` and `collaborators`
-- was considered and rejected -- the second table would have to copy the grant
-- set from the first, and the copy is what eventually disagrees.
--
-- The creator is deliberately not in here. Their authority comes from
-- `projects.creator_id` and is implicit, so a creator is never "a collaborator
-- who happens to hold everything": a row that could be revoked would be a way
-- to lock a creator out of their own campaign, and a row that could be edited
-- would be a way to take a capability away from the person who owns it.
--
-- Reverse:
--   DROP TABLE IF EXISTS collaborator_capabilities;
--   DROP TABLE IF EXISTS collaborators;
--   -- Reversing discards every grant and every outstanding invitation. Nothing
--   -- else references these tables, so the way back from a bad release is the
--   -- previous build of the application plus this block -- and the invitation
--   -- links already sent stop working, because their hashes go with the rows.

-- ---------------------------------------------------------------------------
-- collaborators
-- ---------------------------------------------------------------------------

CREATE TABLE collaborators (
    id                    uuid        PRIMARY KEY,
    -- Cascades: a hard-deleted campaign is one that never launched, and a grant
    -- on a campaign that does not exist authorises nothing.
    project_id            uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    -- Null until the invitation is accepted, and that is the whole point of the
    -- invited address below: an invitation to somebody who has no account here
    -- is legal, and is claimed when that person registers and follows the link.
    -- No ON DELETE clause, for the reason `projects.creator_id` has none: §17.4
    -- closes an account by anonymising the person in place, which leaves this
    -- reference intact.
    account_id            uuid        REFERENCES users (id),
    -- citext, so that Person@Example.com and person@example.com are one
    -- invitation. The same decision as `users.email`, and it has to be the same
    -- one: acceptance compares this against the accepting account's address.
    invited_email         citext      NOT NULL,
    -- SHA-256 of the token in the invitation link, never the token itself. The
    -- `verification_tokens` reasoning applies unchanged: whoever can read this
    -- table must not be able to use what they find, and a 256-bit value we
    -- generated needs no salt and no work factor because there is no dictionary
    -- to attack it with.
    invitation_token_hash bytea       NOT NULL,
    -- Who issued it. Recorded because a grant is a privileged action: "who let
    -- this person see the finances" has to be answerable later, and only the
    -- inviter's own capabilities bounded what they were able to confer.
    invited_by            uuid        NOT NULL REFERENCES users (id),
    -- Written by the application from its own clock, like `verification_tokens`,
    -- so that the expiry check below compares two timestamps from one source.
    -- The default is a backstop for a hand-written INSERT, not the normal path.
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    -- An invitation is not a standing offer. An address left unaccepted for a
    -- week is usually a typo or somebody who left the company, and a link that
    -- works forever is a key to a campaign sitting in an old mailbox.
    expires_at            timestamptz NOT NULL,
    -- Single use, spent by setting this rather than by deleting the row -- the
    -- reason `verification_tokens` gives: a second attempt with the same link
    -- can then be told apart from a token that never existed, and one of those
    -- is a person double-clicking while the other is somebody guessing.
    accepted_at           timestamptz,
    revoked_at            timestamptz,
    revoked_by            uuid        REFERENCES users (id),

    -- The same loose shape as `users.email_shape`. The real test of an address
    -- is whether the invitation arrives at it.
    CONSTRAINT collaborators_email_shape CHECK (
        invited_email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'
    ),
    CONSTRAINT collaborators_email_length CHECK (length(invited_email::text) BETWEEN 3 AND 254),
    CONSTRAINT collaborators_token_hash_is_sha256 CHECK (octet_length(invitation_token_hash) = 32),
    CONSTRAINT collaborators_expiry_after_creation CHECK (expires_at > created_at),
    -- An accepted invitation names the account that accepted it, and a pending
    -- one has no account at all. Stated as an equivalence rather than as two
    -- one-way checks, in the voice of `sessions_revocation_is_complete`: the
    -- states that are incoherent are "accepted by nobody" -- a grant that
    -- authorises no account and yet counts as accepted -- and "an account
    -- attached to an invitation nobody has accepted", which would authorise
    -- somebody who never clicked anything.
    CONSTRAINT collaborators_acceptance_names_the_account CHECK (
        (accepted_at IS NULL) = (account_id IS NULL)
    ),
    -- A revoked grant says who revoked it. Same shape, same reason: withdrawing
    -- somebody's access to an unlaunched campaign is a privileged action, and
    -- "access was removed and nobody recorded by whom" is the state that makes
    -- the decision unreviewable afterwards.
    CONSTRAINT collaborators_revocation_is_complete CHECK (
        (revoked_at IS NULL) = (revoked_by IS NULL)
    ),
    CONSTRAINT collaborators_acceptance_follows_the_invitation CHECK (
        accepted_at IS NULL OR accepted_at >= created_at
    ),
    CONSTRAINT collaborators_revocation_follows_the_invitation CHECK (
        revoked_at IS NULL OR revoked_at >= created_at
    )
);

-- The lookup on acceptance, and the reason a stolen link is worth nothing twice:
-- unique, so one hash is one invitation.
CREATE UNIQUE INDEX collaborators_invitation_token_hash_key
    ON collaborators (invitation_token_hash);

-- One live invitation or grant per address per campaign. Partial, because a
-- revoked row stays -- history -- and the same person may be invited again
-- afterwards. Without this, inviting an address twice would produce two grants
-- and revoking one of them would silently leave the other in force.
CREATE UNIQUE INDEX collaborators_live_invitation_key
    ON collaborators (project_id, invited_email)
    WHERE revoked_at IS NULL;

-- And the same rule seen from the account, which is what an authorisation check
-- looks up. Two accepted rows for one account would make "what may this person
-- do here" depend on which row was read.
CREATE UNIQUE INDEX collaborators_live_account_key
    ON collaborators (project_id, account_id)
    WHERE account_id IS NOT NULL AND revoked_at IS NULL;

-- The authorisation query: every editor request by a collaborator runs it, so it
-- is indexed on exactly the rows that can authorise anything.
CREATE INDEX collaborators_active_grant_idx
    ON collaborators (account_id, project_id)
    WHERE accepted_at IS NOT NULL AND revoked_at IS NULL;

-- The People tab: everyone on this campaign, including the invitations nobody
-- has accepted yet and the grants that were withdrawn.
CREATE INDEX collaborators_project_idx ON collaborators (project_id, created_at);

CREATE TRIGGER collaborators_set_updated_at
    BEFORE UPDATE ON collaborators
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE collaborators IS
    'Invitations and the grants they become. The creator is not in here; their authority is implicit.';
COMMENT ON COLUMN collaborators.invitation_token_hash IS
    'SHA-256 of the invitation token. The token itself exists only in the message that was sent.';
COMMENT ON COLUMN collaborators.accepted_at IS
    'Single use. Spent by setting this, never by deleting the row.';

-- ---------------------------------------------------------------------------
-- collaborator_capabilities
-- ---------------------------------------------------------------------------

-- The grant set, one capability per row rather than an array column.
--
-- An array would have let the database check that a grant confers at least one
-- capability, which this shape cannot: a per-row check cannot see the absence of
-- sibling rows, and the alternatives are a trigger or a deferred constraint.
-- Rows won anyway, for two reasons. "Who can see this campaign's finances" is a
-- question with an index behind it here and a sequential scan behind an array;
-- and `text[]` columns are the one shape Hibernate's schema validation has no
-- settled mapping for, which would have made the choice visible as a start-up
-- failure rather than as a design decision.
--
-- The empty-set rule is therefore enforced in Java, where CollaboratorService
-- refuses it with a 400 that names the field. That is the one rule in this
-- migration that the database does not also hold.
CREATE TABLE collaborator_capabilities (
    collaborator_id uuid NOT NULL REFERENCES collaborators (id) ON DELETE CASCADE,
    capability      text NOT NULL,

    -- The pair is the identity: granting the same capability twice is not a
    -- stronger grant, and a duplicate row would make a capability count.
    CONSTRAINT collaborator_capabilities_pkey PRIMARY KEY (collaborator_id, capability),
    -- The eight capabilities of the campaign editor's People tab, and nothing
    -- else. A capability nobody implemented is a grant that silently authorises
    -- nothing, which reads to a creator as the platform ignoring them.
    CONSTRAINT collaborator_capabilities_known CHECK (
        capability IN (
            'EDIT_BASICS',           -- title, summary, category, goal, duration
            'EDIT_REWARDS',          -- items, tiers, shipping
            'EDIT_STORY',            -- the story document and the risks section
            'SUBMIT_FOR_REVIEW',     -- send the campaign to moderation
            'PUBLISH_UPDATES',       -- post updates to backers
            'RESPOND_TO_COMMENTS',   -- reply as the campaign
            'VIEW_FINANCES',         -- the backer report and the money in it
            'MANAGE_COLLABORATORS'   -- invite and revoke; only a creator may grant this
        )
    )
);

-- "Which campaigns may this capability be exercised on" -- the reverse of the
-- membership lookup, and what a future audit of a sensitive capability
-- (VIEW_FINANCES) is asked for.
CREATE INDEX collaborator_capabilities_capability_idx
    ON collaborator_capabilities (capability, collaborator_id);

COMMENT ON TABLE collaborator_capabilities IS
    'One row per granted capability. Names match project.domain.Capability exactly.';

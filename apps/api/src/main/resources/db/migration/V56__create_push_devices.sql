-- §4.10's push column, and the thing it has always been missing: somewhere to
-- send it. Issue #87.
--
-- ---------------------------------------------------------------------------
-- Reverse:
--   DROP TABLE IF EXISTS push_devices;
--
--   Nothing else refers to it. What is lost is every registration, so every
--   phone has to be opened once before it receives anything again -- which is
--   recoverable, unlike anything in `transactions`, and is the reason this table
--   is allowed to be dropped at all.
-- ---------------------------------------------------------------------------
--
-- ---------------------------------------------------------------------------
-- WHY THE TOKEN IS THE NATURAL KEY AND `id` IS STILL A uuid
-- ---------------------------------------------------------------------------
--
-- An Expo push token identifies an installation, not a person and not a device:
-- reinstalling the application produces a new one, and a phone handed to
-- somebody else keeps the old one until it does. So the token is what a send
-- addresses and it is what a registration collides on -- hence the unique index.
--
-- The row still has a surrogate key, because the token is the one column most
-- likely to need rotating and a foreign key onto a value the client controls is
-- a foreign key that changes. Nothing references this table today; giving it an
-- `id` now is cheaper than adding one to a table full of rows later.
--
-- ---------------------------------------------------------------------------
-- WHY A TOKEN MOVES BETWEEN ACCOUNTS RATHER THAN BEING DUPLICATED
-- ---------------------------------------------------------------------------
--
-- Two people can sign into one phone. If both registrations were kept, the
-- second person's pledge confirmation would arrive on a device the first person
-- is holding -- which is a disclosure, not a duplicate.
--
-- The unique index on `token` is what makes the fix expressible: registration is
-- an upsert that rewrites `user_id`, so a token belongs to whoever signed in
-- most recently and to nobody else. `PushDevices` carries the same argument on
-- the Java side.
--
-- ---------------------------------------------------------------------------
-- WHY `last_seen_at` AND NOT A `revoked_at`
-- ---------------------------------------------------------------------------
--
-- A device stops being reachable in three ways and only one of them tells us:
-- the person signs out (we are told), they uninstall (we are told, but not until
-- a send is refused with `DeviceNotRegistered`), or the phone is simply never
-- opened again (nobody tells us, ever).
--
-- Sign-out and a refused send both delete the row, because a token that cannot
-- receive is not a state worth keeping -- the next sign-in registers again.
-- `last_seen_at` covers the third: a registration that has not been refreshed in
-- months is one nothing has confirmed, and §17.4's minimisation says an address
-- nobody has confirmed in a year is an address to stop keeping.

CREATE TABLE push_devices (
    id uuid PRIMARY KEY,

    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    -- The Expo push token: `ExponentPushToken[...]` or `ExpoPushToken[...]`.
    -- Shape-checked here as well as in `PushDevices`, because a malformed token
    -- is refused by Expo for the whole batch it is in -- so one bad row would
    -- stop everybody else's notification, and the cheapest place to refuse it is
    -- before it is stored.
    token text NOT NULL
        CONSTRAINT push_devices_token_shape CHECK (
            token ~ '^Expo(nent)?PushToken\[[A-Za-z0-9_-]{1,128}\]$'),

    -- Which store the installation came from. Not used for routing -- Expo hides
    -- that -- and kept because a push that is failing on one platform only is
    -- the single most common shape of push incident, and it is unanswerable
    -- without this column.
    platform text NOT NULL
        CONSTRAINT push_devices_platform_known CHECK (platform IN ('IOS', 'ANDROID')),

    -- What the phone calls itself, for the sessions screen. Free text from the
    -- client, bounded so that it cannot be used as storage.
    device_name text
        CONSTRAINT push_devices_name_is_short CHECK (device_name IS NULL OR length(device_name) <= 120),

    -- The application build that registered. A push that renders wrongly is
    -- almost always a build that is older than the payload; without this the
    -- question "which versions are affected" has no answer.
    app_version text
        CONSTRAINT push_devices_version_is_short CHECK (app_version IS NULL OR length(app_version) <= 40),

    created_at timestamptz NOT NULL DEFAULT now(),

    -- Rewritten on every registration. See the header.
    last_seen_at timestamptz NOT NULL DEFAULT now(),

    -- One row per installation. This is the constraint that makes a token belong
    -- to one account rather than to several.
    CONSTRAINT push_devices_token_is_unique UNIQUE (token)
);

-- The send path: every reachable installation for one recipient. This is the
-- only query the sender makes, and it makes one per notification.
CREATE INDEX push_devices_user_idx ON push_devices (user_id);

-- The retention sweep: registrations nobody has refreshed. Ordered so the sweep
-- reads a range rather than the table.
CREATE INDEX push_devices_last_seen_idx ON push_devices (last_seen_at);

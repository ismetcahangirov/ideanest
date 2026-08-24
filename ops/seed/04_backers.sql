-- Nine hundred backer accounts, so that a campaign can plausibly be at 140% of
-- a 25 000 AZN goal.
--
-- A crowdfunding platform seeded with eight backers looks like a crowdfunding
-- platform with eight backers: every progress bar is a sliver, the backer list
-- on a dashboard fits on one screen, and the admin user table has nothing to
-- paginate. The volume is the point.
--
-- Names are assembled from two lists rather than generated as noise, because
-- "Aygün Rzayeva" reads as a person on a backer list and "user-412" does not.
-- The ASCII column exists because users.slug refuses anything outside
-- [a-z0-9-], and Azerbaijani orthography is mostly outside it.

INSERT INTO users (id, email, email_verified_at, name, slug, avatar_url, locale, currency,
                   profile_visibility, location_id, created_at)
SELECT
    seed_id('backer:' || n.n),
    'backer' || n.n || '@example.az',
    -- A tenth of the population never confirmed their address, which is what
    -- makes the admin user filter for it worth having.
    CASE WHEN n.n % 10 = 0 THEN NULL ELSE now() - (n.n || ' hours')::interval END,
    f.display || ' ' || s.display,
    f.ascii || '-' || s.ascii || '-' || n.n,
    -- Two thirds carry a picture. An avatar column that is never null hides the
    -- fallback initials every list has to render.
    CASE WHEN n.n % 3 = 0 THEN NULL
         ELSE 'https://i.pravatar.cc/300?img=' || (1 + (n.n % 70)) END,
    (ARRAY['az', 'az', 'az', 'az', 'en', 'ru', 'tr'])[1 + (n.n % 7)],
    'AZN',
    CASE WHEN n.n % 12 = 0 THEN 'PRIVATE' ELSE 'PUBLIC' END,
    (SELECT id FROM locations ORDER BY slug OFFSET (n.n % 18) LIMIT 1),
    now() - ((n.n % 300) + 1 || ' days')::interval
FROM generate_series(1, 900) AS n(n)
CROSS JOIN LATERAL (
    SELECT display, ascii FROM (VALUES
        ('Aysel','aysel'),      ('Orxan','orxan'),     ('Günel','gunel'),    ('Tural','tural'),
        ('Sevinc','sevinc'),    ('Ramin','ramin'),     ('Nurlan','nurlan'),  ('Aygün','aygun'),
        ('Emin','emin'),        ('Lalə','lala'),       ('Fərid','farid'),    ('Zaur','zaur'),
        ('Nəzrin','nazrin'),    ('Samir','samir'),     ('Vüsal','vusal'),    ('Kamran','kamran'),
        ('Nigar','nigar'),      ('Elvin','elvin'),     ('Rəşad','rashad'),   ('Leyla','leyla'),
        ('Murad','murad'),      ('Şəbnəm','shabnam'),  ('İlkin','ilkin'),    ('Xəyalə','xayala'),
        ('Ceyhun','ceyhun'),    ('Ülviyyə','ulviyya'), ('Anar','anar'),      ('Mehriban','mehriban'),
        ('Rüfət','rufat'),      ('Türkan','turkan')
    ) AS t(display, ascii) OFFSET (n.n % 30) LIMIT 1
) AS f
CROSS JOIN LATERAL (
    SELECT display, ascii FROM (VALUES
        ('Məmmədov','mammadov'),   ('Əliyev','aliyev'),      ('Həsənov','hasanov'),
        ('Quliyev','quliyev'),     ('Rzayev','rzayev'),       ('İsmayılov','ismayilov'),
        ('Bayramov','bayramov'),   ('Cəfərov','jafarov'),     ('Salmanov','salmanov'),
        ('Muradov','muradov'),     ('Qasımov','qasimov'),     ('Vəliyev','valiyev'),
        ('Bağırov','bagirov'),     ('Nəbiyev','nabiyev'),     ('Səfərov','safarov'),
        ('Abbasov','abbasov'),     ('Kərimov','karimov'),     ('Şirinov','shirinov'),
        ('Tağıyev','tagiyev'),     ('Hüseynov','huseynov'),   ('Axundov','axundov'),
        ('Nəsirov','nasirov'),     ('Zeynalov','zeynalov'),   ('Mirzəyev','mirzayev'),
        ('Rəhimov','rahimov')
    ) AS t(display, ascii) OFFSET ((n.n * 7) % 25) LIMIT 1
) AS s
ON CONFLICT (id) DO NOTHING;

INSERT INTO user_credentials (user_id, password_hash, password_changed_at, created_at)
SELECT u.id, seed_password(), u.created_at, u.created_at
FROM users u
ON CONFLICT (user_id) DO NOTHING;

-- A handful of these accounts have asked to be deleted and are inside the
-- retention window, which is the state the account admin screen has to show.
UPDATE users SET
    deletion_requested_at = now() - interval '9 days',
    deletion_scheduled_at = now() + interval '21 days'
WHERE id IN (seed_id('backer:37'), seed_id('backer:214'), seed_id('backer:658'));

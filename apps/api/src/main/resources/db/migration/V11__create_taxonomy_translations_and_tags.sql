-- The discovery taxonomy: a category tree that is translated rather than
-- bilingual, and the flat tag vocabulary that sits beside it.
--
-- Two changes that belong to one design. §4.3 asks for fifteen categories with
-- "a translation per supported locale", and §21.1 names four locales -- az, en,
-- ru, tr. `categories.name_az` and `categories.name_en` can express two of
-- those, and the third and fourth would each be a schema change plus a
-- deployment, which is the opposite of what §4.3 asks for ("the taxonomy is
-- data, not code: it must be editable without a deployment"). Translations
-- therefore become rows. Tags arrive in the same migration because §4.3 lists
-- them as a discovery filter alongside the tree and they are the other half of
-- how a campaign is found; splitting them would leave two migrations that only
-- make sense read together.
--
-- THIS IS THE EXPAND HALF. `categories.name_az`, `categories.name_en`,
-- `subcategories.name_az`, and `subcategories.name_en` all stay, are still
-- written by the seed below, and are still read by `GET /v1/categories` (which
-- returns them alongside the new `name` and `names` fields for one release,
-- because apps/web deploys separately from the API). The contract half is a
-- later migration that drops the four columns; its precondition is that no
-- reader is left -- specifically that `CategoryController` has stopped
-- returning `nameAz`/`nameEn`, that `Category`/`Subcategory` no longer map the
-- columns, and that a web release reading only `name` has been live long
-- enough that no cached bundle still asks for the aliases.
--
-- Reverse:
--   DELETE FROM project_tags;
--   DROP TABLE IF EXISTS project_tags;
--   DROP TABLE IF EXISTS tags;
--   DROP TABLE IF EXISTS subcategory_translations;
--   DROP TABLE IF EXISTS category_translations;
--   -- and then the taxonomy itself, which this migration reshaped in place:
--   -- the six categories it added, the ninety-odd subcategories it added, and
--   -- the `community` category and two subcategories it removed. Those are not
--   -- recoverable from here. The rows are seed data rather than anybody's
--   -- work, so the way back is to restore them from V6 and re-file by hand the
--   -- campaigns this migration unfiled -- see the remapping section, which
--   -- names exactly which campaigns those are. As everywhere else in this
--   -- directory, the way back from a bad release is the previous build of the
--   -- application, not this block.

-- ---------------------------------------------------------------------------
-- category_translations and subcategory_translations
-- ---------------------------------------------------------------------------

-- One row per category per locale. The name is the only translatable thing on
-- a category: the slug is the stable handle (it is in the URL and in every
-- fixture) and must not move with the language, and `sort_order` is a platform
-- decision rather than a linguistic one -- see the comment V6 left on it.
--
-- No surrogate key. The identity of a translation is the thing it translates
-- and the language it translates it into, and a generated id would only give
-- the table a second way to say the same thing -- plus a way to write two rows
-- for one pair. Same shape and same reasoning as `collaborator_capabilities`.
CREATE TABLE category_translations (
    category_id uuid        NOT NULL REFERENCES categories (id) ON DELETE CASCADE,
    -- The four codes of §21.1 and nothing else. A text column with a check
    -- rather than a native enum, for the reason `projects_state_known` gives:
    -- adding `tr` in phase 3 is then an ordinary migration that runs inside a
    -- transaction, whereas ALTER TYPE ... ADD VALUE cannot be used by the same
    -- transaction that adds it -- and an enum's declaration order silently
    -- becomes the sort order of every query that orders by locale.
    --
    -- Bare language codes, not BCP 47 tags: the platform ships one Azerbaijani
    -- and one Russian, not az-Latn-AZ and ru-RU, and a column that accepted
    -- both forms would eventually hold both for one language.
    locale      text        NOT NULL,
    name        text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),

    -- The pair is the identity: one name per category per language. Two rows
    -- would make the displayed name depend on which the planner read first.
    CONSTRAINT category_translations_pkey PRIMARY KEY (category_id, locale),
    CONSTRAINT category_translations_locale_known CHECK (
        locale IN (
            'az',  -- primary; every category and subcategory has one
            'en',  -- phase 1
            'ru',  -- phase 1
            'tr'   -- phase 3
        )
    ),
    -- Trimmed, so a name of nothing but spaces is not a name -- and bounded,
    -- because a category label longer than this is not going to fit in the
    -- navigation it exists for.
    CONSTRAINT category_translations_name_length CHECK (length(btrim(name)) BETWEEN 1 AND 80)
);

COMMENT ON TABLE category_translations IS
    'One name per category per locale. Every category has an az row; the fallback chain is in Taxonomy.';

CREATE TRIGGER category_translations_set_updated_at
    BEFORE UPDATE ON category_translations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE subcategory_translations (
    subcategory_id uuid        NOT NULL REFERENCES subcategories (id) ON DELETE CASCADE,
    locale         text        NOT NULL,
    name           text        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT subcategory_translations_pkey PRIMARY KEY (subcategory_id, locale),
    CONSTRAINT subcategory_translations_locale_known CHECK (
        locale IN ('az', 'en', 'ru', 'tr')
    ),
    CONSTRAINT subcategory_translations_name_length CHECK (length(btrim(name)) BETWEEN 1 AND 80)
);

COMMENT ON TABLE subcategory_translations IS
    'One name per subcategory per locale. Same az rule and same fallback as category_translations.';

CREATE TRIGGER subcategory_translations_set_updated_at
    BEFORE UPDATE ON subcategory_translations
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- WHY THE `az` RULE IS NOT A CONSTRAINT.
--
-- Every category and every subcategory must have an `az` row, because §21.1
-- makes Azerbaijani the primary language and a taxon with no Azerbaijani name
-- is a navigation item that renders as a slug to the platform's largest
-- audience. The database cannot say that here.
--
--   * NOT NULL constrains a column within a row. "At least one row of a given
--     locale exists for this parent" is a statement about a set of sibling
--     rows, which no per-row CHECK can see.
--   * A partial unique index -- UNIQUE (category_id) WHERE locale = 'az' --
--     forbids a second Azerbaijani name. It cannot require a first one; an
--     index constrains the rows that exist, never the rows that do not.
--   * What is left is a deferred constraint trigger on both tables plus one on
--     `categories` itself, firing on every insert and delete, to enforce a rule
--     that is violated only by hand-editing seed data. A trigger nobody can
--     read at the point they break the rule is worse than a documented
--     invariant plus a test, and this migration's seed is checked by
--     `TaxonomySchemaTests` exactly for this.
--
-- SO THE INVARIANT IS HELD IN THREE PLACES INSTEAD. The seed below writes an
-- `az` row for all fifteen categories and all of their subcategories; the
-- schema test fails the build if any taxon lacks one; and reading is defensive
-- regardless, through the explicit chain in `Taxonomy`:
--
--     requested locale -> az -> the slug
--
-- The last step exists so that a taxon somebody adds by hand without an
-- Azerbaijani name degrades to a readable handle rather than to a blank
-- `<option>` in the campaign editor's category select.

-- Backfill, which is what makes this the expand half rather than a rewrite.
--
-- Taken from the columns that are staying, so the translation tables start out
-- agreeing with them exactly. Every row currently in `categories` is covered,
-- including any a hand-edit added since V6 -- the canonical seed further down
-- names only the fifteen categories §4.3 specifies, and this is what catches
-- everything else.
INSERT INTO category_translations (category_id, locale, name)
SELECT id, 'az', name_az FROM categories
UNION ALL
SELECT id, 'en', name_en FROM categories;

INSERT INTO subcategory_translations (subcategory_id, locale, name)
SELECT id, 'az', name_az FROM subcategories
UNION ALL
SELECT id, 'en', name_en FROM subcategories;

-- No `ru` or `tr` rows anywhere in this migration, deliberately. They are
-- phase 1 and phase 3 in §21.1, and the entire point of a translation table is
-- that adding a language becomes an INSERT rather than a release: when Russian
-- ships, somebody writes ~120 rows into these two tables and the API starts
-- resolving `Accept-Language: ru` against them without a deployment. Seeding
-- machine-translated placeholders now would make that INSERT an UPDATE against
-- text nobody reviewed, and §21.1 is explicit that content is not
-- machine-translated.

-- ---------------------------------------------------------------------------
-- The fifteen categories of §4.3
-- ---------------------------------------------------------------------------

-- V6 seeded ten categories as an interim, five of which do not appear in §4.3's
-- fifteen in any form. This reconciles the two. Nine slugs are kept exactly as
-- V6 wrote them -- technology, design, games, art, music, film, publishing,
-- food, fashion -- because a slug is in the URL and in every fixture, and
-- renaming one to match a specification that already agrees with it in
-- substance would break links to buy nothing.
--
-- `sort_order` is likewise left where V6 put it for those nine. The six new
-- categories take 100 onwards, in the order §4.3 lists them among themselves.
-- Alphabetical order is deliberately not used, for the reason V6 gives on the
-- column: the alphabetical order of these names in Azerbaijani and in English
-- are two different orders, and the navigation would reshuffle with the
-- language.
--
-- The English names are §4.3's, exactly. Two of the nine kept slugs disagreed
-- with it -- `film` was seeded as "Film & video" and `food` as "Food & drink" --
-- and where a seeded label and the specification differ on the same concept,
-- the specification is the one other documents and designs quote.
CREATE TEMPORARY TABLE seed_categories (
    slug       text PRIMARY KEY,
    name_az    text NOT NULL,
    name_en    text NOT NULL,
    sort_order int  NOT NULL
) ON COMMIT DROP;

INSERT INTO seed_categories (slug, name_az, name_en, sort_order) VALUES
    ('technology',  'Texnologiya',   'Technology',    10),
    ('design',      'Dizayn',        'Design',        20),
    ('games',       'Oyunlar',       'Games',         30),
    ('art',         'İncəsənət',     'Art',           40),
    ('music',       'Musiqi',        'Music',         50),
    ('film',        'Film və video', 'Film & Video',  60),
    ('publishing',  'Nəşriyyat',     'Publishing',    70),
    ('food',        'Qida',          'Food',          80),
    ('fashion',     'Moda',          'Fashion',       90),
    ('photography', 'Fotoqrafiya',   'Photography',  100),
    ('comics',      'Komikslər',     'Comics',       110),
    ('crafts',      'Sənətkarlıq',   'Crafts',       120),
    ('dance',       'Rəqs',          'Dance',        130),
    ('journalism',  'Jurnalistika',  'Journalism',   140),
    ('theatre',     'Teatr',         'Theatre',      150);

-- The six that §4.3 names and V6 did not seed. Inserted before the remapping
-- below, because two of them are where the remapping sends campaigns.
--
-- Identifiers are generated rather than written as literals, as in V6: a
-- literal in a migration is the value a fixture or a client eventually
-- hardcodes, and the stable handle for a category is its slug.
INSERT INTO categories (id, slug, name_az, name_en, sort_order)
SELECT gen_random_uuid(), seed.slug, seed.name_az, seed.name_en, seed.sort_order
FROM seed_categories seed
WHERE NOT EXISTS (SELECT 1 FROM categories WHERE categories.slug = seed.slug);

-- And the nine that already existed are brought onto the same list, so that
-- there is exactly one place -- `seed_categories` above -- that says what a
-- category is called.
UPDATE categories
SET name_az    = seed.name_az,
    name_en    = seed.name_en,
    sort_order = seed.sort_order
FROM seed_categories seed
WHERE categories.slug = seed.slug;

INSERT INTO category_translations (category_id, locale, name)
SELECT categories.id, 'az', seed.name_az FROM categories JOIN seed_categories seed ON seed.slug = categories.slug
UNION ALL
SELECT categories.id, 'en', seed.name_en FROM categories JOIN seed_categories seed ON seed.slug = categories.slug
ON CONFLICT (category_id, locale) DO UPDATE SET name = EXCLUDED.name;

-- ---------------------------------------------------------------------------
-- Refiling the campaigns that lose their category
-- ---------------------------------------------------------------------------

-- Three taxa go away, and every one of them may have campaigns filed under it.
-- The composite foreign key `projects_subcategory_belongs_to_category` and the
-- plain reference from `projects.category_id` both refuse a delete that would
-- orphan a row, so this has to happen first -- and it has to happen as three
-- separate decisions, because the three are not the same case.
--
-- `art/photography` and `publishing/comics` are promoted by §4.3 to top-level
-- categories of their own. A campaign filed under either is about exactly the
-- thing the new category names, so it keeps its meaning: it moves to the new
-- category and loses its subcategory, because the subcategory it had is now the
-- category it is in and repeating that would be a filing that says one thing
-- twice. A creator who wants to be more specific can choose one of the new
-- subcategories; nothing they said has been contradicted.

UPDATE projects
SET category_id    = (SELECT id FROM categories WHERE slug = 'photography'),
    subcategory_id = NULL
WHERE subcategory_id = (
    SELECT s.id FROM subcategories s JOIN categories c ON c.id = s.parent_id
    WHERE c.slug = 'art' AND s.slug = 'photography'
);

UPDATE projects
SET category_id    = (SELECT id FROM categories WHERE slug = 'comics'),
    subcategory_id = NULL
WHERE subcategory_id = (
    SELECT s.id FROM subcategories s JOIN categories c ON c.id = s.parent_id
    WHERE c.slug = 'publishing' AND s.slug = 'comics'
);

-- `community` is the different case, and the reason it is unfiled rather than
-- moved. §4.3's fifteen contain no category that means what "Community" meant:
-- its two seeded subcategories were Education and Environment, and neither Art
-- nor Publishing nor any of the other thirteen is a place a literacy programme
-- or a tree-planting campaign belongs. Every candidate destination would be a
-- guess made by this migration on the creator's behalf.
--
-- A wrong filing is worse than an absent one. A campaign in the wrong category
-- is shown to backers browsing a facet it does not belong in, and is missing
-- from the one it does; an unfiled campaign is simply not yet filed. §5.3 makes
-- a category a submission requirement and the checklist (#37) refuses to submit
-- without one, so a draft that lands here is asked the question again by the
-- person who can actually answer it -- and a campaign that is already live
-- keeps running, because none of §5.1's arithmetic depends on its category.
UPDATE projects
SET category_id = NULL, subcategory_id = NULL
WHERE category_id = (SELECT id FROM categories WHERE slug = 'community');

-- Now nothing points at them. The two promoted subcategories go first,
-- individually; deleting `community` takes its own subcategories with it
-- through the cascade V6 put on `subcategories.parent_id`, and takes their
-- translation rows through the cascade above.
DELETE FROM subcategories
WHERE slug = 'photography'
  AND parent_id = (SELECT id FROM categories WHERE slug = 'art');

DELETE FROM subcategories
WHERE slug = 'comics'
  AND parent_id = (SELECT id FROM categories WHERE slug = 'publishing');

DELETE FROM categories WHERE slug = 'community';

-- ---------------------------------------------------------------------------
-- The subcategories
-- ---------------------------------------------------------------------------

-- §4.3: "between four and nineteen subcategories -- roughly a hundred in
-- total". Seven per category, a hundred and five in all, which sits inside the
-- band with room in both directions: a category can gain a subcategory or lose
-- two without the shape of the tree needing a decision.
--
-- Seven rather than nineteen because a facet list is read, not searched. A
-- category that offers nineteen choices in a `<select>` makes a creator scroll
-- to file a campaign and makes a browsing backer read a column of options
-- instead of picking one; the specification's nineteen is a ceiling for the
-- categories that genuinely need it, not a target. Where the taxonomy turns out
-- to be too coarse, adding a row is now an INSERT -- which is the property §4.3
-- asked for.
--
-- Every slug V6 seeded and §4.3 keeps is reused verbatim: hardware, software,
-- robotics, product, graphic, tabletop, video, illustration, albums,
-- documentary, short, books, restaurants, craft, apparel, accessories.
--
-- The Azerbaijani names are Azerbaijani, not transliterated English, and two of
-- them exist because this platform is Azerbaijani: `crafts/carpet-weaving`
-- (Xalçaçılıq) and `music/mugham` (Muğam) are the two categories of work a
-- crowdfunding platform here will actually see, and neither has an obvious home
-- under a taxonomy copied from an American one.
CREATE TEMPORARY TABLE seed_subcategories (
    category_slug text NOT NULL,
    slug          text NOT NULL,
    name_az       text NOT NULL,
    name_en       text NOT NULL,
    sort_order    int  NOT NULL,
    PRIMARY KEY (category_slug, slug)
) ON COMMIT DROP;

INSERT INTO seed_subcategories (category_slug, slug, name_az, name_en, sort_order) VALUES
    -- Art
    ('art', 'illustration',            'İllüstrasiya',            'Illustration',             10),
    ('art', 'painting',                'Rəngkarlıq',              'Painting',                 20),
    ('art', 'sculpture',               'Heykəltəraşlıq',          'Sculpture',                30),
    ('art', 'digital-art',             'Rəqəmsal incəsənət',      'Digital art',              40),
    ('art', 'mixed-media',             'Qarışıq texnika',         'Mixed media',              50),
    ('art', 'performance',             'Performans',              'Performance art',          60),
    ('art', 'public-art',              'İctimai incəsənət',       'Public art',               70),
    -- Comics
    ('comics', 'graphic-novels',       'Qrafik romanlar',         'Graphic novels',           10),
    ('comics', 'single-issues',        'Ayrıca buraxılışlar',     'Single issues',            20),
    ('comics', 'webcomics',            'Vebkomikslər',            'Webcomics',                30),
    ('comics', 'manga',                'Manqa',                   'Manga',                    40),
    ('comics', 'anthologies',          'Antologiyalar',           'Anthologies',              50),
    ('comics', 'comic-strips',         'Komiks zolaqları',        'Comic strips',             60),
    ('comics', 'comic-events',         'Komiks tədbirləri',       'Events',                   70),
    -- Crafts
    ('crafts', 'ceramics',             'Keramika',                'Ceramics',                 10),
    ('crafts', 'carpet-weaving',       'Xalçaçılıq',              'Carpet weaving',           20),
    ('crafts', 'textiles',             'Toxuculuq',               'Textiles',                 30),
    ('crafts', 'woodworking',          'Ağac emalı',              'Woodworking',              40),
    ('crafts', 'jewellery',            'Zərgərlik',               'Jewellery',                50),
    ('crafts', 'glass',                'Şüşə',                    'Glass',                    60),
    ('crafts', 'leather',              'Dəri məmulatları',        'Leatherwork',              70),
    -- Dance
    ('dance', 'performances',          'Tamaşalar',               'Performances',             10),
    ('dance', 'folk-dance',            'Xalq rəqsləri',           'Folk dance',               20),
    ('dance', 'contemporary',          'Müasir rəqs',             'Contemporary dance',       30),
    ('dance', 'choreography',          'Xoreoqrafiya',            'Choreography',             40),
    ('dance', 'dance-film',            'Rəqs filmi',              'Dance film',               50),
    ('dance', 'workshops',             'Ustad dərsləri',          'Workshops',                60),
    ('dance', 'residencies',           'Rezidensiyalar',          'Residencies',              70),
    -- Design
    ('design', 'product',              'Məhsul dizaynı',          'Product design',           10),
    ('design', 'graphic',              'Qrafik dizayn',           'Graphic design',           20),
    ('design', 'typography',           'Tipoqrafika',             'Typography',               30),
    ('design', 'interactive',          'İnteraktiv dizayn',       'Interactive design',       40),
    ('design', 'architecture',         'Memarlıq',                'Architecture',             50),
    ('design', 'interiors',            'İnteryer dizaynı',        'Interior design',          60),
    ('design', 'civic',                'Şəhər dizaynı',           'Civic design',             70),
    -- Fashion
    ('fashion', 'apparel',             'Geyim',                   'Apparel',                  10),
    ('fashion', 'accessories',         'Aksesuarlar',             'Accessories',              20),
    ('fashion', 'footwear',            'Ayaqqabı',                'Footwear',                 30),
    ('fashion', 'ready-to-wear',       'Hazır geyim',             'Ready-to-wear',            40),
    ('fashion', 'childrenswear',       'Uşaq geyimi',             'Childrenswear',            50),
    ('fashion', 'sustainable',         'Davamlı moda',            'Sustainable fashion',      60),
    ('fashion', 'costume',             'Səhnə geyimi',            'Costume',                  70),
    -- Film & Video
    ('film', 'documentary',            'Sənədli film',            'Documentary',              10),
    ('film', 'short',                  'Qısametrajlı film',       'Short film',               20),
    ('film', 'feature',                'Tammetrajlı film',        'Feature film',             30),
    ('film', 'animation',              'Animasiya',               'Animation',                40),
    ('film', 'web-series',             'Veb-seriallar',           'Web series',               50),
    ('film', 'music-video',            'Musiqi videosu',          'Music video',              60),
    ('film', 'festivals',              'Festivallar',             'Festivals',                70),
    -- Food
    ('food', 'restaurants',            'Restoranlar',             'Restaurants',              10),
    ('food', 'craft',                  'Yerli istehsal',          'Craft producers',          20),
    ('food', 'bakeries',               'Çörəkxanalar',            'Bakeries',                 30),
    ('food', 'drinks',                 'İçkilər',                 'Drinks',                   40),
    ('food', 'farms',                  'Təsərrüfatlar',           'Farms',                    50),
    ('food', 'food-trucks',            'Səyyar mətbəxlər',        'Food trucks',              60),
    ('food', 'cookbooks',              'Kulinariya kitabları',    'Cookbooks',                70),
    -- Games
    ('games', 'tabletop',              'Stolüstü oyunlar',        'Tabletop games',           10),
    ('games', 'video',                 'Video oyunlar',           'Video games',              20),
    ('games', 'card-games',            'Kart oyunları',           'Card games',               30),
    ('games', 'mobile-games',          'Mobil oyunlar',           'Mobile games',             40),
    ('games', 'puzzles',               'Tapmacalar',              'Puzzles',                  50),
    ('games', 'live-games',            'Canlı oyunlar',           'Live games',               60),
    ('games', 'gaming-hardware',       'Oyun avadanlığı',         'Gaming hardware',          70),
    -- Journalism
    ('journalism', 'investigative',    'Araşdırma jurnalistikası','Investigative journalism', 10),
    ('journalism', 'local-news',       'Yerli xəbərlər',          'Local news',               20),
    ('journalism', 'long-form',        'Geniş formatlı yazılar',  'Long-form',                30),
    ('journalism', 'photojournalism',  'Fotojurnalistika',        'Photojournalism',          40),
    ('journalism', 'data-journalism',  'Data jurnalistikası',     'Data journalism',          50),
    ('journalism', 'podcasts',         'Podkastlar',              'Podcasts',                 60),
    ('journalism', 'newsletters',      'Bülletenlər',             'Newsletters',              70),
    -- Music
    ('music', 'albums',                'Albomlar',                'Albums',                   10),
    ('music', 'mugham',                'Muğam',                   'Mugham',                   20),
    ('music', 'classical',             'Klassik musiqi',          'Classical',                30),
    ('music', 'electronic',            'Elektron musiqi',         'Electronic',               40),
    ('music', 'concerts',              'Konsertlər',              'Concerts',                 50),
    ('music', 'tours',                 'Qastrol səfərləri',       'Tours',                    60),
    ('music', 'instruments',           'Musiqi alətləri',         'Instruments',              70),
    -- Photography
    ('photography', 'photo-books',     'Foto kitablar',           'Photo books',              10),
    ('photography', 'exhibitions',     'Sərgilər',                'Exhibitions',              20),
    ('photography', 'documentary',     'Sənədli fotoqrafiya',     'Documentary photography',  30),
    ('photography', 'portrait',        'Portret',                 'Portrait',                 40),
    ('photography', 'street',          'Küçə fotoqrafiyası',      'Street',                   50),
    ('photography', 'nature',          'Təbiət',                  'Nature',                   60),
    ('photography', 'equipment',       'Foto avadanlıq',          'Equipment',                70),
    -- Publishing
    ('publishing', 'books',            'Kitablar',                'Books',                    10),
    ('publishing', 'fiction',          'Bədii ədəbiyyat',         'Fiction',                  20),
    ('publishing', 'nonfiction',       'Qeyri-bədii ədəbiyyat',   'Nonfiction',               30),
    ('publishing', 'poetry',           'Poeziya',                 'Poetry',                   40),
    ('publishing', 'childrens-books',  'Uşaq kitabları',          'Children''s books',        50),
    ('publishing', 'magazines',        'Jurnallar',               'Magazines',                60),
    ('publishing', 'translation',      'Tərcümə',                 'Translation',              70),
    -- Technology
    ('technology', 'hardware',         'Avadanlıq',               'Hardware',                 10),
    ('technology', 'software',         'Proqram təminatı',        'Software',                 20),
    ('technology', 'robotics',         'Robototexnika',           'Robotics',                 30),
    ('technology', 'wearables',        'Geyilən texnologiya',     'Wearables',                40),
    ('technology', 'open-source',      'Açıq mənbə',              'Open source',              50),
    ('technology', 'green-tech',       'Yaşıl texnologiya',       'Green technology',         60),
    ('technology', 'education-tech',   'Təhsil texnologiyaları',  'Education technology',     70),
    -- Theatre
    ('theatre', 'plays',               'Tamaşalar',               'Plays',                    10),
    ('theatre', 'musicals',            'Müzikllər',               'Musicals',                 20),
    ('theatre', 'puppetry',            'Kukla teatrı',            'Puppetry',                 30),
    ('theatre', 'immersive',           'İmmersiv teatr',          'Immersive theatre',        40),
    ('theatre', 'set-design',          'Səhnə dizaynı',           'Set design',               50),
    ('theatre', 'festivals',           'Teatr festivalları',      'Festivals',                60),
    ('theatre', 'workshops',           'Emalatxanalar',           'Workshops',                70);

-- Slugs are unique within a parent, not globally -- so `festivals` under Film
-- and `festivals` under Theatre are two different rows and neither URL is
-- ambiguous. V6's `subcategories_slug_key` is what makes that true; the primary
-- key on the temporary table above matches it so a duplicate in this list fails
-- here rather than halfway through the insert.
INSERT INTO subcategories (id, parent_id, slug, name_az, name_en, sort_order)
SELECT gen_random_uuid(), categories.id, seed.slug, seed.name_az, seed.name_en, seed.sort_order
FROM seed_subcategories seed
JOIN categories ON categories.slug = seed.category_slug
WHERE NOT EXISTS (
    SELECT 1 FROM subcategories
    WHERE subcategories.parent_id = categories.id AND subcategories.slug = seed.slug
);

UPDATE subcategories
SET name_az    = seed.name_az,
    name_en    = seed.name_en,
    sort_order = seed.sort_order
FROM seed_subcategories seed
JOIN categories ON categories.slug = seed.category_slug
WHERE subcategories.parent_id = categories.id
  AND subcategories.slug = seed.slug;

INSERT INTO subcategory_translations (subcategory_id, locale, name)
SELECT subcategories.id, 'az', seed.name_az
FROM seed_subcategories seed
JOIN categories ON categories.slug = seed.category_slug
JOIN subcategories ON subcategories.parent_id = categories.id AND subcategories.slug = seed.slug
UNION ALL
SELECT subcategories.id, 'en', seed.name_en
FROM seed_subcategories seed
JOIN categories ON categories.slug = seed.category_slug
JOIN subcategories ON subcategories.parent_id = categories.id AND subcategories.slug = seed.slug
ON CONFLICT (subcategory_id, locale) DO UPDATE SET name = EXCLUDED.name;

-- ---------------------------------------------------------------------------
-- tags
-- ---------------------------------------------------------------------------

-- §4.3 lists Tags as a "free vocabulary" filter, beside the category tree and
-- not inside it. The two answer different questions: a category is where a
-- campaign is filed -- one place, chosen from a list the platform curates, and
-- a submission requirement under §5.3 -- while a tag is what a campaign is
-- about, in the creator's own words, and there are several. Folding tags into
-- `subcategories` would make every new word a curation decision, which is
-- exactly what a free vocabulary is not.
CREATE TABLE tags (
    id          uuid        PRIMARY KEY,
    -- The folded, lower-case form, and the only thing compared. §11.3 requires
    -- the index to fold locale-specific characters -- ə->e, ı->i, ö->o, ü->u,
    -- ğ->g, ş->s, ç->c -- "because users type both forms interchangeably", so
    -- "İncəsənət" and "incesenet" have to be one tag and not two. Folding
    -- happens in Java on the way in, where the rule is written once and can be
    -- unit-tested against the pairs §11.3 lists; a database-side
    -- `unaccent()` would not fold ə at all, because it is a letter of the
    -- alphabet rather than an accented e.
    slug        text        NOT NULL,
    -- The label as the first creator to use the tag wrote it, diacritics and
    -- capitals intact, and what every surface displays. Kept apart from `slug`
    -- because the fold is lossy in both directions: displaying the slug would
    -- show "incesenet" to a reader whose language has the letter ə in it, and
    -- comparing labels would make "Incesenet" and "İncəsənət" two tags with two
    -- separate sets of campaigns behind them.
    label       text        NOT NULL,
    -- Denormalised, and NOT maintained here. Discovery (#42) owns trending tags
    -- (D-09) and the live faceted counts (D-10), and it is the epic that will
    -- decide whether this is updated as pledges land, recomputed on a schedule,
    -- or replaced by a counting query against `project_tags`. Until then every
    -- row sits at 0 and no reader may treat it as live -- a zero here means
    -- "nobody has counted", not "no campaigns".
    usage_count int         NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),

    -- The same shape as every other slug in the schema, so a tag can appear in
    -- a filter URL without escaping.
    CONSTRAINT tags_slug_shape CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT tags_slug_length CHECK (length(slug) BETWEEN 2 AND 40),
    CONSTRAINT tags_label_length CHECK (length(btrim(label)) BETWEEN 1 AND 40),
    -- A negative count is a decrement that ran without its increment, and it
    -- would sort to the top of "least used" for ever.
    CONSTRAINT tags_usage_count_is_not_negative CHECK (usage_count >= 0)
);

-- One row per tag. The slug is the identity of a tag, which is the whole point
-- of folding it: two rows would split one tag's campaigns across two facets.
CREATE UNIQUE INDEX tags_slug_key ON tags (slug);

-- For autocomplete, which #46 builds: "suggest tags containing what has been
-- typed so far" is a LIKE '%...%' predicate, and the unique B-tree above cannot
-- serve one -- it can only answer prefixes. A trigram index can, and pg_trgm is
-- already enabled by V1 for exactly this.
CREATE INDEX tags_slug_trgm_idx ON tags USING gin (slug gin_trgm_ops);

CREATE TRIGGER tags_set_updated_at
    BEFORE UPDATE ON tags
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE tags IS
    'Free vocabulary (§4.3). `slug` is the folded form and the identity; `label` is what is displayed.';
COMMENT ON COLUMN tags.usage_count IS
    'Denormalised and not yet maintained. Discovery (#42) owns it; 0 means nobody has counted.';

-- No seed. A free vocabulary that the platform pre-populates is a curated one,
-- and the rows arrive from the campaign editor's tag field when it is built.

-- ---------------------------------------------------------------------------
-- project_tags
-- ---------------------------------------------------------------------------

CREATE TABLE project_tags (
    -- Cascades: a hard-deleted campaign is one that never launched, and a tag
    -- edge to a campaign that does not exist is a facet count that is wrong.
    project_id uuid        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    -- Cascades too, and this direction is the one that makes retiring a tag
    -- possible at all: a moderator merging two spellings deletes one row from
    -- `tags` and the edges go with it, rather than leaving every tagged
    -- campaign pointing at nothing.
    tag_id     uuid        NOT NULL REFERENCES tags (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),

    -- The pair is the identity: tagging a campaign with the same word twice is
    -- not a stronger claim about it, and a duplicate row would make the tag
    -- count double.
    CONSTRAINT project_tags_pkey PRIMARY KEY (project_id, tag_id)
);

-- "Which campaigns carry this tag" -- the query the discovery tag filter runs,
-- and the reverse of the primary key, which can only answer "which tags does
-- this campaign carry". Without it the filter is a sequential scan of every
-- edge on the platform.
CREATE INDEX project_tags_tag_idx ON project_tags (tag_id, project_id);

COMMENT ON TABLE project_tags IS
    'Which campaigns carry which tags. The cap on tags per campaign is enforced in Java; see below.';

-- WHY THE CAP IS IN JAVA AND NOT IN A TRIGGER.
--
-- A campaign needs a bound on how many tags it may carry -- otherwise one
-- campaign can appear in every facet on the platform, which is the cheapest
-- form of spam a free vocabulary allows. The database cannot express "at most N
-- rows with this project_id" as a constraint for the same reason it cannot
-- express the `az` rule above: a per-row CHECK cannot see sibling rows. The
-- only database-side option is a trigger that counts on every insert.
--
-- The cap is therefore enforced by the service that attaches tags, for two
-- reasons beyond that. A limit exceeded in a trigger reaches the client as a
-- constraint violation and a 500; enforced in Java it is a 400 that names the
-- field and says what the limit is, which is the same argument V6 makes about
-- checking title length in both places. And a trigger firing per row would
-- reject the eleventh tag of a ten-tag replacement that also removed three,
-- because it cannot see the deletes in the same statement.
--
-- That service does not exist yet, and neither does the endpoint. Attaching a
-- tag to a campaign belongs to the campaign editor, and §10.2 lists no
-- `/v1/tags` route at all -- so this migration delivers the schema, #41
-- delivers the entities and repositories, and nothing writes to either table
-- until the surface that owns the words is built.

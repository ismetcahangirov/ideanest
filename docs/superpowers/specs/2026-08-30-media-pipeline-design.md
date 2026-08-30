# Media pipeline for campaign images, and the cover minimum it makes honest

Design, 2026-08-30. Implements the ingestion half of `docs/architecture.md`
§13.1 for two surfaces, and changes one submission rule that the pipeline makes
newly enforceable.

---

## Why

Two complaints, and they turn out to be one problem.

**Creators cannot set a cover.** `SubmissionChecklist.COVER_MIN_WIDTH/HEIGHT`
requires 1024×576, `ProjectTransitionService` refuses to submit a campaign that
fails the checklist, and `CoverImageField` will not even record a smaller image.
A creator holding an 800×600 photograph is stopped at the first screen.

**There is nowhere to put a file.** There is no upload endpoint, no object
storage, no `media` table, and no processing. `apps/api/.../media/` contains one
`package-info.java`. Every image on the platform is a URL a creator typed by
hand, and `CoverImageField` renders a banner saying so. So the workaround for
the paragraph above is "go and host it somewhere yourself", which is the real
friction.

They are one problem because the minimum exists to protect a 1440px hero from a
stretched image, and it is checked against **dimensions the browser reported**.
`SubmissionChecklist`'s own header says a client could claim any size it liked.
The rule is simultaneously too strict for honest creators and unenforceable
against dishonest ones. Ingestion is what makes it a real measurement — and once
the server measures, the rule can afford to be advice.

---

## Scope

**In:** campaign cover images and story-document images.

**Out, and deliberately:** profile avatars, reward item images, and collection
covers keep accepting typed URLs. Video (§13.2). Virus scanning and
adult-content detection. Retention of the uploaded original.

### What this does not finish

§13.1 names two things as its definition of done. This work meets neither, and
that is a consequence of the scope above rather than an oversight:

1. **`remotePatterns` does not narrow.** `apps/web/next.config.mjs` accepts
   `https` on any host, which leaves `/_next/image` usable as an image proxy.
   The config comment already names this as a cost. It cannot narrow while
   avatars, reward items and collection covers still resolve to hosts we do not
   control.
2. **`blurDataUrl` reaches only two surfaces.** The `media` record carries it,
   so covers and story images get a placeholder from the same response as the
   image. Everything still on a typed URL keeps the status quo — no placeholder
   outside the editor, which samples bytes it already holds.

Both close when the remaining three fields migrate. That is follow-up work, and
it should be an issue rather than a comment nobody reads.

---

## Architecture

### Bytes never pass through Spring

```
Editor picks a file
  → POST /v1/media/uploads { contentType, byteSize }
      validates the ceiling and the declared type
      writes a media row as PENDING
      returns { mediaId, uploadUrl, expiresAt }
  → Browser PUTs the bytes straight to object storage
  → POST /v1/media/{id}/complete
      marks UPLOADED, enqueues processing
  → Processing (bounded pool, two at a time)
      read from storage → magic bytes → strip EXIF
      → downscale to ≤1440px → re-encode → 16px blur sample
      → write the derived object, delete the raw one
      → READY, with width, height and blurDataUrl
```

A presigned `PUT` rather than a multipart endpoint. Multipart through the API
would put a 20MB body in the request thread, and there is no
`spring.servlet.multipart` configuration in this repository to size it with — a
gap that today only the verification endpoint is exposed to, and one this
design should not widen.

Failure writes `FAILED` and a reason, which the editor shows. `complete` is
idempotent: a replay returns the current state and does not enqueue a second
pass. That is not defensive coding — a browser that retries on a dropped
response is the ordinary case.

### Storage

An S3-compatible object store, reached through the AWS SDK v2 client. R2, MinIO
and S3 itself all speak it.

Not Vercel Blob: `deploy.yml` rolls out container digests through a deploy hook
and this repository owns no infrastructure, so coupling the Java service to one
vendor's platform buys nothing. Not Postgres `bytea`: that is what identity
verification does, and it is right there — 5 MiB per document, at most four,
encrypted, and read once by a human. Campaign covers are read by everybody on
every page, and putting them in the row would carry them into every backup in
`ops/backup`.

### Processing runs in the API process

§13.1 says "enqueues processing". This uses a bounded executor inside the API
service rather than a new deployable, alongside the scheduled-job pattern the
project module already uses (`CampaignFinalizerJob`, `DeadlineReminderJob`,
`LaunchReminderJob`). A third container is infrastructure work this repository
cannot do — `deploy.yml` takes an `api_digest` and a `web_digest` and nothing
else.

The pool is bounded at two because the bound is a memory limit, not a throughput
preference. See below.

### libvips, and why a native dependency is worth it

The runtime stage is `eclipse-temurin:21-jre`. The JDK's own `ImageIO` reads and
writes JPEG and PNG with no new dependency at all, and it was the first choice.
Two facts ruled it out.

**It cannot read HEIC.** That is the default camera format on an iPhone. iOS
Safari usually transcodes to JPEG when a file input is used, and "usually" is
not a guarantee to build a first-run experience on — particularly in a change
whose whole purpose is that creators stop getting stuck.

**It decodes to a full bitmap in heap.** An 8000×6000 photograph is 192 MB as a
`BufferedImage`. The container sizes its heap with `MaxRAMPercentage`; two or
three concurrent uploads is an OutOfMemoryError, not a slowdown. libvips streams
and never materialises the whole image, which is also what makes the pool bound
of two a comfortable number rather than a nervous one.

Cost: one `apt-get install libvips-tools` in the runtime stage, roughly 40 MB.

### One derived file, not four

§13.1 asks ingestion for four variants — 160w, 640w, 1440w, original — in AVIF
with WebP and JPEG behind it. This stores **one**.

The reason is that the delivery half of §13.1 is already built and already does
that work. `next/image` content-negotiates AVIF then WebP, `deviceSizes` stops
at 1440 and `imageSizes` starts at 16, and the result is cached for 30 days.
Emitting four variants at ingestion would have the optimiser derive its own
variants from ours — the same encoding twice, and roughly five times the storage
for it.

Quality is not the trade being made. The widest box in the product is 720 CSS
px, so 1440 is that box at 2× and anything beyond it encodes pixels nobody can
resolve — which is the same reasoning `next.config.mjs` already used to drop
Next's 2048 and 3840 candidates.

The original is not kept. Re-cropping later works from the 1440px copy. Keeping
it is a defensible different choice; it costs roughly four to five times the
storage, and this design spends that budget on not having it.

---

## Data model

### `media`, new in V61

| Column | Notes |
|---|---|
| `id` | uuid, primary key |
| `owner_user_id` | uuid, **`ON DELETE CASCADE`** |
| `status` | `PENDING`, `UPLOADED`, `PROCESSING`, `READY`, `FAILED` |
| `storage_key` | object key; null until the derived object is written |
| `content_type` | the **detected** type, not the declared one |
| `byte_size` | of the derived object |
| `width`, `height` | measured, not reported |
| `blur_data_url` | 16px base64 sample |
| `failure_reason` | null unless `FAILED` |
| `created_at`, `updated_at` | |

`ON DELETE CASCADE` is not a style preference. Test suites truncate `users`; a
foreign key with `NO ACTION` breaks roughly twenty tests in suites that have
nothing to do with media, three frames away from anything that names it.

Constraints: `status` is checked against the five values; `width`/`height` are
either both null or both positive; `blur_data_url` and `storage_key` are
non-null when `status = 'READY'`, which keeps a half-processed row from being
served.

### Wiring in, expand then contract

`projects` gains `cover_media_id uuid REFERENCES media(id)`. The existing
`cover_image_url`, `cover_image_width` and `cover_image_height` **stay**, and
readers prefer the media row when it is present. Dropping the three columns is a
later release, per §1 of `CLAUDE.md`: expand, then contract, never both.

Story image blocks gain an optional `mediaId` beside the existing `url`.
`StoryDocuments` keeps accepting a block with only a `url` — every document
already stored has exactly that shape, and a validator that stopped accepting it
would invalidate the corpus.

---

## Processing rules

| Rule | Value |
|---|---|
| Type detection | magic bytes only, never the extension or the declared type |
| Accepted input | JPEG, PNG, WebP, AVIF, HEIC, TIFF, GIF |
| Upload ceiling | 20 MB, per §13.1 |
| Downscale | longest edge to 1440px; a smaller image is **not** enlarged |
| Output | PNG when the source has an alpha channel, otherwise JPEG q82 |
| EXIF | stripped entirely |
| Blur | 16px wide, base64, stored on the row |

The 20 MB ceiling is a denial-of-service control and stays. It is not the kind
of limit this work exists to remove: nobody is blocked from making a campaign by
it, and without it one request can occupy a processing slot with an arbitrarily
large file.

PNG for sources with alpha because JPEG has no alpha channel and would composite
transparency onto black. PNG is not compact, but the stored object is an input
to `next/image`, which will serve AVIF or WebP from it — so the cost is storage
on one file rather than bytes on every request.

---

## The rule this changes

`COVER_MIN_WIDTH` and `COVER_MIN_HEIGHT` stop blocking submission and become
advice.

`SubmissionChecklist` currently answers with blocking items only. It gains a
second severity, so a campaign whose cover is 800×600 is submittable and carries
a visible warning while it goes. Moderation reviews every submission anyway;
this moves a judgement about image quality to the people who already make
judgements about the campaign.

A hard floor of 320px on the longest edge stays, and it is not a quality rule —
it is what stops somebody who picked their avatar by mistake from putting a
128px square on a hero.

`CoverImageField` stops refusing the file. It shows the warning and records the
image.

`alt` text on story images stays required. `CLAUDE.md` does not treat
accessibility as negotiable, and nothing about an upload pipeline changes who
needs the description.

---

## Testing

The rules that fail silently, per `CLAUDE.md` §3:

- **Status transitions.** Every legal move, and that the illegal ones are
  refused — a row cannot go `READY` without a `storage_key`.
- **`complete` is idempotent.** A replay returns the same state and enqueues
  nothing.
- **Magic bytes.** A `.jpg` whose bytes are a PDF is refused; a JPEG named
  `.txt` is accepted.
- **EXIF is gone.** A fixture with GPS coordinates, asserted absent from the
  output. This is a privacy claim, so it is tested rather than reasoned about.
- **Downscale arithmetic**, including that a 640px image comes back 640px.
- **Alpha routing** — a PNG with transparency stays PNG, a photograph becomes
  JPEG.
- **The checklist no longer blocks** at 800×600, and still reports the warning.
- **`alt` is still required.**

Test data notes: fixtures take their own handles rather than reusing
`creator-1@example.com`, and teardown clears `outbox_events` before deleting
rows that events refer to.

## Documentation

`docs/architecture.md` §13.1 is edited in the same pull request. Today it says
"Ingestion is not built"; afterwards that is true of three fields and false of
two, and the section says which. The two definition-of-done items stay listed as
outstanding, with what they are waiting on.

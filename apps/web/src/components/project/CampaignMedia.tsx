import Image from 'next/image';
import { MediaFrame } from '@ideanest/ui/server';
import { PRELAUNCH_COVER_SIZES } from '../../lib/images/sizes';
import { canOptimise } from '../../lib/images/source';
import type { CampaignPage } from '../../lib/projects/publicPage';

/**
 * §4.4's media player — issue #281. Poster-first, no autoplay, and today no video.
 *
 * <h2>There is nothing to play, and this component says so rather than pretending</h2>
 *
 * §4.4 asks the header for a "media player (poster-first, no autoplay)". The poster half is
 * real: `ProjectPageResponse` carries `coverImage` and the campaign editor collects it. The
 * video half does not exist anywhere on the platform — <strong>the response has no video
 * field, and §13.2's video pipeline is not built</strong>: nothing transcodes an upload,
 * nothing stores a rendition, and no endpoint would return an address to play.
 *
 * So this renders the poster, and the play affordance is written as a branch that is
 * currently unreachable rather than as a control that is currently a lie. A play button over
 * a campaign's cover that does nothing when pressed is worse than no button: it is a promise
 * the page cannot keep, made at the top of the page, to somebody deciding whether the
 * creator keeps promises. {@link CampaignMediaProps.video} is the shape the day a rendition
 * URL exists; the branch below it is what mounts then, and nothing else on this page changes.
 *
 * <h2>Poster-first is a decision that survives the video arriving</h2>
 *
 * "No autoplay" is not a preference. This is the largest element on the largest-contentful-
 * paint-sensitive route in the application (#119), it is the first thing a reader sees, and
 * an autoplaying video costs the connection of somebody on mobile data before they have
 * decided they want it. When there is a video, the poster is what is served and the video is
 * fetched on the press — `preload="none"`, and the `<video>` element is mounted by the
 * client island that owns the press rather than sitting inert in every server render.
 *
 * <h2>The box is reserved before anything decodes</h2>
 *
 * `MediaFrame` sets the 16:9 crop up front, so the page's largest element does not change
 * height when the photograph arrives. That is the layout shift the Core Web Vitals budget in
 * CI measures, and it is why the frame is rendered even for a campaign with no cover at all.
 *
 * <h2>The alt text is empty, deliberately</h2>
 *
 * §9.2 of docs/ui-kit.md forbids colour alone carrying meaning and §9.4 asks for accessible
 * names on controls; neither asks for a description of a decorative photograph. The campaign
 * title is the `<h1>` immediately beside this, so a screen reader that announced the cover as
 * "A coffee table book" would read the same words twice. The service stores no alternative
 * text for a cover — there is no column for one — and inventing a description from the title
 * is exactly that duplication. An empty `alt` takes the image out of the accessibility tree,
 * which is the correct answer for an image whose content is already stated in text.
 */

export interface CampaignVideo {
  /** A playable rendition. Nothing produces one yet — see the class comment. */
  readonly url: string;
  /** How long it runs, in seconds, for the control's accessible name. */
  readonly durationSeconds: number | null;
}

export interface CampaignMediaProps {
  readonly campaign: CampaignPage;
  /**
   * The campaign's video, when the platform has one.
   *
   * Always absent today, and typed rather than omitted so that the branch below is written,
   * reviewed and tested now instead of being retrofitted onto a header that had grown around
   * its absence.
   */
  readonly video?: CampaignVideo | undefined;
}

export function CampaignMedia({ campaign, video }: CampaignMediaProps) {
  const hasVideo = video !== undefined;

  return (
    <MediaFrame ratio="16/9" radius="lg">
      {campaign.coverImage !== null && (
        <Image
          src={campaign.coverImage.url}
          alt=""
          fill
          /*
           * The prelaunch stops, reused. The two layouts are not identical — this column is
           * `minmax(0,1.6fr)` inside a 1200px container rather than a 720px measure — so the
           * request is a little conservative at the widest breakpoint. Correcting it is an
           * entry in `lib/images/sizes.ts`, which is that module's to add: it exists so that
           * every `sizes` string is written next to the Tailwind classes it mirrors, and a
           * hand-rolled string here would be the stale constant it was written to prevent.
           */
          sizes={PRELAUNCH_COVER_SIZES}
          /*
           * An address on a host the optimiser will not fetch is served as it is rather than
           * thrown over: `next/image` raises on a URL no remote pattern matches, and a raised
           * render in a Server Component takes the whole page down. One creator's typo must
           * not be able to do that.
           */
          unoptimized={!canOptimise(campaign.coverImage.url)}
          priority
          className="object-cover"
        />
      )}

      {/*
        THE PLAY AFFORDANCE, AND WHY IT IS NOT ON THE PAGE.

        `hasVideo` is false for every campaign on the platform today, because no field on any
        response carries a video. This is the seam and not a placeholder: when §13.2 lands, a
        client island mounts here to own the press and the `<video>` element — `preload="none"`
        so the poster stays the only thing fetched until somebody asks — and the rest of this
        header is unchanged.
      */}
      {hasVideo && (
        <div className="absolute inset-0 grid place-items-center">
          <p className="rounded-sm bg-black/60 px-3 py-1.5 text-sm text-white">
            This campaign has a video.
          </p>
        </div>
      )}
    </MediaFrame>
  );
}

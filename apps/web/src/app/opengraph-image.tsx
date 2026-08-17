import { ImageResponse } from 'next/og';
import {
  OG_IMAGE_CONTENT_TYPE,
  OG_IMAGE_SIZE,
  OG_SITE_ALT,
  siteSocialCard,
} from '../lib/seo/metadata-card';

/**
 * The site's social preview image — `/opengraph-image`.
 *
 * **Static, and static is the point.** It takes no parameters and reads nothing, so
 * `next build` renders it once and serves a file thereafter; the route table prints
 * it as `○`. It is the fallback every segment that has no card of its own falls
 * back to, and a fallback that cost a rasterisation per request would be the most
 * expensive image on the site.
 *
 * **It is a route, not a picture in `public/`.** A hand-authored PNG would be a
 * colour palette nobody could review and nobody could keep in step with the token
 * file — the one place a design change to the brand card would silently fail to
 * arrive. Drawn from the tokens, it changes when they do.
 *
 * `alt`, `size`, and `contentType` are Next's own exports for a metadata image
 * file; they become `og:image:alt`, `og:image:width`, `og:image:height`, and the
 * response's content type.
 */
export const alt = OG_SITE_ALT;
export const size = OG_IMAGE_SIZE;
export const contentType = OG_IMAGE_CONTENT_TYPE;

export default function Image(): ImageResponse {
  return new ImageResponse(siteSocialCard(), { ...size });
}

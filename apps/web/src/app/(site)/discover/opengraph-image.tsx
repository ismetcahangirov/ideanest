import { ImageResponse } from 'next/og';
import {
  OG_IMAGE_CONTENT_TYPE,
  OG_IMAGE_SIZE,
  OG_SITE_ALT,
  siteSocialCard,
} from '../../../lib/seo/metadata-card';

/**
 * `/discover`'s social preview image — the site card again.
 *
 * **THIS FILE IS NOT REDUNDANT, and deleting it would silently remove the image
 * from the page.** Next's file-based Open Graph image is merged into the metadata
 * of the segment it sits in, and a child segment that declares an `openGraph`
 * block of its own REPLACES its parent's resolved one wholesale
 * (`resolve-metadata.js`: `case 'openGraph'`). `/discover` declares one — it has
 * its own title, description, and canonical — so the card from `app/` is replaced
 * and the segment needs its own to have any card at all.
 *
 * The card itself is the site's, deliberately. Discovery is the front door and its
 * results are chosen in the browser from the query string, so there is no single
 * campaign for a card to show and no honest way to draw the feed a particular link
 * happens to select.
 */
export const alt = OG_SITE_ALT;
export const size = OG_IMAGE_SIZE;
export const contentType = OG_IMAGE_CONTENT_TYPE;

export default function Image(): ImageResponse {
  return new ImageResponse(siteSocialCard(), { ...size });
}

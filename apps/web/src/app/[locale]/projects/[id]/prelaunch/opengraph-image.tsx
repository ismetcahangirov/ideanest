import { ImageResponse } from 'next/og';
import {
  OG_IMAGE_CONTENT_TYPE,
  OG_IMAGE_SIZE,
  OG_PROJECT_ALT,
  projectSocialCard,
  siteSocialCard,
} from '../../../../../lib/seo/metadata-card';
import { fetchPublicProjectPreview } from '../../../../../lib/seo/metadata-source';

/**
 * A campaign's social preview image — `/projects/{id}/prelaunch/opengraph-image`.
 *
 * **When it is used.** `generateMetadata` names the campaign's own cover
 * photograph as the preview whenever there is one, and Next then ignores this file
 * (it merges a file-based image only where the segment's metadata declares no
 * `images`). So this route is what a campaign with NO cover image gets — which,
 * until the media pipeline of §13.1 exists, is most of them, since a cover today
 * is a URL a creator pasted.
 *
 * **It is a public, unauthenticated endpoint, and it is written as one.** Anybody
 * may request it for any identifier. It therefore reads the same anonymous
 * projection `generateMetadata` does and falls back to the site's own card for
 * every campaign it cannot confirm is public — a draft, a suspended campaign, an
 * identifier that does not exist, or a service that did not answer. The fallback
 * names no campaign, so an image request is not a way to ask whether a private
 * campaign exists.
 *
 * **It never throws.** A metadata image route that throws does not produce a
 * broken image, it produces a 500 on a URL that Facebook, X, and WhatsApp have
 * already been told is the picture for this page.
 */
export const alt = OG_PROJECT_ALT;
export const size = OG_IMAGE_SIZE;
export const contentType = OG_IMAGE_CONTENT_TYPE;

export default async function Image({
  params,
}: {
  params: Promise<{ id: string }>;
}): Promise<ImageResponse> {
  const { id } = await params;
  const preview = await fetchPublicProjectPreview(id);

  return new ImageResponse(
    preview === null
      ? siteSocialCard()
      : projectSocialCard({ title: preview.title, blurb: preview.blurb }),
    { ...size },
  );
}

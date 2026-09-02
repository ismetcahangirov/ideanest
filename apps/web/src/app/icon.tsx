import { ImageResponse } from 'next/og';
import { colors } from '@ideanest/design-tokens';

/**
 * The tab icon — issue #403.
 *
 * <h2>Why a 404 was worth clearing</h2>
 *
 * <p>The application declared no icon at all, so every page in it logged a request for
 * `/favicon.ico` and a 404 in reply. That is not a broken page, and that is the problem: it
 * was the only console error in an otherwise clean log, which is exactly how a real one stops
 * being noticed. Declaring an icon makes the browser ask for the icon rather than guess.
 *
 * <h2>A route drawn from the tokens, not a file in `public/`</h2>
 *
 * <p>`app/[locale]/opengraph-image.tsx` makes this argument for the social card and it
 * applies unchanged: a hand-authored image is a colour palette nobody can review and nobody
 * can keep in step with the token file — the one place a change to the brand would silently
 * fail to arrive. It takes no parameters and reads nothing, so `next build` renders it once
 * and serves a file thereafter.
 *
 * <h2>Not lime</h2>
 *
 * <p>docs/ui-kit.md: lime says "act now". A brand mark is not urgent, and an icon that is
 * lime whatever is happening spends the one colour the product reserves for saying something.
 * This is the site header's own look — the wordmark's first letter, white on near-black.
 */
export const size = { width: 32, height: 32 };
export const contentType = 'image/png';

export default function Icon(): ImageResponse {
  return new ImageResponse(
    (
      <div
        style={{
          width: '100%',
          height: '100%',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          background: colors.surface1,
          color: colors.textPrimary,
          fontSize: 24,
          fontWeight: 600,
          // The wordmark's own tracking, so the letter sits the way it does in the header.
          letterSpacing: '-0.03em',
        }}
      >
        I
      </div>
    ),
    { ...size },
  );
}

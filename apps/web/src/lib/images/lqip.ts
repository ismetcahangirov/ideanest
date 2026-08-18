/**
 * The low-quality image placeholder, and where it comes from.
 *
 * THE SHAPE OF THE PROBLEM. A blur placeholder is a picture of the picture, so
 * something has to have seen the bytes. There are three cases and they have
 * three different answers:
 *
 *   1. A STATICALLY IMPORTED image. Next reads the file during the build and
 *      attaches `blurDataURL` to the import; `placeholder="blur"` then needs
 *      nothing from us. There is no such import in the application yet.
 *
 *   2. AN UPLOAD. docs/architecture.md §13.1 has the media service producing
 *      a thumbnail, card, and hero variant during ingestion. The placeholder is
 *      the same operation at 16 pixels, base64'd into the media record, so it
 *      arrives in the same response as the URL and the dimensions and costs no
 *      extra request. That service does not exist yet.
 *
 *   3. AN ADDRESS THE SERVER HAS NEVER SEEN — which is every cover today,
 *      because there is no uploader and the creator types a URL (contract §3).
 *      Nothing on the server can produce a placeholder for bytes it has not
 *      fetched, and fetching them per card during render is a worse cost than
 *      the one the placeholder is meant to remove.
 *
 * WHAT THIS MODULE IS. Case 2's algorithm, running in the only place that holds
 * the bytes today: the creator's browser, at the moment the campaign editor
 * already loads the image to read its intrinsic size. The editor shows the
 * result immediately. It is NOT persisted, because `cover_image_url`,
 * `cover_image_width` and `cover_image_height` are the only three columns
 * (`lib/projects/api.ts`) and inventing a fourth is the media epic's work, not
 * this one's. When that lands, this function moves to the server unchanged and
 * the placeholder starts arriving with the cover.
 *
 * WHY 16 PIXELS WIDE. It is the width the placeholder is blurred past anyway,
 * it keeps the data URI in the hundreds of bytes so it can travel inline, and
 * it is small enough that no recognisable detail survives — which matters,
 * because a placeholder is shown before any moderation has looked at the image.
 */

/** The sampled width. Height follows the image's own aspect ratio. */
export const LQIP_WIDTH = 16;

/**
 * WebP first, then JPEG. WebP at a low quality is roughly half the bytes of the
 * equivalent JPEG at this size, and every browser that can run this code can
 * decode it — but `toDataURL` silently answers with a PNG for a type it does not
 * support, and a PNG of a photograph is far larger than either, so the result is
 * checked rather than assumed.
 */
const ENCODINGS: readonly { type: string; quality: number }[] = [
  { type: 'image/webp', quality: 0.6 },
  { type: 'image/jpeg', quality: 0.5 },
];

/**
 * Samples a loaded image down to a placeholder data URI.
 *
 * Returns `null` rather than throwing, for two reasons that are both normal
 * rather than exceptional:
 *
 *   - THE CANVAS IS TAINTED. Drawing a cross-origin image onto a canvas makes
 *     `toDataURL` throw a `SecurityError` unless the image was requested with
 *     `crossOrigin` AND the host answered with the CORS header. Most hosts do
 *     not, and a creator's own photograph on their own website is the common
 *     case. A cover with no placeholder is a cover with no placeholder; it is
 *     not a failed save.
 *   - THERE IS NO CANVAS. Server rendering and the test environment both reach
 *     this file through the module graph and neither has a 2D context.
 *
 * The image must already be loaded — `naturalWidth` is zero until it is, and a
 * zero-width canvas is not an error anywhere, just an empty picture.
 */
export function placeholderFrom(image: HTMLImageElement): string | null {
  const { naturalWidth, naturalHeight } = image;
  if (naturalWidth <= 0 || naturalHeight <= 0) return null;

  const width = Math.min(LQIP_WIDTH, naturalWidth);
  const height = Math.max(1, Math.round((width * naturalHeight) / naturalWidth));

  let canvas: HTMLCanvasElement;
  try {
    canvas = document.createElement('canvas');
  } catch {
    return null;
  }

  canvas.width = width;
  canvas.height = height;

  const context = canvas.getContext('2d');
  if (context === null) return null;

  try {
    context.drawImage(image, 0, 0, width, height);

    for (const { type, quality } of ENCODINGS) {
      const uri = canvas.toDataURL(type, quality);
      if (uri.startsWith(`data:${type};base64,`)) return uri;
    }
  } catch {
    // Tainted canvas, or a decoder that refused the frame.
    return null;
  }

  return null;
}

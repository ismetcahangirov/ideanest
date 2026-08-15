/**
 * The cover image rule, and the only way the client can check it.
 *
 * §5.3 makes a cover of at least 1024×576 a submission requirement, so the
 * dimensions have to be known before the project is submitted. There is no
 * uploader and no media service yet (contract §3), which means nothing on the
 * server has ever seen the file — the browser is the only place the intrinsic
 * size can be read at all. It is read here and sent alongside the URL.
 *
 * This is interim by construction. When the media pipeline lands
 * (docs/architecture.md §13) the service reads the dimensions itself during
 * ingestion and this module goes away rather than being extended.
 */

export const COVER_MIN_WIDTH = 1024;
export const COVER_MIN_HEIGHT = 576;

export interface ImageSize {
  width: number;
  height: number;
}

/** 16:9 at the minimum, which is the aspect the discovery card is cut to. */
export function meetsCoverMinimum(size: ImageSize): boolean {
  return size.width >= COVER_MIN_WIDTH && size.height >= COVER_MIN_HEIGHT;
}

export function describeSize(size: ImageSize): string {
  return `${size.width}×${size.height}`;
}

/**
 * Reads the intrinsic pixel size of an image, from a URL or from a local file.
 *
 * `naturalWidth` and `naturalHeight` rather than `width`/`height`: the latter
 * pair report the layout box, which for an image that never entered the
 * document is zero, and a cover silently recorded as 0×0 would fail the
 * checklist for a reason nobody could see.
 *
 * A cross-origin URL is fine here — intrinsic dimensions are readable without
 * CORS, unlike pixel data — so `crossOrigin` is deliberately not set. Setting it
 * would make every image on a host without the header fail to load instead.
 */
export function measureImage(source: string | File): Promise<ImageSize> {
  const objectUrl = typeof source === 'string' ? null : URL.createObjectURL(source);
  const src = objectUrl ?? (source as string);

  return new Promise<ImageSize>((resolve, reject) => {
    const image = new Image();

    const done = (settle: () => void): void => {
      // The blob URL holds the whole file in memory until it is revoked, and a
      // creator trying four photographs would otherwise leak all four.
      if (objectUrl !== null) URL.revokeObjectURL(objectUrl);
      settle();
    };

    image.onload = () =>
      done(() => resolve({ width: image.naturalWidth, height: image.naturalHeight }));

    image.onerror = () =>
      done(() =>
        reject(
          new Error(
            typeof source === 'string'
              ? 'That address could not be loaded as an image.'
              : 'That file could not be read as an image.',
          ),
        ),
      );

    image.src = src;
  });
}

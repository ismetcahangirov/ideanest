import { afterEach, describe, expect, it, vi } from 'vitest';
import { LQIP_WIDTH, placeholderFrom } from './lqip';

/**
 * The placeholder sampler.
 *
 * jsdom has no 2D context, so the canvas is stubbed — which is the honest way
 * to test this anyway: what matters is the geometry it asks for, the encoding
 * it settles on, and that every way this can fail is a `null` rather than an
 * exception thrown out of a creator's save.
 */

interface DrawCall {
  readonly width: number;
  readonly height: number;
}

interface CanvasStub {
  readonly draws: DrawCall[];
  readonly encodings: string[];
  readonly canvas: { width: number; height: number };
}

/** Replaces `document.createElement('canvas')` with something inspectable. */
function stubCanvas(options: {
  context?: boolean;
  toDataURL?: (type: string) => string;
}): CanvasStub {
  const draws: DrawCall[] = [];
  const encodings: string[] = [];
  const canvas = { width: 0, height: 0 };

  const element = {
    get width() {
      return canvas.width;
    },
    set width(value: number) {
      canvas.width = value;
    },
    get height() {
      return canvas.height;
    },
    set height(value: number) {
      canvas.height = value;
    },
    getContext: () =>
      options.context === false
        ? null
        : {
            drawImage: (_image: unknown, _x: number, _y: number, w: number, h: number) => {
              draws.push({ width: w, height: h });
            },
          },
    toDataURL: (type: string) => {
      encodings.push(type);
      return (options.toDataURL ?? (() => `data:${type};base64,AAAA`))(type);
    },
  };

  vi.spyOn(document, 'createElement').mockImplementation((tag: string) => {
    if (tag === 'canvas') return element as unknown as HTMLElement;
    throw new Error(`unexpected createElement(${tag})`);
  });

  return { draws, encodings, canvas };
}

function loadedImage(width: number, height: number): HTMLImageElement {
  return { naturalWidth: width, naturalHeight: height } as HTMLImageElement;
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe('placeholderFrom', () => {
  it('samples to the placeholder width, keeping the aspect ratio', () => {
    const stub = stubCanvas({});

    const uri = placeholderFrom(loadedImage(1600, 900));

    expect(uri).toBe('data:image/webp;base64,AAAA');
    expect(stub.canvas).toEqual({ width: LQIP_WIDTH, height: 9 });
    expect(stub.draws).toEqual([{ width: LQIP_WIDTH, height: 9 }]);
  });

  it('rounds a fractional height up to at least one row', () => {
    // A very wide banner is under half a pixel tall at this width, and a
    // zero-height canvas encodes to nothing at all.
    const stub = stubCanvas({});

    placeholderFrom(loadedImage(4000, 100));

    expect(stub.canvas.height).toBeGreaterThanOrEqual(1);
  });

  it('never samples an image up', () => {
    const stub = stubCanvas({});

    placeholderFrom(loadedImage(8, 8));

    expect(stub.canvas).toEqual({ width: 8, height: 8 });
  });

  it('prefers WebP', () => {
    const stub = stubCanvas({});

    placeholderFrom(loadedImage(1600, 900));

    expect(stub.encodings).toEqual(['image/webp']);
  });

  it('falls back to JPEG when the browser answers WebP with a PNG', () => {
    // `toDataURL` does not report an unsupported type, it silently returns a
    // PNG — and a PNG of a photograph is larger than either alternative.
    const stub = stubCanvas({
      toDataURL: (type) =>
        type === 'image/webp' ? 'data:image/png;base64,AAAA' : `data:${type};base64,BBBB`,
    });

    expect(placeholderFrom(loadedImage(1600, 900))).toBe('data:image/jpeg;base64,BBBB');
    expect(stub.encodings).toEqual(['image/webp', 'image/jpeg']);
  });

  it('gives up when no encoding is honoured', () => {
    stubCanvas({ toDataURL: () => 'data:image/png;base64,AAAA' });

    expect(placeholderFrom(loadedImage(1600, 900))).toBeNull();
  });

  it('returns null for a tainted canvas rather than throwing', () => {
    // A cross-origin image without the CORS header taints the canvas, and
    // `toDataURL` then throws a SecurityError. That is the common case, not an
    // exceptional one: a cover with no placeholder is not a failed save.
    stubCanvas({
      toDataURL: () => {
        throw new DOMException('tainted', 'SecurityError');
      },
    });

    expect(placeholderFrom(loadedImage(1600, 900))).toBeNull();
  });

  it('returns null when there is no 2D context', () => {
    stubCanvas({ context: false });

    expect(placeholderFrom(loadedImage(1600, 900))).toBeNull();
  });

  it.each([
    ['not loaded yet', 0, 0],
    ['no height', 1600, 0],
  ])('returns null for an image that is %s', (_label, width, height) => {
    expect(placeholderFrom(loadedImage(width, height))).toBeNull();
  });
});

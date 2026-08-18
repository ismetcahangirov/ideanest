import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import {
  Media,
  MediaFrame,
  MEDIA_RATIOS,
  aspectRatioOf,
  isPlaceholderUri,
} from './media/Media';

/**
 * The media primitive. Appearance is reviewed in Storybook; these cover the
 * three things that fail silently.
 *
 * THE RESERVATION. `aspect-ratio` missing from the frame is invisible in review
 * — the page looks right once the image has loaded, and only shifts on the
 * first paint of a cold connection. It is the headline requirement of the image
 * pipeline issue, and it is asserted on every path through the component,
 * including the paths a bad measurement produces.
 *
 * THE ALT CONTRACT. `alt=""` and a missing `alt` render identically to the eye
 * and differently to a screen reader, which reads the file name for the second.
 *
 * THE PLACEHOLDER. It is interpolated into a CSS `url()`, so anything that is
 * not an inline image is a string being trusted into a stylesheet.
 */

const COVER = 'data:image/png;base64,iVBORw0KGgo=';

function frameOf(container: HTMLElement): HTMLElement {
  const frame = container.querySelector<HTMLElement>('[data-media-frame]');
  if (frame === null) throw new Error('no media frame rendered');
  return frame;
}

describe('aspectRatioOf', () => {
  it('maps every crop token to a CSS ratio', () => {
    for (const token of Object.keys(MEDIA_RATIOS) as (keyof typeof MEDIA_RATIOS)[]) {
      expect(aspectRatioOf(token)).toBe(MEDIA_RATIOS[token]);
    }
  });

  it('uses the image’s own shape when it is shown whole', () => {
    expect(aspectRatioOf({ width: 1024, height: 576 })).toBe('1024 / 576');
    expect(aspectRatioOf({ width: 900, height: 1600 })).toBe('900 / 1600');
  });

  it.each([
    ['zero height', { width: 1024, height: 0 }],
    ['zero width', { width: 0, height: 576 }],
    ['negative', { width: -4, height: -3 }],
    ['not a number', { width: Number.NaN, height: 576 }],
    ['infinite', { width: Number.POSITIVE_INFINITY, height: 576 }],
  ])('falls back to 16:9 rather than emitting an invalid ratio: %s', (_label, size) => {
    // `1024 / 0` is a declaration the browser drops, and dropping it takes the
    // reservation with it. A measurement that failed leaves exactly this.
    expect(aspectRatioOf(size)).toBe(MEDIA_RATIOS['16/9']);
  });
});

describe('isPlaceholderUri', () => {
  it('accepts an inline image', () => {
    expect(isPlaceholderUri(COVER)).toBe(true);
    expect(isPlaceholderUri('data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=')).toBe(true);
  });

  it.each([
    ['a remote address', 'https://images.example.test/tiny.jpg'],
    ['a relative path', '/covers/tiny.jpg'],
    ['a non-image data URI', 'data:text/html;base64,PGI+'],
    ['an unencoded data URI', 'data:image/svg+xml,<svg/>'],
    ['a quote break-out', 'data:image/png;base64,AAAA");background:url("evil'],
    ['nothing at all', ''],
  ])('rejects %s', (_label, value) => {
    expect(isPlaceholderUri(value)).toBe(false);
  });
});

describe('MediaFrame', () => {
  it('reserves the box before anything has loaded', () => {
    const { container } = render(<MediaFrame ratio="16/9" />);

    expect(frameOf(container).style.aspectRatio).toBe('16 / 9');
  });

  it('reserves the box for an image it is given', () => {
    const { container } = render(
      <MediaFrame ratio={{ width: 1600, height: 900 }}>
        <img src={COVER} alt="" />
      </MediaFrame>,
    );

    expect(frameOf(container).style.aspectRatio).toBe('1600 / 900');
  });

  it('is a positioned, clipping ancestor so a filled image has something to fill', () => {
    // `next/image` with `fill` positions itself absolutely against the nearest
    // positioned ancestor. Without `relative` here it escapes to the page.
    const { container } = render(<MediaFrame ratio="16/9" />);

    expect(frameOf(container).className).toContain('relative');
    expect(frameOf(container).className).toContain('overflow-hidden');
  });

  it('paints a placeholder inside the reserved box, out of the accessibility tree', () => {
    const { container } = render(<MediaFrame ratio="16/9" placeholder={COVER} />);

    const layer = container.querySelector<HTMLElement>('[data-media-placeholder]');
    expect(layer).not.toBeNull();
    expect(layer).toHaveAttribute('aria-hidden', 'true');
    expect(layer?.style.backgroundImage).toBe(`url("${COVER}")`);
  });

  it('ignores a placeholder that is not an inline image', () => {
    const { container } = render(
      <MediaFrame ratio="16/9" placeholder="https://images.example.test/tiny.jpg" />,
    );

    expect(container.querySelector('[data-media-placeholder]')).toBeNull();
  });

  it('keeps the reservation when the caller styles the frame', () => {
    const { container } = render(
      <MediaFrame ratio="4/3" className="rounded-lg" style={{ maxWidth: '320px' }} />,
    );

    expect(frameOf(container).style.aspectRatio).toBe('4 / 3');
    expect(frameOf(container).style.maxWidth).toBe('320px');
  });
});

describe('Media', () => {
  it('reserves the box and fills it', () => {
    const { container } = render(<Media src={COVER} ratio="16/9" decorative />);

    expect(frameOf(container).style.aspectRatio).toBe('16 / 9');
    expect(container.querySelector('img')?.className).toContain('object-cover');
  });

  it('describes a content image', () => {
    render(<Media src={COVER} ratio="16/9" alt="A field recorder on a workbench." />);

    expect(
      screen.getByRole('img', { name: 'A field recorder on a workbench.' }),
    ).toBeInTheDocument();
  });

  it('empties the alt of a decorative image rather than omitting it', () => {
    // An omitted `alt` makes a screen reader read the file name; `alt=""`
    // takes the image out of the accessibility tree, which is the intent.
    const { container } = render(<Media src={COVER} ratio="16/9" decorative />);
    const image = container.querySelector('img');

    expect(image).toHaveAttribute('alt', '');
    expect(screen.queryByRole('img')).toBeNull();
  });

  it('is lazy by default and eager only when asked', () => {
    const { container, rerender } = render(<Media src={COVER} ratio="16/9" decorative />);
    expect(container.querySelector('img')).toHaveAttribute('loading', 'lazy');

    rerender(<Media src={COVER} ratio="16/9" decorative loading="eager" />);
    expect(container.querySelector('img')).toHaveAttribute('loading', 'eager');
  });

  it('letterboxes rather than crops when told to contain', () => {
    const { container } = render(
      <Media src={COVER} ratio={{ width: 900, height: 1600 }} fit="contain" decorative />,
    );

    expect(container.querySelector('img')?.className).toContain('object-contain');
    expect(frameOf(container).style.aspectRatio).toBe('900 / 1600');
  });
});

import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { Chip } from './Chip/Chip';
import { IconButton } from './IconButton/IconButton';
import { ProgressBar } from './ProgressBar/ProgressBar';
import { DotIndicator } from './DotIndicator/DotIndicator';
import { Avatar, AvatarGroup } from './Avatar/Avatar';
import { Timeline } from '../layout/Timeline';
import { RailItem, Rail } from '../layout/Rail';

/**
 * Appearance is reviewed in Storybook. These tests cover BEHAVIOUR and
 * ACCESSIBILITY — the things that break silently and still ship.
 */

describe('Chip', () => {
  it('announces its selected state via aria-pressed', () => {
    const { rerender } = render(<Chip active={false}>Games</Chip>);
    expect(screen.getByRole('button')).toHaveAttribute('aria-pressed', 'false');

    rerender(<Chip active>Games</Chip>);
    expect(screen.getByRole('button')).toHaveAttribute('aria-pressed', 'true');
  });

  it('forwards clicks', async () => {
    const onClick = vi.fn();
    render(<Chip onClick={onClick}>Art</Chip>);
    await userEvent.click(screen.getByRole('button', { name: 'Art' }));
    expect(onClick).toHaveBeenCalledOnce();
  });

  it('renders a zero count rather than hiding it', () => {
    render(<Chip count={0}>Empty</Chip>);
    expect(screen.getByRole('button')).toHaveTextContent('0');
  });
});

describe('IconButton', () => {
  it('gives an icon-only control an accessible name', () => {
    render(<IconButton icon={<svg />} label="Search" />);
    expect(screen.getByRole('button', { name: 'Search' })).toBeInTheDocument();
  });
});

describe('ProgressBar', () => {
  it('exposes progressbar semantics', () => {
    render(<ProgressBar value={64} />);
    const bar = screen.getByRole('progressbar');
    expect(bar).toHaveAttribute('aria-valuenow', '64');
    expect(bar).toHaveAttribute('aria-valuemin', '0');
    expect(bar).toHaveAttribute('aria-valuemax', '100');
  });

  it('keeps the real figure in aria while clamping the fill to 100%', () => {
    const { container } = render(<ProgressBar value={1111} />);
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '1111');
    const fill = container.querySelector('[role="progressbar"] > div') as HTMLElement;
    expect(fill.style.width).toBe('100%');
  });

  it('clamps negative values to zero', () => {
    const { container } = render(<ProgressBar value={-20} />);
    const fill = container.querySelector('[role="progressbar"] > div') as HTMLElement;
    expect(fill.style.width).toBe('0%');
  });
});

describe('DotIndicator', () => {
  it('states the percentage in words so colour is not the only signal', () => {
    render(<DotIndicator percent={87} />);
    expect(screen.getByRole('img', { name: /87 percent/ })).toBeInTheDocument();
  });
});

describe('Avatar', () => {
  it('falls back to initials when no image is supplied', () => {
    render(<Avatar name="Amara Osei" />);
    expect(screen.getByRole('img', { name: 'Amara Osei' })).toHaveTextContent('AO');
  });

  it('derives a single initial from a single-word name', () => {
    render(<Avatar name="Rowan" />);
    expect(screen.getByRole('img', { name: 'Rowan' })).toHaveTextContent('R');
  });

  it('uses the name as alt text when an image is supplied', () => {
    render(<Avatar name="Rowan Hale" src="/r.jpg" />);
    expect(screen.getByRole('img', { name: 'Rowan Hale' }).tagName).toBe('IMG');
  });

  it('collapses the overflow into a +N chip', () => {
    render(
      <AvatarGroup max={2} total={1697}>
        <Avatar name="One Person" />
        <Avatar name="Two Person" />
        <Avatar name="Three Person" />
      </AvatarGroup>,
    );
    expect(screen.getByText('+1695')).toBeInTheDocument();
  });
});

describe('RailItem', () => {
  it('marks the active destination with aria-current', () => {
    render(
      <Rail>
        <RailItem icon={<svg />} label="Discover" active />
        <RailItem icon={<svg />} label="Settings" />
      </Rail>,
    );
    expect(screen.getByRole('button', { name: 'Discover' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(screen.getByRole('button', { name: 'Settings' })).not.toHaveAttribute('aria-current');
  });
});

describe('Timeline', () => {
  const start = new Date('2026-01-01T00:00:00Z');
  const end = new Date('2026-01-11T00:00:00Z');

  it('projects a marker onto the track as a percentage', () => {
    const { container } = render(
      <Timeline
        start={start}
        end={end}
        markers={[{ id: 'm', at: new Date('2026-01-06T00:00:00Z'), label: 'Midpoint' }]}
      />,
    );
    const marker = container.querySelector('[title="Midpoint"]') as HTMLElement;
    expect(marker.style.left).toBe('50%');
  });

  it('clamps markers that fall outside the window', () => {
    const { container } = render(
      <Timeline
        start={start}
        end={end}
        markers={[
          { id: 'before', at: new Date('2025-06-01T00:00:00Z'), label: 'Before' },
          { id: 'after', at: new Date('2027-06-01T00:00:00Z'), label: 'After' },
        ]}
      />,
    );
    expect((container.querySelector('[title="Before"]') as HTMLElement).style.left).toBe('0%');
    expect((container.querySelector('[title="After"]') as HTMLElement).style.left).toBe('100%');
  });

  it('does not divide by zero when start equals end', () => {
    const { container } = render(
      <Timeline
        start={start}
        end={start}
        markers={[{ id: 'm', at: start, label: 'Only' }]}
      />,
    );
    expect((container.querySelector('[title="Only"]') as HTMLElement).style.left).toBe('0%');
  });
});

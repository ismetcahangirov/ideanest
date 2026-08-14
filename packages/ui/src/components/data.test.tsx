import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
  type TableSort,
} from './data/Table';
import { Pagination } from './data/Pagination';
import { EmptyState } from './data/EmptyState';
import { Skeleton, SkeletonCard, SkeletonGroup, SkeletonText } from './data/Skeleton';
import { InlineAlert } from './data/InlineAlert';

/**
 * Appearance is reviewed in Storybook. These tests cover BEHAVIOUR and
 * ACCESSIBILITY — the wiring that breaks silently and still ships.
 */

function renderTable(sort: TableSort | null, onSortChange = vi.fn()) {
  return render(
    <Table caption="Recent pledges" sort={sort} onSortChange={onSortChange}>
      <TableHead>
        <TableRow interactive={false}>
          <TableHeaderCell sortKey="backer">Backer</TableHeaderCell>
          <TableHeaderCell align="right" sortKey="amount">
            Amount
          </TableHeaderCell>
          <TableHeaderCell>Status</TableHeaderCell>
        </TableRow>
      </TableHead>
      <TableBody>
        <TableRow>
          <TableCell>Amara Osei</TableCell>
          <TableCell align="right">£48.00</TableCell>
          <TableCell>Settled</TableCell>
        </TableRow>
      </TableBody>
    </Table>,
  );
}

describe('Table', () => {
  it('is a real table whose caption is its accessible name', () => {
    renderTable(null);
    expect(screen.getByRole('table', { name: 'Recent pledges' })).toBeInTheDocument();
  });

  it('exposes column headers as columnheaders', () => {
    renderTable(null);
    expect(screen.getAllByRole('columnheader')).toHaveLength(3);
    expect(screen.getByRole('columnheader', { name: /Backer/ })).toBeInTheDocument();
  });

  it('reports aria-sort as none on a sortable but unsorted column', () => {
    renderTable(null);
    expect(screen.getByRole('columnheader', { name: /Backer/ })).toHaveAttribute(
      'aria-sort',
      'none',
    );
    expect(screen.getByRole('columnheader', { name: /Amount/ })).toHaveAttribute(
      'aria-sort',
      'none',
    );
  });

  it('leaves aria-sort off a column that cannot be sorted', () => {
    renderTable(null);
    expect(screen.getByRole('columnheader', { name: 'Status' })).not.toHaveAttribute('aria-sort');
  });

  it('reflects the controlled sort state on the sorted column only', () => {
    const { rerender } = renderTable({ key: 'amount', direction: 'ascending' });
    expect(screen.getByRole('columnheader', { name: /Amount/ })).toHaveAttribute(
      'aria-sort',
      'ascending',
    );
    expect(screen.getByRole('columnheader', { name: /Backer/ })).toHaveAttribute(
      'aria-sort',
      'none',
    );

    rerender(
      <Table
        caption="Recent pledges"
        sort={{ key: 'amount', direction: 'descending' }}
        onSortChange={vi.fn()}
      >
        <TableHead>
          <TableRow interactive={false}>
            <TableHeaderCell align="right" sortKey="amount">
              Amount
            </TableHeaderCell>
          </TableRow>
        </TableHead>
      </Table>,
    );
    expect(screen.getByRole('columnheader', { name: /Amount/ })).toHaveAttribute(
      'aria-sort',
      'descending',
    );
  });

  it('puts a real button in the sortable header and asks for ascending first', async () => {
    const onSortChange = vi.fn();
    renderTable(null, onSortChange);

    await userEvent.click(screen.getByRole('button', { name: 'Amount' }));
    expect(onSortChange).toHaveBeenCalledWith({ key: 'amount', direction: 'ascending' });
  });

  it('reverses the live column on a second click', async () => {
    const onSortChange = vi.fn();
    renderTable({ key: 'amount', direction: 'ascending' }, onSortChange);

    await userEvent.click(screen.getByRole('button', { name: 'Amount' }));
    expect(onSortChange).toHaveBeenCalledWith({ key: 'amount', direction: 'descending' });
  });

  it('starts a different column ascending rather than inheriting the direction', async () => {
    const onSortChange = vi.fn();
    renderTable({ key: 'amount', direction: 'descending' }, onSortChange);

    await userEvent.click(screen.getByRole('button', { name: 'Backer' }));
    expect(onSortChange).toHaveBeenCalledWith({ key: 'backer', direction: 'ascending' });
  });

  it('does not make a column sortable without an onSortChange handler', () => {
    render(
      <Table caption="Read-only">
        <TableHead>
          <TableRow interactive={false}>
            <TableHeaderCell sortKey="backer">Backer</TableHeaderCell>
          </TableRow>
        </TableHead>
      </Table>,
    );
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('scrolls inside a named, focusable region', () => {
    renderTable(null);
    const region = screen.getByRole('region', { name: 'Recent pledges' });
    expect(region).toHaveAttribute('tabindex', '0');
    expect(within(region).getByRole('table')).toBeInTheDocument();
  });

  it('lets the scroll region be named independently of the caption', () => {
    render(<Table caption="Payout ledger" scrollLabel="Payout ledger, scrollable" />);
    expect(screen.getByRole('region', { name: 'Payout ledger, scrollable' })).toBeInTheDocument();
  });
});

describe('Pagination', () => {
  it('is a named navigation landmark', () => {
    render(<Pagination page={3} pageCount={12} onPageChange={vi.fn()} />);
    expect(screen.getByRole('navigation', { name: 'Pagination' })).toBeInTheDocument();
  });

  it('marks the current page with aria-current and no other page', () => {
    render(<Pagination page={3} pageCount={12} onPageChange={vi.fn()} />);
    expect(screen.getByRole('button', { name: 'Page 3' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('button', { name: 'Page 2' })).not.toHaveAttribute('aria-current');
  });

  it('states the position for a screen reader without a live region', () => {
    const { container } = render(<Pagination page={3} pageCount={12} onPageChange={vi.fn()} />);
    expect(screen.getByText('Page 3 of 12')).toBeInTheDocument();
    expect(container.querySelector('[aria-live]')).toBeNull();
  });

  it('disables previous on the first page and next on the last', () => {
    const { rerender } = render(<Pagination page={1} pageCount={12} onPageChange={vi.fn()} />);
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Next page' })).toBeEnabled();

    rerender(<Pagination page={12} pageCount={12} onPageChange={vi.fn()} />);
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled();
  });

  it('reports the requested page', async () => {
    const onPageChange = vi.fn();
    render(<Pagination page={3} pageCount={12} onPageChange={onPageChange} />);

    await userEvent.click(screen.getByRole('button', { name: 'Next page' }));
    expect(onPageChange).toHaveBeenCalledWith(4);
  });

  it('hides the ellipsis from the accessibility tree', () => {
    render(<Pagination page={42} pageCount={99} onPageChange={vi.fn()} />);
    const gaps = screen.getAllByText('…');
    expect(gaps.length).toBeGreaterThan(0);
    for (const gap of gaps) expect(gap).toHaveAttribute('aria-hidden', 'true');
  });

  it('handles a single page with both controls inert', () => {
    render(<Pagination page={1} pageCount={1} onPageChange={vi.fn()} />);
    expect(screen.getByRole('button', { name: 'Page 1' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('button', { name: 'Previous page' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled();
  });

  it('renders nothing when there are no pages', () => {
    const { container } = render(<Pagination page={1} pageCount={0} onPageChange={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('clamps a page outside the range instead of trusting it', () => {
    render(<Pagination page={999} pageCount={5} onPageChange={vi.fn()} />);
    expect(screen.getByRole('button', { name: 'Page 5' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('button', { name: 'Next page' })).toBeDisabled();
  });
});

describe('EmptyState', () => {
  it('puts the meaning in a heading', () => {
    render(<EmptyState title="No campaigns yet" />);
    expect(screen.getByRole('heading', { name: 'No campaigns yet' })).toBeInTheDocument();
  });

  it('respects the requested heading level', () => {
    render(<EmptyState title="No campaigns yet" headingLevel={2} />);
    expect(screen.getByRole('heading', { level: 2 })).toBeInTheDocument();
  });

  it('hides the decorative icon from the accessibility tree', () => {
    const { container } = render(<EmptyState title="No campaigns yet" />);
    const icon = container.querySelector('svg');
    expect(icon).not.toBeNull();
    expect(icon?.closest('[aria-hidden="true"]')).not.toBeNull();
  });

  it('distinguishes the filtered case, because the recovery differs', () => {
    const { container } = render(<EmptyState variant="filtered" title="Nothing matched" />);
    expect(container.firstElementChild).toHaveAttribute('data-variant', 'filtered');
  });
});

describe('Skeleton', () => {
  it('hides the placeholder and marks the container busy', () => {
    const { container } = render(
      <SkeletonGroup label="Loading projects">
        <Skeleton />
      </SkeletonGroup>,
    );
    const group = container.firstElementChild;
    expect(group).toHaveAttribute('aria-busy', 'true');
    expect(group?.querySelector('.skeleton-shimmer')).toHaveAttribute('aria-hidden', 'true');
  });

  it('announces the message the caller wrote, not the shimmer', () => {
    render(
      <SkeletonGroup label="Loading projects">
        <SkeletonCard />
      </SkeletonGroup>,
    );
    expect(screen.getByRole('status')).toHaveTextContent('Loading projects');
  });

  it('renders the requested number of text lines', () => {
    const { container } = render(<SkeletonText lines={5} />);
    expect(container.querySelectorAll('[data-skeleton-line]')).toHaveLength(5);
  });

  it('renders a single line without crashing on the short-last-line rule', () => {
    const { container } = render(<SkeletonText lines={1} />);
    expect(container.querySelectorAll('[data-skeleton-line]')).toHaveLength(1);
  });
});

describe('InlineAlert', () => {
  it('asserts for danger and warning only', () => {
    const { rerender } = render(<InlineAlert variant="danger">Payment failed</InlineAlert>);
    expect(screen.getByRole('alert')).toHaveTextContent('Payment failed');

    rerender(<InlineAlert variant="warning">Closing soon</InlineAlert>);
    expect(screen.getByRole('alert')).toHaveTextContent('Closing soon');

    rerender(<InlineAlert variant="info">Draft saved</InlineAlert>);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

    rerender(<InlineAlert variant="success">Goal reached</InlineAlert>);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('pairs every variant with an icon, so colour is never the only signal', () => {
    for (const variant of ['info', 'success', 'warning', 'danger'] as const) {
      const { container, unmount } = render(<InlineAlert variant={variant}>Message</InlineAlert>);
      const icon = container.querySelector('svg');
      expect(icon, `${variant} renders no icon`).not.toBeNull();
      expect(icon).toHaveAttribute('aria-hidden', 'true');
      unmount();
    }
  });

  it('gives the icon-only dismiss button an accessible name and fires it', async () => {
    const onDismiss = vi.fn();
    render(
      <InlineAlert variant="info" onDismiss={onDismiss} dismissLabel="Dismiss draft saved">
        Draft saved
      </InlineAlert>,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Dismiss draft saved' }));
    expect(onDismiss).toHaveBeenCalledOnce();
  });

  it('has no dismiss control unless a handler is supplied', () => {
    render(<InlineAlert variant="danger">Payment failed</InlineAlert>);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});

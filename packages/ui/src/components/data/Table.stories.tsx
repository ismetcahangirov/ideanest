import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
  type TableSort,
} from './Table';
import { Tag } from '../Tag/Tag';

const meta = {
  title: 'Data/Table',
  component: Table,
  parameters: {
    layout: 'padded',
    docs: {
      description: {
        component:
          'No zebra striping: surface colour encodes state in this system, so ' +
          'spending it on alternating rows would make a genuinely highlighted ' +
          'row indistinguishable. Money arrives pre-formatted as a string.',
      },
    },
  },
} satisfies Meta<typeof Table>;

export default meta;
type Story = StoryObj<typeof meta>;

interface Pledge {
  id: string;
  backer: string;
  tier: string;
  /** Pre-formatted. Never a float — see CLAUDE.md §3. */
  amount: string;
  status: 'settled' | 'failed';
}

const PLEDGES: Pledge[] = [
  { id: '1', backer: 'Amara Osei', tier: 'Early bird', amount: '£48.00', status: 'settled' },
  { id: '2', backer: 'Rowan Hale', tier: 'Standard', amount: '£1,240.00', status: 'settled' },
  { id: '3', backer: 'Tomas Vidal', tier: 'Founder', amount: '£12,480.00', status: 'failed' },
  { id: '4', backer: 'Nia Brand', tier: 'Standard', amount: '£96.50', status: 'settled' },
];

export const Default: Story = {
  args: { caption: 'Recent pledges' },
  render: (args) => (
    <Table {...args}>
      <TableHead>
        <TableRow interactive={false}>
          <TableHeaderCell>Backer</TableHeaderCell>
          <TableHeaderCell>Tier</TableHeaderCell>
          <TableHeaderCell align="right">Amount</TableHeaderCell>
          <TableHeaderCell>Status</TableHeaderCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {PLEDGES.map((pledge) => (
          <TableRow key={pledge.id}>
            <TableCell>{pledge.backer}</TableCell>
            <TableCell>{pledge.tier}</TableCell>
            <TableCell align="right">{pledge.amount}</TableCell>
            <TableCell>
              <Tag variant={pledge.status === 'failed' ? 'danger' : 'success'}>
                {pledge.status === 'failed' ? 'Payment failed' : 'Settled'}
              </Tag>
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  ),
};

/**
 * The caller owns the sort and re-orders its own data — the rows on screen are
 * usually one page of a server-side query, so the table must not shuffle them.
 */
export const Sortable: Story = {
  args: { caption: 'Recent pledges, sortable' },
  render: (args) => {
    const [sort, setSort] = useState<TableSort | null>({ key: 'amount', direction: 'descending' });
    const ordered = [...PLEDGES].sort((a, b) => {
      if (!sort) return 0;
      const key = sort.key === 'amount' ? 'amount' : 'backer';
      const result = a[key].localeCompare(b[key]);
      return sort.direction === 'ascending' ? result : -result;
    });

    return (
      <Table {...args} sort={sort} onSortChange={setSort}>
        <TableHead>
          <TableRow interactive={false}>
            <TableHeaderCell sortKey="backer">Backer</TableHeaderCell>
            <TableHeaderCell>Tier</TableHeaderCell>
            <TableHeaderCell align="right" sortKey="amount">
              Amount
            </TableHeaderCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {ordered.map((pledge) => (
            <TableRow key={pledge.id}>
              <TableCell>{pledge.backer}</TableCell>
              <TableCell>{pledge.tier}</TableCell>
              <TableCell align="right">{pledge.amount}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    );
  },
};

/**
 * Wide tables scroll inside a named, focusable region. Without `tabIndex`, the
 * right-hand columns are reachable by pointer only.
 */
export const Scrollable: Story = {
  args: { caption: 'Payout ledger', scrollLabel: 'Payout ledger, scrollable' },
  render: (args) => (
    <div className="max-w-md">
      <Table {...args}>
        <TableHead>
          <TableRow interactive={false}>
            {['Reference', 'Backer', 'Method', 'Settled on', 'Fee'].map((heading) => (
              <TableHeaderCell key={heading} className="whitespace-nowrap">
                {heading}
              </TableHeaderCell>
            ))}
            <TableHeaderCell align="right">Amount</TableHeaderCell>
          </TableRow>
        </TableHead>
        <TableBody>
          <TableRow>
            <TableCell className="whitespace-nowrap">PLG-4820</TableCell>
            <TableCell className="whitespace-nowrap">Amara Osei</TableCell>
            <TableCell className="whitespace-nowrap">Card</TableCell>
            <TableCell className="whitespace-nowrap">14 Aug 2026</TableCell>
            <TableCell className="whitespace-nowrap">£1.44</TableCell>
            <TableCell align="right">£48.00</TableCell>
          </TableRow>
        </TableBody>
      </Table>
    </div>
  ),
};

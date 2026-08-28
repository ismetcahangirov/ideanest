import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../lib/api/problem';
import {
  readShippingAddress,
  saveShippingAddress,
  type PostalAddress,
  type StoredAddress,
} from '../../lib/fulfilment/api';
import { ShippingAddressForm } from './ShippingAddressForm';
import { shippingAddressFormCopyFrom } from '../../lib/i18n/fulfilment-copy';
import { translatorFor } from '../../test-copy';
/*
 * The copy the route would have resolved, built from `messages/en.json` by the same function it
 * calls — issue #324. Retyping the sentences here would give a test that passes whatever the
 * catalogue says, which is the opposite of what it is for.
 */
const COPY = shippingAddressFormCopyFrom(translatorFor('account.fulfilment'));

/**
 * §4.8's PM-07 — issue #290.
 *
 * WHAT THESE COVER:
 *
 *   - **every field is sent on every save, including the empty ones.** The endpoint replaces
 *     the address entirely, and a form that sent only what changed is how somebody who moved
 *     house ends up with the old flat number on the new street.
 *   - **204 renders a blank form**, because the pledge exists and the address does not — a
 *     different fact from "no such pledge", which is a 404 and an error.
 *   - a locked address is read-only rather than a form that will be refused. PM-08 freezes
 *     them before labels are printed, and the row still reads.
 *   - the fields an envelope cannot be addressed without are required here as well as on the
 *     service, so nobody waits for a round trip to be told.
 */

vi.mock('../../lib/fulfilment/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../lib/fulfilment/api')>()),
  readShippingAddress: vi.fn(),
  saveShippingAddress: vi.fn(),
}));

const readMock = vi.mocked(readShippingAddress);
const saveMock = vi.mocked(saveShippingAddress);

const ADDRESS: PostalAddress = {
  recipient: 'Aysel Q',
  line1: '12 Nizami küç.',
  line2: '',
  locality: 'Baku',
  region: '',
  postcode: 'AZ1000',
  countryCode: 'AZ',
  phone: '',
};

function stored(overrides: Partial<StoredAddress> = {}): StoredAddress {
  return {
    pledgeId: 'pledge-1',
    address: ADDRESS,
    locked: false,
    lockedAt: null,
    updatedAt: '2026-08-20T09:00:00Z',
    ...overrides,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  saveMock.mockImplementation(async () => stored());
});

afterEach(cleanup);

describe('ShippingAddressForm', () => {
  it('renders a blank form when the pledge has no address yet', async () => {
    readMock.mockResolvedValue(null);
    render(<ShippingAddressForm copy={COPY} pledgeId="pledge-1" />);

    expect(await screen.findByText('No address yet')).toBeInTheDocument();
    expect(screen.getByLabelText(/Full name/u)).toHaveValue('');
  });

  it('sends every field, empty ones included, because the write replaces the address', async () => {
    readMock.mockResolvedValue(stored());
    const user = userEvent.setup();
    render(<ShippingAddressForm copy={COPY} pledgeId="pledge-1" />);

    await user.click(await screen.findByRole('button', { name: 'Save address' }));

    await waitFor(() => expect(saveMock).toHaveBeenCalled());
    const [, sent] = saveMock.mock.calls[0] as [string, PostalAddress];
    expect(Object.keys(sent).sort()).toEqual(
      ['countryCode', 'line1', 'line2', 'locality', 'phone', 'postcode', 'recipient', 'region'].sort(),
    );
    expect(sent.line2).toBe('');
  });

  it('refuses to submit without the fields an envelope needs', async () => {
    readMock.mockResolvedValue(
      stored({ address: { ...ADDRESS, recipient: '', line1: '', locality: '', countryCode: '' } }),
    );
    const user = userEvent.setup();
    render(<ShippingAddressForm copy={COPY} pledgeId="pledge-1" />);

    await user.click(await screen.findByRole('button', { name: 'Save address' }));

    expect(screen.getAllByText('A parcel cannot be addressed without this.')).toHaveLength(4);
    expect(saveMock).not.toHaveBeenCalled();
  });

  it('reads a locked address without offering a write that would be refused', async () => {
    readMock.mockResolvedValue(stored({ locked: true, lockedAt: '2026-08-21T09:00:00Z' }));
    render(<ShippingAddressForm copy={COPY} pledgeId="pledge-1" />);

    expect(await screen.findByText('This address is locked')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save address' })).not.toBeInTheDocument();
    expect(screen.getByLabelText(/Full name/u)).toBeDisabled();
    expect(screen.getByLabelText(/Full name/u)).toHaveValue('Aysel Q');
  });

  it('says what the service said when the pledge is not the reader’s', async () => {
    readMock.mockRejectedValue(new ApiError(404, { detail: 'No such pledge.' }));
    render(<ShippingAddressForm copy={COPY} pledgeId="somebody-elses" />);

    expect(await screen.findByText('No such pledge.')).toBeInTheDocument();
  });

  it('renders nothing when there is no session', async () => {
    readMock.mockRejectedValue(new ApiError(401));
    const { container } = render(<ShippingAddressForm copy={COPY} pledgeId="pledge-1" />);

    await waitFor(() => expect(container).toBeEmptyDOMElement());
  });
});

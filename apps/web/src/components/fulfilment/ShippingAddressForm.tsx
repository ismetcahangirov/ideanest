'use client';

import { useEffect, useState, type FormEvent } from 'react';
import { Field, InlineAlert, Pill, Skeleton, SkeletonGroup, TextInput } from '@ideanest/ui';
import { ApiError } from '../../lib/api/problem';
import {
  EMPTY_ADDRESS,
  readShippingAddress,
  saveShippingAddress,
  type PostalAddress,
} from '../../lib/fulfilment/api';
import { formatExactTime } from '../../lib/time';
import { useRouteLocale } from '../../lib/i18n/useRouteLocale';

/**
 * §4.8's PM-07 — where one pledge's reward goes. Issue #290.
 *
 * <h2>Every field is sent on every save, including the empty ones</h2>
 *
 * `ShippingAddressController` calls the endpoint a `PATCH` that replaces the address entirely,
 * and gives the reason: merging a partial address "is how somebody who moved house ends up
 * with the old flat number on the new street". So this form has no notion of a changed field.
 * `lib/fulfilment/api.ts` is where the trimming happens, so the one place that talks to the
 * endpoint is the one place that decides what a blank field means.
 *
 * <h2>A locked address is read-only, not a form that will fail</h2>
 *
 * PM-08 lets a creator freeze every address before printing labels. The row still reads —
 * somebody has to be able to see where their parcel is going — and the write is refused. A
 * disabled form with the reason above it is the honest rendering; offering the controls and
 * catching the 409 would be inviting somebody to retype an address that cannot be saved.
 *
 * <h2>The country is two letters, and the form says so rather than guessing</h2>
 *
 * The service stores the destination country outside the encrypted envelope, on
 * `pledges.shipping_country`, because §17.4's envelope is not queryable and shipping rules are
 * decided by region. It is an ISO 3166-1 alpha-2 code. A `<select>` of every country would be
 * a list of 249 options this application would have to own and translate; a two-letter field
 * with the format stated is smaller and does not go stale. It is upper-cased on the way out.
 *
 * <h2>Nothing here is validated beyond "the service needs these"</h2>
 *
 * Address formats differ per country in ways no client-side rule survives — a postcode is
 * mandatory in Germany, absent in Ireland, and alphanumeric in the Netherlands. The required
 * fields are the ones an envelope cannot be addressed without, and everything else is the
 * service's to refuse.
 */

export interface ShippingAddressFormProps {
  readonly pledgeId: string;
}

type Status = 'loading' | 'ready' | 'failed' | 'signed-out';

/** The fields, in the order an address is written. */
const FIELDS: ReadonlyArray<{
  readonly key: keyof PostalAddress;
  readonly label: string;
  readonly autoComplete: string;
  readonly required: boolean;
  readonly hint?: string;
}> = [
  { key: 'recipient', label: 'Full name', autoComplete: 'name', required: true },
  { key: 'line1', label: 'Address', autoComplete: 'address-line1', required: true },
  {
    key: 'line2',
    label: 'Apartment, floor, or company',
    autoComplete: 'address-line2',
    required: false,
  },
  { key: 'locality', label: 'City or town', autoComplete: 'address-level2', required: true },
  { key: 'region', label: 'Region or state', autoComplete: 'address-level1', required: false },
  { key: 'postcode', label: 'Postcode', autoComplete: 'postal-code', required: false },
  {
    key: 'countryCode',
    label: 'Country',
    autoComplete: 'country',
    required: true,
    hint: 'Two letters — AZ, TR, GB, DE.',
  },
  {
    key: 'phone',
    label: 'Phone',
    autoComplete: 'tel',
    required: false,
    hint: 'Some carriers will not deliver without one.',
  },
];

export function ShippingAddressForm({ pledgeId }: ShippingAddressFormProps) {
  const locale = useRouteLocale();
  const [status, setStatus] = useState<Status>('loading');
  const [address, setAddress] = useState<PostalAddress>(EMPTY_ADDRESS);
  const [locked, setLocked] = useState(false);
  const [lockedAt, setLockedAt] = useState<string | null>(null);
  const [updatedAt, setUpdatedAt] = useState<string | null>(null);
  const [neverGiven, setNeverGiven] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  const [missing, setMissing] = useState<Readonly<Record<string, string>>>({});

  useEffect(() => {
    const controller = new AbortController();

    void (async () => {
      try {
        const stored = await readShippingAddress(pledgeId, controller.signal);
        if (controller.signal.aborted) return;

        if (stored === null) {
          /*
           * 204: the pledge exists and the address does not. That is the state a blank form
           * renders, and it is a different fact from "no such pledge" — which arrives as a
           * 404 and is handled below.
           */
          setNeverGiven(true);
          setStatus('ready');
          return;
        }

        setAddress(stored.address);
        setLocked(stored.locked);
        setLockedAt(stored.lockedAt);
        setUpdatedAt(stored.updatedAt);
        setStatus('ready');
      } catch (cause) {
        if (controller.signal.aborted) return;
        if (cause instanceof DOMException && cause.name === 'AbortError') return;

        if (cause instanceof ApiError && cause.status === 401) {
          setStatus('signed-out');
          return;
        }
        setError(
          cause instanceof ApiError
            ? (cause.problem?.detail ??
              cause.problem?.title ??
              'That pledge could not be found, or it is not yours.')
            : 'The service could not be reached. Check your connection and try again.',
        );
        setStatus('failed');
      }
    })();

    return () => controller.abort();
  }, [pledgeId]);

  async function submit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    if (busy || locked) return;

    const blanks: Record<string, string> = {};
    for (const field of FIELDS) {
      if (field.required && address[field.key].trim() === '') {
        blanks[field.key] = 'A parcel cannot be addressed without this.';
      }
    }
    if (Object.keys(blanks).length > 0) {
      setMissing(blanks);
      setSaved(false);
      return;
    }

    setMissing({});
    setError(null);
    setBusy(true);

    try {
      const stored = await saveShippingAddress(pledgeId, address);
      setAddress(stored.address);
      setLocked(stored.locked);
      setLockedAt(stored.lockedAt);
      setUpdatedAt(stored.updatedAt);
      setNeverGiven(false);
      setSaved(true);
    } catch (cause) {
      setSaved(false);
      setError(
        cause instanceof ApiError
          ? (cause.problem?.detail ?? cause.problem?.title ?? 'The address was refused.')
          : 'The service could not be reached. Check your connection and try again.',
      );
    } finally {
      setBusy(false);
    }
  }

  if (status === 'signed-out') return null;

  if (status === 'loading') {
    return (
      <SkeletonGroup label="Loading your address" className="flex flex-col gap-4">
        {[0, 1, 2, 3].map((row) => (
          <Skeleton key={row} height="3.5rem" />
        ))}
      </SkeletonGroup>
    );
  }

  if (status === 'failed') {
    return (
      <InlineAlert variant="danger" title="This address could not be loaded">
        <p>{error}</p>
      </InlineAlert>
    );
  }

  return (
    <form onSubmit={submit} noValidate className="flex max-w-[34rem] flex-col gap-5">
      {locked && (
        <InlineAlert variant="warning" title="This address is locked">
          <p>
            The creator has closed changes
            {lockedAt !== null && lockedAt !== '' ? ` on ${formatExactTime(lockedAt, locale)}` : ''}, which
            usually means labels are being printed. Message them through the campaign if it is
            wrong.
          </p>
        </InlineAlert>
      )}

      {neverGiven && !locked && (
        <InlineAlert variant="info" title="No address yet">
          <p>The creator cannot send anything until this is filled in.</p>
        </InlineAlert>
      )}

      {error !== null && (
        <InlineAlert variant="danger" title="It was not saved">
          <p>{error}</p>
        </InlineAlert>
      )}

      {saved && error === null && (
        <InlineAlert variant="success" title="Saved">
          <p>
            This is where the reward goes
            {updatedAt !== null && updatedAt !== ''
              ? `, as of ${formatExactTime(updatedAt, locale)}`
              : ''}
            .
          </p>
        </InlineAlert>
      )}

      {FIELDS.map((field) => (
        <Field
          key={field.key}
          label={field.label}
          required={field.required}
          hint={field.hint}
          error={missing[field.key]}
        >
          <TextInput
            name={field.key}
            autoComplete={field.autoComplete}
            disabled={locked}
            /*
             * The country code is the one field with a length the service actually constrains,
             * and a longer value is refused rather than truncated — so the cap is here, where
             * somebody can see it stop, rather than as a surprise on submit.
             */
            {...(field.key === 'countryCode' ? { maxLength: 2 } : {})}
            value={address[field.key]}
            onChange={(event) => {
              setAddress((previous) => ({ ...previous, [field.key]: event.target.value }));
              setSaved(false);
            }}
          />
        </Field>
      ))}

      {!locked && (
        <div>
          <Pill type="submit" disabled={busy}>
            {busy ? 'Saving' : 'Save address'}
          </Pill>
        </div>
      )}
    </form>
  );
}

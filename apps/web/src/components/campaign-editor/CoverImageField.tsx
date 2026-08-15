'use client';

import { useState } from 'react';
import { Field, FileDropZone, InlineAlert, Pill, TextInput } from '@ideanest/ui';
import type { CoverImage } from '../../lib/projects/api';
import {
  COVER_MIN_HEIGHT,
  COVER_MIN_WIDTH,
  describeSize,
  measureImage,
  meetsCoverMinimum,
} from '../../lib/projects/coverImage';

/**
 * The cover image, as much of it as exists today.
 *
 * WHAT IS MISSING, PLAINLY. There is no media table, no object storage, and no
 * uploader (contract §3, docs/architecture.md §13). Nothing this form does puts
 * a file anywhere. What the project can record is a URL and the dimensions that
 * go with it, so that is what this collects: the creator gives an address, the
 * browser loads it to read its intrinsic size, and the two are saved together.
 * The interface says so rather than implying an upload that will not happen.
 *
 * THE DROP ZONE STILL EARNS ITS PLACE. A creator holding a photograph needs to
 * know whether it satisfies the 1024×576 minimum (§5.3) BEFORE they publish it
 * somewhere and paste the address, and a local file is the only thing that can
 * answer that. So the zone measures and reports, and says in words that nothing
 * was uploaded. A control that quietly did nothing would be worse than no
 * control.
 *
 * The dimensions are read in the browser because the browser is the only place
 * they can be read while the server has never seen the file. When the media
 * pipeline lands it reads them during ingestion and this component is replaced,
 * not extended.
 */
export interface CoverImageFieldProps {
  /** The address as typed, which may not yet be a saved cover. */
  url: string;
  cover: CoverImage | null;
  disabled?: boolean;
  /** A message from the form's own validation, e.g. a saved cover that is small. */
  error?: string;
  onUrlChange: (url: string) => void;
  onAccept: (cover: CoverImage) => void;
  onRemove: () => void;
}

type Note = { tone: 'success' | 'info' | 'danger'; title?: string; text: string };

const MINIMUM = `${COVER_MIN_WIDTH}×${COVER_MIN_HEIGHT}`;

export function CoverImageField({
  url,
  cover,
  disabled = false,
  error,
  onUrlChange,
  onAccept,
  onRemove,
}: CoverImageFieldProps) {
  const [checking, setChecking] = useState(false);
  const [note, setNote] = useState<Note | null>(null);

  async function useAddress(): Promise<void> {
    const address = url.trim();
    if (address === '') {
      setNote({ tone: 'danger', text: 'Enter the address of an image first.' });
      return;
    }

    setChecking(true);
    setNote(null);
    try {
      const size = await measureImage(address);

      if (!meetsCoverMinimum(size)) {
        setNote({
          tone: 'danger',
          text: `That image is ${describeSize(size)} pixels, which is below the ${MINIMUM} minimum. It has not been saved as the cover.`,
        });
        return;
      }

      onAccept({ url: address, width: size.width, height: size.height });
      setNote({ tone: 'success', text: `Cover set from a ${describeSize(size)} pixel image.` });
    } catch (cause) {
      setNote({
        tone: 'danger',
        text:
          cause instanceof Error
            ? cause.message
            : 'That address could not be loaded as an image.',
      });
    } finally {
      setChecking(false);
    }
  }

  async function checkFile(file: File): Promise<void> {
    setChecking(true);
    setNote(null);
    try {
      const size = await measureImage(file);

      setNote(
        meetsCoverMinimum(size)
          ? {
              tone: 'info',
              title: 'This file is large enough',
              text: `${file.name} is ${describeSize(size)} pixels. Nothing has been uploaded — publish it somewhere public and paste the address below.`,
            }
          : {
              tone: 'danger',
              title: 'This file is too small',
              text: `${file.name} is ${describeSize(size)} pixels, below the ${MINIMUM} minimum. A larger version is needed.`,
            },
      );
    } catch (cause) {
      setNote({
        tone: 'danger',
        text: cause instanceof Error ? cause.message : 'That file could not be read as an image.',
      });
    } finally {
      setChecking(false);
    }
  }

  return (
    <Field
      grouped
      label="Cover image"
      hint={`Shown on the discovery grid and at the top of the project page. At least ${MINIMUM} pixels.`}
      error={error}
    >
      <div className="flex flex-col gap-3">
        <InlineAlert variant="info" title="Uploading arrives with the media pipeline">
          This form cannot store a file yet. Give the address of an image that is already published
          and its size is read here in the browser.
        </InlineAlert>

        {cover !== null && (
          <figure className="overflow-hidden rounded-lg border border-white/8 bg-surface-2">
            {/* Decorative: the caption below carries the same information as
                text, and a preview of an image the creator just chose has no
                description this component could invent. */}
            <img src={cover.url} alt="" className="aspect-video w-full object-cover" />
            <figcaption className="flex flex-wrap items-center justify-between gap-3 px-4 py-3 text-[13px] text-white/64">
              <span>Cover is {describeSize(cover)} pixels</span>
              <Pill variant="ghost" size="sm" disabled={disabled} onClick={onRemove}>
                Remove cover
              </Pill>
            </figcaption>
          </figure>
        )}

        <div className="flex flex-col gap-2 sm:flex-row">
          <TextInput
            type="url"
            inputMode="url"
            value={url}
            disabled={disabled}
            // The `Field` label names a group here, so it cannot name this
            // control; without a label of its own the input would be announced
            // as "edit text" and nothing else.
            aria-label="Cover image address"
            placeholder="https://images.example.com/cover.jpg"
            className="sm:flex-1"
            onChange={(event) => onUrlChange(event.target.value)}
          />
          <Pill variant="ghost" disabled={disabled || checking} onClick={() => void useAddress()}>
            {checking ? 'Checking' : 'Use this image'}
          </Pill>
        </div>

        <FileDropZone
          accept="image/*"
          disabled={disabled}
          prompt="Or drop a file here to check its size"
          dragPrompt="Release to measure this image"
          buttonLabel="Choose a file to measure"
          hint="Measured in your browser. Nothing is uploaded."
          onFiles={(files) => {
            const [first] = files;
            if (first) void checkFile(first);
          }}
        />

        {/*
          The outcome of pressing a button has to be announced, or a
          screen-reader user presses "Use this image" and hears nothing at all.

          Two regions rather than one: `InlineAlert` already carries
          role="alert" for danger — a measurement that failed is worth
          interrupting for — and putting an alert inside a polite live region
          gives assistive technology two contradictory instructions about the
          same text. The polite region is rendered on every pass so that it is
          registered before anything is put into it.
        */}
        <div role="status" aria-live="polite" className="empty:hidden">
          {note !== null && note.tone !== 'danger' && (
            <InlineAlert variant={note.tone} title={note.title}>
              {note.text}
            </InlineAlert>
          )}
        </div>

        {note !== null && note.tone === 'danger' && (
          <InlineAlert variant="danger" title={note.title}>
            {note.text}
          </InlineAlert>
        )}
      </div>
    </Field>
  );
}

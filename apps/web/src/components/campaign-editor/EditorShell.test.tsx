import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { EditorShell } from './EditorShell';
import { EDITOR_TABS } from './tabs';

/**
 * Appearance is reviewed in Storybook. These cover the navigation contract: the
 * section a creator is on, the sections that do not exist yet, and reaching all
 * of them with a keyboard.
 */

function renderShell() {
  return render(
    <EditorShell projectId="project-1" active="basics" title="A field recorder" state="DRAFT">
      <p>The basics form</p>
    </EditorShell>
  );
}

describe('EditorShell', () => {
  it('names the navigation and lists every section once', () => {
    renderShell();

    const nav = screen.getByRole('navigation', { name: 'Campaign sections' });
    expect(nav).toBeInTheDocument();

    for (const tab of EDITOR_TABS) {
      expect(screen.getByText(tab.label)).toBeInTheDocument();
    }
  });

  /*
   * Sections are routes, not panels in this document, so they are links with
   * `aria-current` rather than an ARIA tablist. A tablist would promise
   * same-document panels and would take the arrow keys away from the browser.
   */
  it('marks the current section as the page, and only that one', () => {
    renderShell();

    const current = screen.getAllByRole('link').filter((link) => link.hasAttribute('aria-current'));
    expect(current).toHaveLength(1);
    expect(current[0]).toHaveAccessibleName('Basics');
    expect(current[0]).toHaveAttribute('aria-current', 'page');
  });

  it('points the available section at its own route', () => {
    renderShell();

    expect(screen.getByRole('link', { name: 'Basics' })).toHaveAttribute(
      'href',
      '/projects/project-1/edit/basics',
    );
  });

  /*
   * The rewards, story, pre-launch and review routes belong to #34, #35, #39 and
   * #37. A disabled tab says "not finished yet"; a stub page would leave the
   * creator unable to tell that from "broken".
   */
  it('renders a section whose route does not exist as unavailable, not as a link', () => {
    renderShell();

    const rewards = screen.getByRole('button', { name: /Rewards/ });
    expect(rewards).toHaveAttribute('aria-disabled', 'true');
    expect(rewards).toHaveAccessibleName('Rewards, not available yet');
    expect(screen.queryByRole('link', { name: /Rewards/ })).not.toBeInTheDocument();
  });

  /*
   * `aria-disabled` rather than `disabled`, so the tab keeps its place in the
   * tab order. A keyboard user is told the section exists and is not ready;
   * `disabled` would mean they never learn it is there at all.
   */
  it('keeps every section reachable by keyboard, in order', async () => {
    const user = userEvent.setup();
    renderShell();

    const expected = ['Basics', 'Rewards', 'Story', 'Pre-launch', 'Review'];

    for (const label of expected) {
      await user.tab();
      expect(document.activeElement).toHaveAccessibleName(
        label === 'Basics' ? 'Basics' : `${label}, not available yet`,
      );
    }
  });

  it('says which state the campaign is in, in words', () => {
    renderShell();
    // Colour alone must never carry meaning (docs/ui-kit.md §9.2).
    expect(screen.getByText('Draft')).toBeInTheDocument();
  });

  it('renders the tab content it was given', () => {
    renderShell();
    expect(screen.getByText('The basics form')).toBeInTheDocument();
  });
});

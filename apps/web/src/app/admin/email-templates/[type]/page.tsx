import type { Metadata } from 'next';
import { EmailTemplateEditor } from '../../../../components/admin/EmailTemplateEditor';
import { privatePageMetadata } from '../../../../lib/seo/metadata';

/**
 * One template's copy, and every version of it — §12.3, issue #315.
 *
 * <p>The type is in the path rather than in a query parameter because it is what the page is
 * about: an administrator sends somebody a link to the notice they are arguing over, and a
 * query parameter would be a link to the list with a filter applied.
 *
 * <p><strong>The title does not name the template.</strong> `generateMetadata` could read the
 * type from the params and put it in the tab, and it is deliberately not done: the value is a
 * `NotificationType`, so the title would be `PLEDGE_CONFIRMED` — a constant from the codebase
 * rendered as a page title. The heading below carries it, where it reads as what it is.
 *
 * <p>`privatePageMetadata` for the reason every console route gives.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Email copy',
  description: 'The shipped copy, the override if there is one, and every version anybody has written.',
});

export default async function EmailTemplatePage({
  params,
}: {
  readonly params: Promise<{ readonly type: string }>;
}) {
  const { type } = await params;

  return (
    <div className="max-w-[880px]">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        {type}
      </h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        The subject and the first paragraph are what an administrator may rewrite. The
        headline, the button label and a type&rsquo;s conditional second paragraph stay in the
        shipped catalogue — a button with no label is a broken email rather than a badly
        worded one.
      </p>

      <div className="mt-8">
        <EmailTemplateEditor type={type} />
      </div>
    </div>
  );
}

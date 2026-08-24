import type { Metadata } from 'next';
import { EmailTemplateIndex } from '../../../components/admin/EmailTemplateIndex';
import { privatePageMetadata } from '../../../lib/seo/metadata';

/**
 * §4.11's AD-15: edit, preview, test send — §12.3, issue #315.
 *
 * <p>Two of the three arrived with #86 and this epic adds the third. What #86 said was
 * missing was exactly this: "editing means storing a template, versioning it, and deciding
 * who may change what a payment-failure notice says; that is a screen and a schema".
 *
 * <p>`privatePageMetadata` for the reason every console route gives.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Email templates',
  description: 'The copy of every message the platform sends, and every edit anybody has made to it.',
});

export default function EmailTemplatesPage() {
  return (
    <div className="max-w-[880px]">
      <h1 className="text-2xl font-semibold tracking-[-0.03em] text-white sm:text-3xl">
        Email templates
      </h1>
      <p className="mt-2 max-w-[62ch] text-sm text-white/64">
        The copy that ships with the code is what the platform sends unless somebody has
        rewritten it. An edit appends a version rather than changing anything in place, so
        &ldquo;what did this notice say in March&rdquo; stays answerable.
      </p>

      <div className="mt-8">
        <EmailTemplateIndex />
      </div>
    </div>
  );
}

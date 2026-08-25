import type { Metadata } from 'next';
import { getLocale, getTranslations } from 'next-intl/server';
import { AccountPageHeader } from '../../../../components/account/AccountPageHeader';
import { LanguagePanel } from '../../../../components/settings/LanguagePanel';
import { localeOrDefault } from '../../../../lib/i18n/locale';
import { privatePageMetadata } from '../../../../lib/seo/metadata';

/**
 * THE METADATA IS ENGLISH WHILE THE PAGE IS NOT, and that is a choice rather than an omission.
 *
 * `lib/seo/metadata.ts` builds one title template, one description and one `og:locale` for the
 * whole application, and `SITE_LANGUAGE` there records why the document stays `lang="en"`: the
 * public routes are cached shared renders and #123's locale-prefixed URLs are what a
 * translated one would need. A `generateMetadata` here would translate the browser tab of a
 * `noindex, nofollow` page — nothing reads it but the reader, and the one string it would
 * change is already the `<h1>` below in their own language.
 */
export const metadata: Metadata = privatePageMetadata({
  title: 'Language',
  description: 'Choose the language IdeaNest writes to you in.',
});

/**
 * `/settings/language` — §4.2's P-10, issue #280.
 *
 * <h2>A server component that reads the catalogue, wrapping a client component that writes</h2>
 *
 * The strings come from `getTranslations` here and go down as props. The alternative —
 * `useTranslations` inside the panel — needs a `NextIntlClientProvider` above it, and that
 * provider serialises a catalogue into the route's first load so the browser can look up nine
 * strings it could have been handed. `LanguagePanel` explains the same trade from its side.
 *
 * `getLocale()` is the language this render is actually in, negotiated from the cookie by
 * `src/i18n/request.ts`. It is passed down so the control opens on the language the reader is
 * looking at rather than on a guess, and so it is a **server** value: reading the cookie inside
 * the client component would answer `null` on the server and the real cookie on hydration,
 * which is a mismatch for everybody whose language is not the default.
 *
 * It is run through `localeOrDefault` on the way past. next-intl types its locale as a plain
 * string, and the four tags are the vocabulary `lib/i18n/locale.ts` owns — narrowing at the
 * boundary is what stops a value the catalogue happened to accept from reaching a `<select>`
 * whose options cannot express it.
 *
 * <h2>Dynamic, and already paid for</h2>
 *
 * Reading a cookie makes this render dynamic. `src/i18n/request.ts` argues at length that this
 * is free on `/settings/*`, which is behind authentication and rendered per person already, and
 * expensive on the cached public routes — which is why the catalogue covers this half of the
 * application and not that one.
 *
 * <h2>It is a page rather than a section of `/settings`</h2>
 *
 * §4.2 lists P-10 beside the notification preferences, and they are not one screen: the
 * notification grid is a matrix of channels a person tunes over time, and this is one choice
 * made once. Putting them together would also mean one Save over two unrelated writes — a
 * language that failed to save because a notification row did.
 */
export default async function LanguageSettingsPage() {
  const t = await getTranslations('settings.language');
  const common = await getTranslations('common');
  const locale = localeOrDefault(await getLocale());

  return (
    <>
      <AccountPageHeader title={t('title')}>{t('intro')}</AccountPageHeader>

      <div className="mt-8">
        <LanguagePanel
          serverLocale={locale}
          copy={{
            fieldLabel: t('fieldLabel'),
            fieldHint: t('fieldHint'),
            save: t('save'),
            saving: common('saving'),
            saved: t('saved'),
            failed: t('failed'),
            currencyHeading: t('currencyHeading'),
            currencyValue: t('currencyValue'),
            currencyNote: t('currencyNote'),
          }}
        />
      </div>
    </>
  );
}

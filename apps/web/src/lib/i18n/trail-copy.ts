import type { TrailCopy } from '../seo/structured-data/breadcrumb';

/** What `getTranslations('common.trail')` hands back, narrowed to what is used. */
type Translator = (key: string) => string;

/**
 * The four fixed breadcrumb steps, in the page's language — issue #123.
 *
 * <h2>Why a builder rather than four `t()` calls at the call site</h2>
 *
 * The same split every `*-copy.ts` in this directory makes: the pure builder is what a test
 * can run against `messages/*.json` directly, so an assertion is made against the words the
 * application will draw rather than against words retyped into a test. Seven routes emit a
 * trail; four `t()` calls repeated seven times is four keys that eventually disagree.
 *
 * <h2>Why the markup is localised at all</h2>
 *
 * `BreadcrumbList` is a claim about the page as it is presented. Before #123 the names were
 * English constants, which was true while the application had one language and became false
 * the moment `/ru/discover` existed: the markup said `Home → Discover` above a page whose own
 * navigation said `Главная → Обзор`. Structured data that contradicts the visible page is the
 * one kind a search engine is entitled to distrust wholesale.
 */
export function trailCopyFrom(t: Translator): TrailCopy {
  return {
    home: t('home'),
    discover: t('discover'),
    categories: t('categories'),
    collections: t('collections'),
  };
}

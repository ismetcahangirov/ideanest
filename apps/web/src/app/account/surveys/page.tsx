import type { Metadata } from 'next';
import { AccountPageHeader } from '../../../components/account/AccountPageHeader';
import { SurveyList } from '../../../components/surveys/SurveyList';
import { privatePageMetadata } from '../../../lib/seo/metadata';

export const metadata: Metadata = privatePageMetadata({
  title: 'Surveys',
  description: 'What creators still need from you before they can ship your rewards.',
});

/**
 * `/account/surveys` — §4.8 PM-05 and PM-06, issue #289.
 *
 * A shell around a client boundary. The list is built from this account's own backings, behind
 * a bearer token, so there is nothing a server render could produce.
 */
export default function SurveysPage() {
  return (
    <>
      <AccountPageHeader title="Surveys">
        After a campaign funds, its creator asks what they need in order to send your reward —
        a size, a colour, an edition. You can change an answer while the survey is open.
      </AccountPageHeader>

      <div className="mt-8">
        <SurveyList />
      </div>
    </>
  );
}

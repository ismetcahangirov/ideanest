import { useMemo } from 'react';
import { Stack, useLocalSearchParams } from 'expo-router';
import { Image } from 'expo-image';
import { Pressable, ScrollView, Share, StyleSheet, View } from 'react-native';
import { formatMoney } from '@ideanest/money';
import { siteUrl } from '../../../api/config';
import { useProjectPage, useProjectRewards, useProjectUpdates } from '../../../api/queries';
import { ErrorState, Loading, OfflineNotice } from '../../../components/states';
import { FadeUp } from '../../../components/motion';
import { ProgressBar } from '../../../components/progress';
import { Body, CardTitle, Display, Heading, Meta, Story, Subheading } from '../../../components/text';
import { shareUrlFor } from '../../../lib/links';
import { storyParagraphs } from '../../../lib/story';
import { colors, radius, size, spacing } from '../../../theme';

/**
 * The campaign page — issue #113. **Story, rewards, updates, comments, and a
 * persistent call to action.**
 *
 * <h2>The call to action is pinned, and the page behind it does not animate</h2>
 *
 * `docs/motion-system.md` §5 puts a checkout surface on the *minimal* budget:
 * motion decreases as money gets closer, because every animation near a payment
 * reads as hesitation. So the story fades up as it arrives — this is still a
 * reading surface — and the bar at the bottom never moves. It is drawn once,
 * outside the scroll view, so it is on screen at the moment somebody decides
 * rather than at the moment they reach the end.
 *
 * <h2>Comments are read, not written</h2>
 *
 * §4.6's comment thread is a moderated surface with reporting, replies and rate
 * limits, and half of it is meaningless without an account this application
 * cannot yet create (see `lib/use-session.ts`). What is here is the count and a
 * link to the web thread — which is the honest version of "comments", rather
 * than a composer whose submit button cannot work.
 */

const styles = StyleSheet.create({
  cover: { width: '100%', aspectRatio: 16 / 9, backgroundColor: colors.surface3 },
  body: { padding: size.cardPaddingLarge, gap: size.sectionGap },
  section: { gap: spacing[3] },
  figures: { flexDirection: 'row', gap: size.cardPaddingLarge, flexWrap: 'wrap' },
  figure: { gap: spacing[1] },
  reward: {
    backgroundColor: colors.surface2,
    borderRadius: radius.lg,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.border,
    padding: size.cardPaddingSmall,
    gap: spacing[2],
  },
  update: { gap: spacing[1], paddingVertical: spacing[2] },
  actions: {
    flexDirection: 'row',
    gap: spacing[3],
    padding: size.cardPaddingSmall,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.border,
    backgroundColor: colors.surface2,
  },
  primary: {
    flex: 1,
    minHeight: size.touchTarget,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.full,
    backgroundColor: colors.lime500,
  },
  secondary: {
    minHeight: size.touchTarget,
    paddingHorizontal: size.cardPaddingSmall,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: radius.full,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.borderStrong,
  },
});

export default function ProjectScreen() {
  const { creatorSlug, projectSlug } = useLocalSearchParams<{
    creatorSlug: string;
    projectSlug: string;
  }>();

  const project = useProjectPage(creatorSlug, projectSlug);
  const rewards = useProjectRewards(project.data?.id);
  const updates = useProjectUpdates(project.data?.id);

  const paragraphs = useMemo(() => storyParagraphs(project.data?.story), [project.data?.story]);

  if (project.data === undefined) {
    if (project.isLoading) return <Loading label="Loading campaign" />;
    return (
      <ErrorState
        title="Could not load this campaign"
        detail="It may have been withdrawn, or this device may be offline."
      />
    );
  }

  const page = project.data;
  const title = page.title ?? 'Campaign';
  const percent = fundedPercent(page.pledged?.amount, page.goal?.amount);

  return (
    <View style={{ flex: 1 }}>
      <Stack.Screen options={{ title, headerBackTitle: 'Back' }} />

      <ScrollView contentInsetAdjustmentBehavior="automatic">
        {page.coverImage?.url == null ? null : (
          <Image
            source={page.coverImage.url}
            style={styles.cover}
            contentFit="cover"
            accessibilityElementsHidden
            importantForAccessibility="no"
          />
        )}

        <View style={styles.body}>
          {project.isStale && project.isError ? (
            <OfflineNotice detail="Saved on this device. The funding figures may have moved since." />
          ) : null}

          <FadeUp index={0}>
            <View style={styles.section}>
              <Heading>{title}</Heading>
              {page.blurb == null ? null : <Body>{page.blurb}</Body>}
              {page.creator?.name == null ? null : <Meta>by {page.creator.name}</Meta>}
            </View>
          </FadeUp>

          <FadeUp index={1}>
            <View style={styles.section}>
              <View style={styles.figures}>
                <View style={styles.figure}>
                  <Display>{formatMoney(page.pledged)}</Display>
                  <Meta>pledged of {formatMoney(page.goal)}</Meta>
                </View>
                <View style={styles.figure}>
                  <Display>{String(page.backersCount ?? 0)}</Display>
                  <Meta>backers</Meta>
                </View>
              </View>
              <ProgressBar completionPercent={percent} label={`Funding progress for ${title}`} />
            </View>
          </FadeUp>

          {paragraphs.length === 0 ? null : (
            <FadeUp index={2}>
              <View style={styles.section}>
                <Subheading>About this campaign</Subheading>
                {paragraphs.map((paragraph, index) => (
                  <Story key={index}>{paragraph}</Story>
                ))}
                <Meta>
                  Formatting, images and video are on the web page. Tap Share to open it.
                </Meta>
              </View>
            </FadeUp>
          )}

          {(rewards.data?.rewards ?? []).length === 0 ? null : (
            <FadeUp index={3}>
              <View style={styles.section}>
                <Subheading>Rewards</Subheading>
                {(rewards.data?.rewards ?? []).map((reward) => (
                  <View key={reward.id} style={styles.reward}>
                    <CardTitle>{reward.title ?? ''}</CardTitle>
                    <Meta tone="secondary">{formatMoney(reward.price)}</Meta>
                    {reward.description == null ? null : (
                      <Body numberOfLines={4}>{reward.description}</Body>
                    )}
                    {/* In words, because "6 left" in lime and "sold out" in grey
                        is colour carrying the difference on its own. */}
                    <Meta>
                      {reward.remainingQuantity == null
                        ? 'Unlimited'
                        : reward.remainingQuantity === 0
                          ? 'None left'
                          : `${reward.remainingQuantity} left`}
                    </Meta>
                  </View>
                ))}
              </View>
            </FadeUp>
          )}

          {(updates.data?.updates ?? []).length === 0 ? null : (
            <FadeUp index={4}>
              <View style={styles.section}>
                <Subheading>Updates</Subheading>
                {(updates.data?.updates ?? []).map((update) => (
                  <View key={update.number} style={styles.update}>
                    <CardTitle numberOfLines={2}>{update.title ?? ''}</CardTitle>
                    <Meta>{update.publishedAt ?? ''}</Meta>
                  </View>
                ))}
              </View>
            </FadeUp>
          )}

          <View style={styles.section}>
            <Subheading>Comments</Subheading>
            <Body>
              The comment thread is on the web page for now. Tap Share to open this campaign
              there.
            </Body>
          </View>
        </View>
      </ScrollView>

      {/*
        Outside the ScrollView, so it does not scroll away. §5's minimal motion
        budget: it is drawn, it does not arrive.
      */}
      <View style={styles.actions}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={`Back ${title} on the web`}
          style={styles.primary}
          onPress={() => void openOnWeb(creatorSlug, projectSlug)}
        >
          {/* Near-black on lime. The only legible pairing (docs/ui-kit.md §9.1). */}
          <CardTitle tone="onLime">Back this campaign</CardTitle>
        </Pressable>

        <Pressable
          accessibilityRole="button"
          accessibilityLabel={`Share ${title}`}
          style={styles.secondary}
          onPress={() => void share(title, creatorSlug, projectSlug)}
        >
          <CardTitle>Share</CardTitle>
        </Pressable>
      </View>
    </View>
  );
}

/**
 * Percent funded, computed here because `ProjectPageResponse` does not carry it.
 *
 * `Card` does — the feed's projection has `completionPercent` — and this one has
 * the two amounts instead. Both are decimal strings, so the division is done on
 * numbers only after the strings have been checked, and only to produce a bar
 * width and a rounded label. Nothing downstream treats the result as money.
 */
function fundedPercent(pledged: string | undefined, goal: string | undefined): string {
  if (pledged === undefined || goal === undefined) return '0';
  if (!/^\d+(\.\d+)?$/.test(pledged) || !/^\d+(\.\d+)?$/.test(goal)) return '0';

  const target = Number(goal);
  if (target <= 0) return '0';

  return ((Number(pledged) / target) * 100).toFixed(2);
}

async function openOnWeb(creatorSlug: string, projectSlug: string): Promise<void> {
  const { openBrowserAsync } = await import('expo-web-browser');
  await openBrowserAsync(shareUrlFor(siteUrl(), creatorSlug, projectSlug));
}

async function share(title: string, creatorSlug: string, projectSlug: string): Promise<void> {
  const url = shareUrlFor(siteUrl(), creatorSlug, projectSlug);
  /*
   * The https URL, never `ideanest://`. A recipient without the application
   * installed must be able to open what they were sent; the universal-link
   * association (#114) is what makes the same string open the application for
   * everybody who does have it.
   */
  await Share.share({ message: `${title} — ${url}`, url, title });
}

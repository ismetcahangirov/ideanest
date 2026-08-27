import { ActivityIndicator, StyleSheet, View } from 'react-native';
import { colors, radius, size, spacing } from '../theme';
import { Body, Meta, Subheading } from './text';

/**
 * The three things every list screen has to be able to say: nothing here,
 * something went wrong, and this is what we had saved.
 *
 * They live together because they are one decision. A screen that renders an
 * empty state when it should have rendered a stale-but-real list is the bug
 * #115 exists to prevent, and having the three side by side is what makes the
 * ordering obvious at each call site: **cached data first, then the error, then
 * the empty state.**
 */

const styles = StyleSheet.create({
  centre: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: size.cardPaddingLarge,
    gap: spacing[3],
  },
  notice: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing[3],
    padding: spacing[3],
    borderRadius: radius.md,
    backgroundColor: colors.surface3,
    borderLeftWidth: 3,
    borderLeftColor: colors.warning,
  },
});

export function Loading({ label }: { readonly label: string }) {
  return (
    <View style={styles.centre}>
      {/* An indicator with no accessible name is announced as nothing at all. */}
      <ActivityIndicator color={colors.lime500} accessibilityLabel={label} />
    </View>
  );
}

export function EmptyState({
  title,
  detail,
}: {
  readonly title: string;
  readonly detail: string;
}) {
  return (
    <View style={styles.centre}>
      <Subheading>{title}</Subheading>
      <Body style={{ textAlign: 'center' }}>{detail}</Body>
    </View>
  );
}

export function ErrorState({
  title,
  detail,
}: {
  readonly title: string;
  readonly detail: string;
}) {
  return (
    <View style={styles.centre} accessibilityRole="alert">
      <Subheading>{title}</Subheading>
      <Body style={{ textAlign: 'center' }}>{detail}</Body>
    </View>
  );
}

/**
 * The banner over a list that is being read from the cache — issue #115.
 *
 * <h2>Why the list is not simply shown</h2>
 *
 * The data is real; it is just old. A funding figure is the one number on this
 * platform where "real but old" and "current" are different facts — a campaign
 * shown at 80% may have closed — so a screen that renders cached money without
 * saying so is a screen that lies quietly. The banner is the difference between
 * offline support and a stale page.
 *
 * A warning stripe **and** the words. Colour alone never carries meaning
 * (CLAUDE.md §2), and this is the message somebody most needs when they cannot
 * see the screen properly on a train.
 */
export function OfflineNotice({ detail }: { readonly detail: string }) {
  return (
    <View style={styles.notice} accessibilityRole="alert">
      <Meta tone="secondary">{detail}</Meta>
    </View>
  );
}

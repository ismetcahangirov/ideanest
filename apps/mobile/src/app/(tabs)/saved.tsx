import { Link } from 'expo-router';
import { FlashList } from '@shopify/flash-list';
import { Pressable, StyleSheet, View } from 'react-native';
import { useSavedProjects } from '../../api/queries';
import { EmptyState, ErrorState, Loading, OfflineNotice } from '../../components/states';
import { CardTitle, Meta } from '../../components/text';
import { useSession } from '../../lib/use-session';
import { colors, radius, size, spacing } from '../../theme';

/**
 * What somebody kept — one of the two lists issue #115 promises offline.
 *
 * <h2>The stale case is the feature, not an edge case</h2>
 *
 * `lib/offline.ts` persists this query, so opening the tab on a plane shows the
 * list that was there last time. The screen's job is to be honest about which of
 * the two it is showing, which is what `isStale && isError` answers: data on
 * screen, and a refetch that failed. Without the notice the two are
 * indistinguishable, and the difference matters — the list is what somebody
 * checks before deciding whether they still have time to back something.
 *
 * <h2>No funding figures here, deliberately</h2>
 *
 * `/v1/me/saved` answers titles and slugs and no money, and that is the right
 * shape for a list that can be a week old. A cached percentage would be the one
 * number a backer acts on, shown at whatever it was last Tuesday.
 */

const styles = StyleSheet.create({
  content: { padding: size.cardGap, gap: spacing[3] },
  row: {
    backgroundColor: colors.surface2,
    borderRadius: radius.lg,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.border,
    padding: size.cardPaddingSmall,
    gap: spacing[2],
    minHeight: size.touchTarget,
  },
  pressed: { backgroundColor: colors.surface3 },
  separator: { height: spacing[3] },
});

export default function SavedScreen() {
  const { signedIn } = useSession();
  const saved = useSavedProjects(signedIn);

  if (!signedIn) {
    return (
      <EmptyState
        title="Sign in to see what you saved"
        detail="Saved campaigns follow the account rather than the phone."
      />
    );
  }

  const items = saved.data?.items ?? [];

  if (items.length === 0) {
    if (saved.isLoading) return <Loading label="Loading saved campaigns" />;
    if (saved.isError) {
      return (
        <ErrorState
          title="Could not load your saved campaigns"
          detail="Nothing was cached on this device yet, so there is nothing to show offline."
        />
      );
    }
    return (
      <EmptyState
        title="Nothing saved yet"
        detail="Save a campaign from its page and it will be here, with or without a connection."
      />
    );
  }

  return (
    <FlashList
      data={items}
      keyExtractor={(item) => item.projectId ?? ''}
      contentContainerStyle={styles.content}
      ItemSeparatorComponent={Separator}
      ListHeaderComponent={
        // Shown only when a refetch actually failed. A cache being used while
        // the network is fine is not worth a banner.
        saved.isError ? (
          <OfflineNotice detail="Showing your saved campaigns from this device. They may be out of date." />
        ) : undefined
      }
      renderItem={({ item }) => (
        <Link
          href={{
            pathname: '/projects/[creatorSlug]/[projectSlug]',
            params: {
              creatorSlug: item.creatorSlug ?? '',
              projectSlug: item.projectSlug ?? '',
            },
          }}
          asChild
        >
          <Pressable
            accessibilityRole="link"
            accessibilityLabel={item.title ?? 'Saved campaign'}
            style={({ pressed }) => [styles.row, pressed && styles.pressed]}
          >
            <CardTitle numberOfLines={2}>{item.title ?? 'Saved campaign'}</CardTitle>
            <Meta>{item.creatorSlug ?? ''}</Meta>
          </Pressable>
        </Link>
      )}
    />
  );
}

function Separator() {
  return <View style={styles.separator} />;
}

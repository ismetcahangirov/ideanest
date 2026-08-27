import { Pressable, StyleSheet } from 'react-native';
import { Link, Tabs } from 'expo-router';
import { Meta } from '../../components/text';
import { colors, fontSize, fontWeight, radius, size, spacing } from '../../theme';

/**
 * The four things somebody does on a phone — issue #110's navigation.
 *
 * <h2>Four, and why not more</h2>
 *
 * §4.1's mobile-marked features fall into discovery, search, what somebody kept,
 * and what somebody backed. Creating and editing a campaign are not here: they
 * are `[W]` in §4 and they are long-form form work that a phone is the wrong
 * place for. A tab bar that offered them would be a promise the screens behind
 * it do not keep.
 *
 * <h2>Every tab has a label, not only an icon</h2>
 *
 * CLAUDE.md §2 requires an accessible name on an icon-only control, and the
 * cheapest way to satisfy it is not to have icon-only controls. A visible label
 * also survives the case an icon never does: somebody using the application in a
 * language whose conventions the icon was not drawn for.
 *
 * <h2>Account is in the header, not in the tab bar — issue #29</h2>
 *
 * The four above are things somebody does. Signing in, turning on the biometric
 * lock and signing out are settings a phone owes, and a fifth tab for them would
 * make the tab bar a list of five things of which one is not like the others.
 * The header is where both platforms already put it, and it is on every tab
 * rather than on one so that "where do I sign in" has the same answer from
 * wherever somebody happens to be when they ask.
 */

const styles = StyleSheet.create({
  account: {
    minHeight: size.touchTarget,
    // Height rather than a square: a 44pt-wide target around a word this short
    // would clip it, and the platform header already spaces the sides.
    justifyContent: 'center',
    paddingHorizontal: spacing[4],
    borderRadius: radius.full,
  },
  accountPressed: { backgroundColor: colors.surface3 },
});

/**
 * The header control.
 *
 * <p>A word rather than a glyph, for the same reason the tabs below carry
 * labels: CLAUDE.md §2 requires an accessible name on an icon-only control, and
 * the cheapest way to satisfy that is not to have one.
 */
function AccountLink() {
  return (
    <Link href="/account" asChild>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Account"
        style={({ pressed }) => [styles.account, pressed && styles.accountPressed]}
      >
        <Meta tone="secondary">Account</Meta>
      </Pressable>
    </Link>
  );
}

export default function TabsLayout() {
  return (
    <Tabs
      screenOptions={{
        headerStyle: { backgroundColor: colors.surface1 },
        headerTintColor: colors.textPrimary,
        headerShadowVisible: false,
        sceneStyle: { backgroundColor: colors.surface1 },
        tabBarStyle: { backgroundColor: colors.surface2, borderTopColor: colors.border },
        // Lime is the active state because lime means "this is where you are",
        // which is the same "act now" the token means everywhere else. Inactive
        // is `--text-tertiary`, which §2.2 measures at 4.9:1 on `--surface-1`.
        tabBarActiveTintColor: colors.lime500,
        tabBarInactiveTintColor: colors.textTertiary,
        tabBarLabelStyle: { fontSize: fontSize.xxs, fontWeight: fontWeight.medium },
        headerRight: () => <AccountLink />,
      }}
    >
      <Tabs.Screen name="index" options={{ title: 'Discover' }} />
      <Tabs.Screen name="search" options={{ title: 'Search' }} />
      <Tabs.Screen name="saved" options={{ title: 'Saved' }} />
      <Tabs.Screen name="pledges" options={{ title: 'Pledges' }} />
    </Tabs>
  );
}

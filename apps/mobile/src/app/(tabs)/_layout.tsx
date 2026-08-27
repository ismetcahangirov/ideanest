import { Tabs } from 'expo-router';
import { colors, fontSize, fontWeight } from '../../theme';

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
 */
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
      }}
    >
      <Tabs.Screen name="index" options={{ title: 'Discover' }} />
      <Tabs.Screen name="search" options={{ title: 'Search' }} />
      <Tabs.Screen name="saved" options={{ title: 'Saved' }} />
      <Tabs.Screen name="pledges" options={{ title: 'Pledges' }} />
    </Tabs>
  );
}

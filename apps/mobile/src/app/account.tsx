import { useCallback, useEffect, useState } from 'react';
import { ScrollView, StyleSheet, Switch, View } from 'react-native';
import { useRouter } from 'expo-router';
import { useQueryClient } from '@tanstack/react-query';
import { Button } from '../components/form';
import { Body, Heading, Meta, Subheading } from '../components/text';
import { signOut } from '../lib/auth';
import { biometricCapability, canLock, type BiometricCapability } from '../lib/biometrics';
import { forgetPersistedCache } from '../lib/offline';
import { disableLock, enableLock } from '../lib/session';
import { useSession } from '../lib/use-session';
import { colors, radius, size, spacing } from '../theme';

/**
 * The account screen — where #29's lock is turned on, and where a session ends.
 *
 * <h2>Why this is a stack screen and not a fifth tab</h2>
 *
 * `(tabs)/_layout.tsx` argues that the tab bar carries §4.1's four
 * mobile-marked activities and that a fifth entry would be a promise the screens
 * behind it do not keep. That argument still holds: this is not an activity, it
 * is the two settings a phone owes. It is reached from the header of every tab,
 * which is where a platform convention already puts it.
 *
 * <h2>The lock is offered only when the device can actually honour it</h2>
 *
 * A switch that turns on and then fails is worse than a switch that is not
 * there, because the failure arrives later and looks like a lost session. So
 * `biometricCapability()` is asked first and the control says which of the three
 * true things is true: there is no scanner, there is one with nothing enrolled,
 * or here is the switch. The middle case is the one worth naming — it is
 * actionable, and "unavailable" would send somebody looking for a bug in this
 * application rather than into their own settings.
 *
 * <h2>Signing out clears the offline cache, and that is not optional</h2>
 *
 * #115 persists the saved list and the pledge list to MMKV so they can be read
 * without a connection. Both are private, and a sign-out that left them on disk
 * would leave one account's pledges readable by the next person to open the
 * application. `queryClient.clear()` empties the in-memory cache and
 * `forgetPersistedCache()` removes the document from MMKV rather than waiting a
 * second for the persister's throttle to rewrite it — see that function for why
 * both are needed.
 */

const styles = StyleSheet.create({
  content: { padding: size.cardPaddingLarge, gap: spacing[8] },
  section: { gap: spacing[3] },
  card: {
    backgroundColor: colors.surface2,
    borderRadius: radius.lg,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.border,
    padding: size.cardPaddingSmall,
    gap: spacing[3],
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing[4],
    minHeight: size.touchTarget,
  },
  rowText: { flex: 1, gap: spacing[1] },
});

export default function AccountScreen() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const { signedIn, locked, unlocked } = useSession();

  const [capability, setCapability] = useState<BiometricCapability | null>(null);
  const [busy, setBusy] = useState(false);
  const [refused, setRefused] = useState(false);

  useEffect(() => {
    let live = true;
    void biometricCapability().then((answer) => {
      if (live) setCapability(answer);
    });
    return () => {
      live = false;
    };
  }, []);

  const toggleLock = useCallback(
    async (next: boolean): Promise<void> => {
      if (busy) return;
      setBusy(true);
      setRefused(false);
      try {
        // Both directions can be refused, and for the same reason: turning the
        // lock off has to read the token, which is what presents the prompt.
        const moved = next ? await enableLock() : await disableLock();
        if (!moved) setRefused(true);
      } finally {
        setBusy(false);
      }
    },
    [busy],
  );

  async function endIt(): Promise<void> {
    if (busy) return;
    setBusy(true);
    try {
      await signOut();
      queryClient.clear();
      forgetPersistedCache();
    } finally {
      setBusy(false);
    }
  }

  if (!signedIn) {
    return (
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.section}>
          <Heading>Account</Heading>
          <Body>
            Nobody is signed in on this phone. Discovery and search work without an account;
            your saved campaigns and pledges need one.
          </Body>
        </View>
        <Button label="Sign in" onPress={() => router.push('/sign-in')} />
      </ScrollView>
    );
  }

  return (
    <ScrollView contentContainerStyle={styles.content}>
      <View style={styles.section}>
        <Heading>Account</Heading>
        <Body>
          You are signed in on this phone. Profile, password and notification settings are on
          ideanest.az.
        </Body>
      </View>

      <View style={styles.section}>
        <Subheading>Unlock</Subheading>
        <View style={styles.card}>
          <View style={styles.row}>
            <View style={styles.rowText}>
              <Body tone="primary">{lockLabel(capability)}</Body>
              <Meta>{lockDetail(capability, locked, unlocked)}</Meta>
            </View>
            {capability !== null && canLock(capability) ? (
              <Switch
                value={locked}
                onValueChange={(next) => void toggleLock(next)}
                disabled={busy}
                accessibilityLabel={lockLabel(capability)}
                /*
                 * Lime when on, for the same reason every other active state on
                 * this platform is: §8.1's lime means "this is live". The thumb
                 * is the near-black that lime always carries.
                 */
                trackColor={{ false: colors.surface3, true: colors.lime500 }}
                thumbColor={locked ? colors.textOnLime : colors.textTertiary}
              />
            ) : null}
          </View>

          {refused ? (
            <Body accessibilityRole="alert" style={{ color: colors.danger }}>
              The device did not confirm it was you, so nothing changed.
            </Body>
          ) : null}
        </View>
      </View>

      <Button label="Sign out" variant="secondary" busy={busy} onPress={() => void endIt()} />
    </ScrollView>
  );
}

/** What to call the control, in the words of whatever the device actually has. */
function lockLabel(capability: BiometricCapability | null): string {
  switch (capability) {
    case 'face':
      return 'Require Face ID';
    case 'fingerprint':
      return 'Require your fingerprint';
    case 'other':
      return 'Require a device unlock';
    case 'not-enrolled':
      return 'Device unlock is not set up';
    case 'unavailable':
      return 'This phone has no biometric unlock';
    case null:
      return 'Checking what this phone can do';
  }
}

function lockDetail(
  capability: BiometricCapability | null,
  locked: boolean,
  unlocked: boolean,
): string {
  if (capability === null) return 'One moment.';
  if (capability === 'unavailable') {
    return 'Your session stays in the keychain, readable while the phone is unlocked.';
  }
  if (capability === 'not-enrolled') {
    return 'Add a fingerprint, a face or a passcode in your phone’s settings, then come back.';
  }
  if (!locked) {
    return 'Your session stays in the keychain, readable while the phone is unlocked.';
  }
  return unlocked
    ? 'On. This session is open until the app has been away for a few minutes.'
    : 'On. The next time your pledges are read, the phone will ask for you.';
}

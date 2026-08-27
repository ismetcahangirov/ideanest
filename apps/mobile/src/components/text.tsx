import { StyleSheet, Text as RNText, type TextProps as RNTextProps } from 'react-native';
import { colors, fontSize, fontWeight, lineHeight, tracking } from '../theme';

/**
 * Typography, as the four roles the screens actually use.
 *
 * <h2>Why roles rather than props</h2>
 *
 * A `<Text size={18} weight="500" tracking={-0.36}>` moves the decision to the
 * call site, and the decision is not the call site's to make: `docs/ui-kit.md`
 * §5.3 pairs a size with a weight and a tracking, and the pairing is the design.
 * Screens ask for a heading and get the heading, which is also what makes a
 * change to the scale a change to one file.
 *
 * Colour is a role too. `Body` is `--text-secondary` and `Heading` is
 * `--text-primary` because that is the hierarchy §2.2 describes; a screen that
 * needs `onLime` says so, because lime is the one surface where the ordinary
 * answer is invisible.
 */

type Tone = 'primary' | 'secondary' | 'tertiary' | 'onLime' | 'onWhite' | 'reading';

const TONE: Record<Tone, string> = {
  primary: colors.textPrimary,
  secondary: colors.textSecondary,
  tertiary: colors.textTertiary,
  onLime: colors.textOnLime,
  onWhite: colors.textOnWhite,
  reading: colors.textReading,
};

export interface TextProps extends RNTextProps {
  readonly tone?: Tone;
}

const styles = StyleSheet.create({
  display: {
    fontSize: fontSize.display,
    lineHeight: lineHeight.display,
    letterSpacing: tracking.display,
    fontWeight: fontWeight.semibold,
  },
  heading: {
    fontSize: fontSize.h2,
    lineHeight: lineHeight.h2,
    letterSpacing: tracking.h2,
    fontWeight: fontWeight.semibold,
  },
  subheading: {
    fontSize: fontSize.h3,
    lineHeight: lineHeight.h3,
    letterSpacing: tracking.h3,
    fontWeight: fontWeight.semibold,
  },
  cardTitle: {
    fontSize: fontSize.lg,
    lineHeight: lineHeight.cardTitle,
    letterSpacing: tracking.cardTitle,
    fontWeight: fontWeight.medium,
  },
  body: {
    fontSize: fontSize.base,
    lineHeight: lineHeight.body,
    letterSpacing: tracking.body,
    fontWeight: fontWeight.regular,
  },
  story: {
    fontSize: fontSize.base,
    lineHeight: lineHeight.story,
    letterSpacing: tracking.body,
    fontWeight: fontWeight.regular,
  },
  meta: {
    fontSize: fontSize.xs,
    lineHeight: lineHeight.body,
    letterSpacing: tracking.tag,
    fontWeight: fontWeight.medium,
  },
});

function role(style: object, defaultTone: Tone) {
  return function Role({ tone = defaultTone, style: override, ...rest }: TextProps) {
    return <RNText {...rest} style={[style, { color: TONE[tone] }, override]} />;
  };
}

/** The one figure a screen leads with. §7.7's headline figure. */
export const Display = role(styles.display, 'primary');

/** A section heading. */
export const Heading = role(styles.heading, 'primary');

/** A heading below a section heading. */
export const Subheading = role(styles.subheading, 'primary');

/** A card's title. 18px, medium, per §5.3. */
export const CardTitle = role(styles.cardTitle, 'primary');

/** Ordinary prose. */
export const Body = role(styles.body, 'secondary');

/**
 * Long-form campaign story. The one place §5.4 asks for a 1.75 line height,
 * and the one place the near-white reading colour is right.
 */
export const Story = role(styles.story, 'reading');

/** A tag, a count, a timestamp. */
export const Meta = role(styles.meta, 'tertiary');

import { useDeferredValue, useMemo, useState } from 'react';
import { Pressable, StyleSheet, TextInput, View } from 'react-native';
import { useSearchResults, useSuggestions, type Card } from '../../api/queries';
import { CampaignList } from '../../components/campaign-list';
import { EmptyState, ErrorState, Loading } from '../../components/states';
import { Body, Meta } from '../../components/text';
import { colors, fontSize, radius, size, spacing } from '../../theme';

/**
 * Search — issue #112's second half.
 *
 * <h2>Why `useDeferredValue` and not a debounce timer</h2>
 *
 * A `setTimeout` debounce is the usual answer and it is worse in the way that
 * matters on a phone: it delays the FIELD as well as the request, so the letters
 * appear late under the thumb. `useDeferredValue` keeps the input at full speed
 * and lets the expensive half — the query, and the list that re-renders with it
 * — lag behind by a render. There is no timer to tune and no timer to leak.
 *
 * The request is still not made for one or two characters: `useSuggestions`
 * refuses under two, and results wait for three. A single-letter full-text
 * search against a trigram index is the most expensive query on the platform and
 * the least useful.
 *
 * <h2>Suggestions are not results</h2>
 *
 * `/v1/search/suggest` answers categories, tags and locations — the things
 * `Taxonomy` has translated — and tapping one narrows the search rather than
 * opening a campaign. They are drawn as chips above the list so that the two
 * are not confused; a suggestion styled like a result is a tap somebody has to
 * undo.
 */

const MINIMUM_QUERY = 3;

const styles = StyleSheet.create({
  header: { gap: spacing[3], paddingBottom: spacing[2] },
  field: {
    backgroundColor: colors.surface3,
    borderRadius: radius.md,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.border,
    color: colors.textPrimary,
    fontSize: fontSize.base,
    paddingHorizontal: spacing[4],
    // The field is a touch target before it is a field.
    minHeight: size.touchTarget,
  },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing[2] },
  chip: {
    paddingVertical: spacing[2],
    paddingHorizontal: spacing[3],
    borderRadius: radius.full,
    backgroundColor: colors.surface3,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.border,
    minHeight: size.touchTarget,
    justifyContent: 'center',
  },
  chipSelected: { backgroundColor: colors.lime500, borderColor: colors.lime500 },
});

export default function SearchScreen() {
  const [term, setTerm] = useState('');
  const [category, setCategory] = useState<string | undefined>(undefined);

  const deferredTerm = useDeferredValue(term);
  const trimmed = deferredTerm.trim();
  const enabled = trimmed.length >= MINIMUM_QUERY || category !== undefined;

  const query = useMemo(
    () => ({
      q: trimmed === '' ? undefined : trimmed,
      // A list because the contract binds several; this screen offers one chip
      // at a time, which is one element rather than a different shape.
      category: category === undefined ? undefined : [category],
    }),
    [trimmed, category],
  );

  const results = useSearchResults(query, enabled);
  const suggestions = useSuggestions(deferredTerm);

  const cards = useMemo(
    () => (results.data?.pages ?? []).flatMap((page) => (page.items ?? []) as Card[]),
    [results.data],
  );

  const header = (
    <View style={styles.header}>
      <TextInput
        value={term}
        onChangeText={setTerm}
        placeholder="Search campaigns"
        placeholderTextColor={colors.textTertiary}
        style={styles.field}
        autoCorrect={false}
        returnKeyType="search"
        // A field whose only label is its placeholder is announced as its
        // current value, or as nothing at all once somebody has typed.
        accessibilityLabel="Search campaigns"
        // Native clear button on iOS; on Android the keyboard provides one.
        clearButtonMode="while-editing"
      />

      {(suggestions.data?.items ?? []).length > 0 ? (
        <View style={styles.chips}>
          {(suggestions.data?.items ?? []).map((item) => {
            const selected = category === item.slug;
            return (
              <Pressable
                key={`${item.kind}:${item.slug}`}
                onPress={() => setCategory(selected ? undefined : item.slug)}
                accessibilityRole="button"
                accessibilityState={{ selected }}
                accessibilityLabel={`Narrow to ${item.label}`}
                style={[styles.chip, selected && styles.chipSelected]}
              >
                {/* Near-black on lime is the only legible pairing (§9.1). */}
                <Meta tone={selected ? 'onLime' : 'secondary'}>{item.label}</Meta>
              </Pressable>
            );
          })}
        </View>
      ) : null}
    </View>
  );

  if (!enabled) {
    return (
      <View style={{ flex: 1, padding: size.cardGap }}>
        {header}
        <Body>Type at least {MINIMUM_QUERY} characters, or pick a suggestion.</Body>
      </View>
    );
  }

  if (cards.length === 0) {
    if (results.isLoading) return <Loading label="Searching" />;
    if (results.isError) {
      return (
        <ErrorState title="Search failed" detail="Check your connection and try again." />
      );
    }
  }

  return (
    <CampaignList
      cards={cards}
      header={header}
      onEndReached={() => {
        if (results.hasNextPage && !results.isFetchingNextPage) void results.fetchNextPage();
      }}
      empty={
        <EmptyState
          title="No matches"
          detail="Nothing matched that. Try a shorter term, or a different category."
        />
      }
    />
  );
}

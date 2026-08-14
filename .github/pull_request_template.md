<!--
Title: Conventional Commits, e.g. feat(payments): add idempotent pledge confirmation
Add a type:, an area:, and a priority: label before requesting review.
-->

## Summary

<!-- What changed, and why. The diff shows what; explain the reason. -->

## Related issues

<!-- "Closes #123" for complete work, "Part of #123" for a step towards it. -->

Closes #

## Verification

<!-- The commands you ran and what you saw. Not what you expect to happen. -->

- [ ] `pnpm typecheck`
- [ ] `pnpm test`
- [ ] `pnpm build:storybook`
- [ ] Reviewed in a browser

<!-- For UI changes, attach a before and after screenshot. -->

## Design compliance

<!-- Delete this section if the change touches no UI. -->

- [ ] Every colour comes from `@ideanest/design-tokens`; no hex literals
- [ ] Lime is used for urgency only, never to signal success
- [ ] No lime text on a light surface
- [ ] Interactive elements have accessible names and visible focus
- [ ] Colour is not the only carrier of any information
- [ ] `prefers-reduced-motion` is respected

## Not done

<!-- Anything deliberately left out, and why. A pull request that hides a gap
     is worse than one that names it. -->

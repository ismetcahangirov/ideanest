/**
 * Everything in the kit that reaches for `motion/react`, kept out of the root
 * barrel and behind `@ideanest/ui/motion`.
 *
 * WHY THIS FILE EXISTS. `motion` is 116 kB of JavaScript uncompressed, 38 kB
 * gzipped. While these members were re-exported from `index.ts`, every route
 * that imported anything at all from the package paid for it: the bundler
 * follows a static re-export whether or not the binding is used, so
 * `/projects/[id]/back` — the checkout, whose motion budget is "near zero"
 * (docs/motion-system.md §5) and which renders not one animated component —
 * shipped the whole animation runtime before it could paint. That was measured,
 * not assumed: stubbing `motion/react` out of the build removed exactly 116.2 kB
 * from the first load of all twelve routes.
 *
 * Turbopack does not rescue this. It tree-shakes the barrel's own bindings, but
 * `@ideanest/ui` is a `transpilePackages` source package that several routes
 * share, so its modules land in one shared chunk that every route's first load
 * pulls in. Adding `@ideanest/ui` to `experimental.optimizePackageImports` was
 * tried and the build came out byte-for-byte identical — that option does
 * nothing under Turbopack, which is what the Next 16 documentation says.
 *
 * The split is by dependency, not by theme. `FlipButton` is a motion-system
 * component (§4.3) and stays in the root barrel because it animates in CSS and
 * costs nothing; `Combobox` is an overlay and stays for the same reason, its
 * popup being the one docs/motion-system.md §5.1 requires not to animate.
 *
 * This is a boundary, not a lazy import. Importing from here still loads
 * `motion` eagerly, which is correct for `FadeUp`: it starts its element at
 * `opacity: 0`, so deferring the runtime would leave a heading invisible until
 * a second round trip finished — a worse largest contentful paint than the one
 * the split is trying to buy. What the boundary buys is that routes which never
 * import this file never see `motion` at all.
 */

/* Motion */
export { FadeUp, StaggerGroup, type FadeUpProps, type StaggerGroupProps } from './motion/FadeUp';
export { CountUp, type CountUpProps } from './motion/CountUp';

/* Overlay — the members that animate */
export { Modal, type ModalProps } from './components/overlay/Modal';
export { Drawer, type DrawerProps, type DrawerSide } from './components/overlay/Drawer';
export { Popover, type PopoverProps } from './components/overlay/Popover';
export { Tooltip, type TooltipProps } from './components/overlay/Tooltip';
export {
  ToastProvider,
  useToast,
  type ToastOptions,
  type ToastVariant,
  type ToastProviderProps,
  type ToastContextValue,
} from './components/overlay/Toast';
export { OVERLAY_ENTRY_MS } from './components/overlay/overlayMotion';

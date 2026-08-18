/**
 * The published contract for "may this account do this", across module boundaries.
 *
 * <p><strong>Why it is here rather than in the project module.</strong> Permissions on
 * a campaign are decided in one place — {@code project.application.ProjectAccess} — and
 * the vocabulary they are decided in, {@code project.domain.Capability}, is that
 * module's internal enum. {@code ModuleBoundaryTests} forbids another module from
 * naming it, and that rule is right: an enum in a {@code domain} package is a
 * persistence and wire concern of the module that owns it, and every module that
 * imported it would be coupled to those.
 *
 * <p>What the rule produced instead was worse than the coupling it prevented. Four
 * modules in turn wanted a fine-grained check, found they could not name the
 * capability, and settled for the coarsest question the project module happened to
 * publish — "may this account edit this campaign at all". The consequence was a live
 * authorisation defect: a collaborator granted only {@code EDIT_REWARDS} could publish
 * a project update and read the referral report, because both asked the coarse
 * question and both got "yes".
 *
 * <p><strong>So the vocabulary is published, and nothing else is.</strong> This package
 * holds {@link az.ideanest.shared.access.ProjectCapability} — the names, and only the
 * names — and the two ports a caller asks through:
 * {@link az.ideanest.shared.access.ProjectAuthorisation} for a capability on a
 * campaign, and {@link az.ideanest.shared.access.PlatformStaff} for staff identity.
 * Both are implemented inside the project module, which remains the one place either
 * question is answered. No campaign, no grant row, and no state ever crosses through
 * here.
 *
 * <p><strong>It stays a contract only.</strong> Nothing in this package may depend on
 * a module, because everything may depend on {@code shared} and a dependency the other
 * way would be a cycle dressed as a utility. That is why the ports declare their
 * refusals in prose rather than in a {@code throws} clause: the exceptions belong to
 * the module that decides, and naming them here would drag it back in.
 */
package az.ideanest.shared.access;

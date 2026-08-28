import { type AuthFailuresCopy, type AuthTranslator, authFailuresCopyFrom } from './auth-copy';

/**
 * The two credential panels under `/settings` — issue #324.
 *
 * <h2>Why these two and not the other eleven</h2>
 *
 * `EmailChangePanel` and `PasswordChangePanel` are the panels that call
 * `describeAuthFailure`, and #324 made its fallback vocabulary a required argument rather than
 * an optional one — a screen that quietly answered in English at the moment somebody is locked
 * out is the defect that change exists to prevent. Threading the failures alone would have
 * left two panels whose refusal is Azerbaijani and whose field labels are English, which is a
 * worse page than either. So both are taken whole.
 *
 * The rest of the account panels are still English literals; `apps/web/README.md` records
 * which, and each is the same shape of work.
 *
 * <h2>Two translators, and the second one is not an accident</h2>
 *
 * The builders take the panel's own namespace and `auth` as well. Two sentences belong to the
 * authentication vocabulary rather than to a settings screen: the password policy hint, which
 * `RegisterForm` and `/reset-password/confirm` print in the same words, and the refusal
 * vocabulary. Copying either into `settings.panels` would give the catalogue two spellings of
 * one fact and no way to notice when they drifted.
 */

export interface EmailChangePanelCopy {
  readonly heading: string;
  /** Carries `{address}`. */
  readonly signInWith: string;
  /** Carries `{address}`. */
  readonly signInWithUnverified: string;
  readonly alertTitle: string;
  readonly alertBody: string;
  readonly currentPassword: string;
  readonly currentPasswordHint: string;
  readonly newEmail: string;
  readonly emailPlaceholder: string;
  readonly submit: string;
  readonly submitting: string;
  /** Carries `{address}`. */
  readonly sentIntro: string;
  readonly nothingChanged: string;
  /** Carries `{address}`. */
  readonly stillSignIn: string;
  readonly stillSignInUnknown: string;
  readonly alsoWrote: string;
  readonly askDifferent: string;
  readonly failures: AuthFailuresCopy;
}

export interface PasswordChangePanelCopy {
  readonly heading: string;
  readonly alertTitle: string;
  readonly alertBody: string;
  readonly currentPassword: string;
  readonly currentPasswordHint: string;
  readonly newPassword: string;
  readonly newPasswordHint: string;
  readonly repeat: string;
  readonly repeatHint: string;
  readonly submit: string;
  readonly submitting: string;
  readonly mismatchTitle: string;
  readonly mismatchDetail: string;
  readonly mismatchField: string;
  readonly failures: AuthFailuresCopy;
}

export function emailChangePanelCopyFrom(
  t: AuthTranslator,
  auth: AuthTranslator,
): EmailChangePanelCopy {
  return {
    heading: t('emailChange.heading'),
    signInWith: t('emailChange.signInWith'),
    signInWithUnverified: t('emailChange.signInWithUnverified'),
    alertTitle: t('emailChange.alertTitle'),
    alertBody: t('emailChange.alertBody'),
    currentPassword: t('emailChange.currentPassword'),
    currentPasswordHint: t('emailChange.currentPasswordHint'),
    newEmail: t('emailChange.newEmail'),
    emailPlaceholder: auth('fields.emailPlaceholder'),
    submit: t('emailChange.submit'),
    submitting: t('emailChange.submitting'),
    sentIntro: t('emailChange.sentIntro'),
    nothingChanged: t('emailChange.nothingChanged'),
    stillSignIn: t('emailChange.stillSignIn'),
    stillSignInUnknown: t('emailChange.stillSignInUnknown'),
    alsoWrote: t('emailChange.alsoWrote'),
    askDifferent: t('emailChange.askDifferent'),
    failures: authFailuresCopyFrom(auth),
  };
}

export function passwordChangePanelCopyFrom(
  t: AuthTranslator,
  auth: AuthTranslator,
): PasswordChangePanelCopy {
  return {
    heading: t('passwordChange.heading'),
    alertTitle: t('passwordChange.alertTitle'),
    alertBody: t('passwordChange.alertBody'),
    currentPassword: t('passwordChange.currentPassword'),
    currentPasswordHint: t('passwordChange.currentPasswordHint'),
    newPassword: t('passwordChange.newPassword'),
    /* The policy sentence, shared with the register form and the reset — see the file note. */
    newPasswordHint: auth('fields.passwordHint'),
    repeat: t('passwordChange.repeat'),
    repeatHint: t('passwordChange.repeatHint'),
    submit: t('passwordChange.submit'),
    submitting: t('passwordChange.submitting'),
    mismatchTitle: t('passwordChange.mismatchTitle'),
    mismatchDetail: t('passwordChange.mismatchDetail'),
    mismatchField: t('passwordChange.mismatchField'),
    failures: authFailuresCopyFrom(auth),
  };
}

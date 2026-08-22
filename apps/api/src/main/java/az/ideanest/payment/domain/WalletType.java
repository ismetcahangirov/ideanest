package az.ideanest.payment.domain;

/**
 * §9.3's R-12: the wallet payments a provider supports.
 *
 * <p>Part of {@link ProviderCapabilities} rather than of any request, because
 * nothing on the platform chooses a wallet — the backer does, inside the provider's
 * hosted tokenisation flow (#55), which is the only place §17.2's SAQ A scope allows
 * a card or a wallet credential to exist. What the platform needs to know is
 * whether the flow it is about to open will offer one, and that is a capability.
 *
 * <p>Two values and not a longer list. R-12's reason for existing is mobile
 * conversion, and these are the two wallets §14.3's platforms put behind a native
 * button; a provider supporting something else supports it for a browser that would
 * have used a card anyway.
 */
public enum WalletType {

    /** iOS and Safari. */
    APPLE_PAY,

    /** Android and Chrome. */
    GOOGLE_PAY
}

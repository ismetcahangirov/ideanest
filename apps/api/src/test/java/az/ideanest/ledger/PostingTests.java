package az.ideanest.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import az.ideanest.ledger.application.EntryDirection;
import az.ideanest.ledger.application.LedgerAccount;
import az.ideanest.ledger.application.Posting;
import az.ideanest.ledger.application.UnbalancedPostingException;
import az.ideanest.shared.money.Money;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * §7.2's double-entry invariant, checked where the mistake is (#62).
 *
 * <p>V41's deferred constraint trigger is what makes the rule true of the database, and
 * {@code LedgerSchemaTests} asserts that. This asserts the other half: that an
 * unbalanced posting cannot be <em>constructed</em>, so the failure lands at the line
 * that built it rather than at a commit whose stack names nothing anybody wrote.
 *
 * <p>A plain unit test. Money arithmetic and a balance rule need no container, and
 * {@code AbstractIntegrationTest} says a test that needs no database should not start
 * one.
 */
class PostingTests {

    private static final UUID TRANSACTION = UUID.randomUUID();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID CREATOR = UUID.randomUUID();

    @Test
    @DisplayName("a posting whose debits equal its credits is accepted")
    void aBalancedPostingIsAccepted() {
        Posting posting = Posting.of(TRANSACTION, PROJECT)
                .debit(LedgerAccount.ESCROW, azn("120.00"))
                .credit(LedgerAccount.creator(CREATOR), azn("120.00"))
                .build();

        assertThat(posting.lines()).hasSize(2);
        assertThat(posting.currency()).isEqualTo("AZN");
    }

    @Test
    @DisplayName("a posting split across several credits still has to add up")
    void aSplitPostingBalances() {
        Posting posting = Posting.of(TRANSACTION, PROJECT)
                .debit(LedgerAccount.ESCROW, azn("100.00"))
                .credit(LedgerAccount.PSP_FEE, azn("3.20"))
                .credit(LedgerAccount.PLATFORM_FEE, azn("5.00"))
                .credit(LedgerAccount.creator(CREATOR), azn("91.80"))
                .build();

        assertThat(posting.lines()).hasSize(4);
    }

    /**
     * The failure the whole type exists to prevent, and the one that is a qapik out.
     *
     * <p>A qapik rather than a round number on purpose: the errors that survive review
     * are the ones a reader's eye rounds off, and a rule that only catches obviously
     * wrong postings is not a rule.
     */
    @Test
    @DisplayName("a posting that is a qapik out is refused, and says by how much")
    void anUnbalancedPostingIsRefused() {
        assertThatThrownBy(() -> Posting.of(TRANSACTION, PROJECT)
                        .debit(LedgerAccount.ESCROW, azn("100.00"))
                        .credit(LedgerAccount.creator(CREATOR), azn("99.99"))
                        .build())
                .isInstanceOf(UnbalancedPostingException.class)
                .hasMessageContaining("0.01");
    }

    @Test
    @DisplayName("a posting in two currencies is refused rather than converted")
    void twoCurrenciesAreRefused() {
        // §21.2: there is no rate at which one currency balances another for anything
        // that moves money, so this is not a rounding question -- it is two unbalanced
        // postings that a currency-blind sum would report as correct.
        assertThatThrownBy(() -> Posting.of(TRANSACTION, PROJECT)
                        .debit(LedgerAccount.ESCROW, azn("100.00"))
                        .credit(LedgerAccount.creator(CREATOR), Money.of(new BigDecimal("100.00"), "USD"))
                        .build())
                .isInstanceOf(UnbalancedPostingException.class)
                .hasMessageContaining("USD");
    }

    @Test
    @DisplayName("a posting with one line is refused; one line balances against nothing")
    void oneLineIsRefused() {
        assertThatThrownBy(() -> Posting.of(TRANSACTION, PROJECT)
                        .debit(LedgerAccount.ESCROW, azn("100.00"))
                        .build())
                .isInstanceOf(UnbalancedPostingException.class);
    }

    @Test
    @DisplayName("a posting with no lines is refused")
    void noLinesIsRefused() {
        assertThatThrownBy(() -> Posting.of(TRANSACTION, PROJECT).build())
                .isInstanceOf(UnbalancedPostingException.class);
    }

    /**
     * V41 refuses a zero-amount entry, so the builder has to be able to leave one out.
     *
     * <p>The alternative is the same {@code if} at four call sites, and the one somebody
     * forgets fails at a commit rather than here.
     */
    @Test
    @DisplayName("a zero line is left out rather than written")
    void aZeroLineIsLeftOut() {
        Posting posting = Posting.of(TRANSACTION, PROJECT)
                .debit(LedgerAccount.ESCROW, azn("100.00"))
                .creditIfAny(LedgerAccount.TAX_PAYABLE, azn("0.00"))
                .credit(LedgerAccount.creator(CREATOR), azn("100.00"))
                .build();

        assertThat(posting.lines())
                .as("no tax was collected, and that is said by the absence of a row")
                .hasSize(2)
                .noneMatch(line -> line.account().equals(LedgerAccount.TAX_PAYABLE));
    }

    @Test
    @DisplayName("a negative amount on a line is refused; the direction carries the sign")
    void aNegativeAmountIsRefused() {
        assertThatThrownBy(() -> new Posting.Line(
                        LedgerAccount.ESCROW, EntryDirection.DEBIT, azn("-1.00")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------
    // The account vocabulary
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an account outside §7.2's six is refused")
    void anUnknownAccountIsRefused() {
        // `platform_fees` rather than `platform_fee`: the typo an accounts table would
        // otherwise have been protecting against, and the one that would silently make
        // the platform's revenue two sums.
        assertThatThrownBy(() -> new LedgerAccount("platform_fees")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LedgerAccount("escrow ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LedgerAccount("creator:not-a-uuid")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a creator's account names the creator, and reads back as one")
    void aCreatorAccountRoundTrips() {
        LedgerAccount account = LedgerAccount.creator(CREATOR);

        assertThat(account.isCreator()).isTrue();
        assertThat(account.creatorId()).isEqualTo(CREATOR);
        assertThat(account.name()).isEqualTo("creator:" + CREATOR.toString().toLowerCase(java.util.Locale.ROOT));
    }

    @Test
    @DisplayName("one of the five singletons is not a creator's account")
    void aSingletonIsNotACreator() {
        assertThat(LedgerAccount.ESCROW.isCreator()).isFalse();
        assertThatThrownBy(LedgerAccount.ESCROW::creatorId).isInstanceOf(IllegalStateException.class);
    }

    private static Money azn(String amount) {
        return Money.of(new BigDecimal(amount), "AZN");
    }
}

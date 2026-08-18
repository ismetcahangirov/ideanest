-- The double-entry invariant, as one query.
--
-- §7.2: "for every `transaction_id`, SUM(debit) = SUM(credit)". This is the SQL
-- form of that sentence, and it is the only place the platform's restore
-- verification expresses it.
--
-- Grouped by currency as well as by transaction. §21 makes the platform
-- multi-currency, and a transaction whose manat debits happen to cancel its
-- dollar credits is not balanced — it is two broken transactions whose errors
-- sum to zero. Grouping on the amount alone would call that correct.
--
-- Called with the table to check, so that the same query runs against the real
-- `ledger_entries` and against the verifier's own fixture:
--
--   psql -v tbl=ledger_entries -f ledger-imbalance.sql
--
-- One row per offending (transaction, currency). No rows is the healthy answer.

SELECT
    transaction_id,
    currency,
    sum(CASE WHEN direction = 'debit'  THEN amount ELSE 0 END) AS debits,
    sum(CASE WHEN direction = 'credit' THEN amount ELSE 0 END) AS credits
FROM :tbl
GROUP BY transaction_id, currency
HAVING sum(CASE WHEN direction = 'debit'  THEN amount ELSE 0 END)
    <> sum(CASE WHEN direction = 'credit' THEN amount ELSE 0 END)
ORDER BY transaction_id, currency;

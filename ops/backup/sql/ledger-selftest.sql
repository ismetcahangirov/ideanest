-- Does the imbalance query actually detect an imbalance?
--
-- A verification that reports "the ledger balances" because it was pointed at
-- nothing, or because its own SQL is wrong, is worse than no verification: it
-- produces a signed-off drill and a false belief. So the verifier tests its own
-- detector on every run, against a fixture whose right answer is known.
--
-- The fixture is a **temporary** table. It exists for this session only, is
-- named so that it cannot shadow `ledger_entries`, and never touches the
-- restored data — a check that writes to the thing it is checking is not a
-- check.
--
-- Four transactions, three of which must be reported:
--
--   balanced        10.00 debit  / 10.00 credit, AZN          -> healthy
--   off-by-a-qapik  10.00 debit  /  9.99 credit, AZN          -> reported
--   cross-currency  10.00 debit AZN / 10.00 credit USD        -> reported twice,
--                                                                once per side
--   missing-side    10.00 debit  / nothing                    -> reported
--
-- The third is the case a naive `SUM(debit) = SUM(credit)` over the transaction
-- would call healthy, which is why it is here.

CREATE TEMPORARY TABLE ledger_entries_selftest (
    transaction_id uuid          NOT NULL,
    account        text          NOT NULL,
    direction      text          NOT NULL CHECK (direction IN ('debit', 'credit')),
    amount         numeric(14,2) NOT NULL,
    currency       text          NOT NULL
) ON COMMIT DROP;

INSERT INTO ledger_entries_selftest (transaction_id, account, direction, amount, currency) VALUES
    ('00000000-0000-4000-8000-000000000001', 'escrow',      'debit',  10.00, 'AZN'),
    ('00000000-0000-4000-8000-000000000001', 'creator:1',   'credit', 10.00, 'AZN'),

    ('00000000-0000-4000-8000-000000000002', 'escrow',      'debit',  10.00, 'AZN'),
    ('00000000-0000-4000-8000-000000000002', 'creator:1',   'credit',  9.99, 'AZN'),

    ('00000000-0000-4000-8000-000000000003', 'escrow',      'debit',  10.00, 'AZN'),
    ('00000000-0000-4000-8000-000000000003', 'creator:1',   'credit', 10.00, 'USD'),

    ('00000000-0000-4000-8000-000000000004', 'escrow',      'debit',  10.00, 'AZN');

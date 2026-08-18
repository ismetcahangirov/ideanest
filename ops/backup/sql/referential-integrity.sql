-- Every foreign key in the schema, checked against the data.
--
-- This is how "no partially-applied transaction survived the restore" is asked
-- of the whole database rather than of one witness row. A transaction that was
-- half replayed would show up as a child row whose parent is not there — the
-- pledge without its project, the ledger entry without its transaction — and
-- PostgreSQL does not re-check a foreign key during recovery. It cannot: it
-- replays pages, and a page is not a constraint.
--
-- Recovery is not supposed to be able to produce such a row, and that is the
-- point. This check exists to be *boring*: every quarter it says the guarantee
-- held, and the one time it does not, the drill has found a corrupted archive
-- or a bug in a recovery nobody would otherwise have questioned.
--
-- MATCH SIMPLE semantics, which is what every foreign key in this schema uses:
-- a row whose referencing columns are not all non-null satisfies the constraint
-- regardless of the parent, so `(cols) IS NOT NULL` — true only when every
-- element is non-null — is the correct guard rather than an optimisation.
--
-- Emits one line per violated constraint and raises at the end, so the output
-- names every problem rather than the first one.

DO $$
DECLARE
    fk         record;
    violations bigint;
    total      bigint := 0;
    checked    bigint := 0;
    statement  text;
BEGIN
    FOR fk IN
        SELECT
            con.conname                            AS constraint_name,
            child_ns.nspname                       AS child_schema,
            child.relname                          AS child_table,
            parent_ns.nspname                      AS parent_schema,
            parent.relname                         AS parent_table,
            (
                SELECT string_agg(quote_ident(att.attname), ', ' ORDER BY k.ord)
                FROM unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord)
                JOIN pg_attribute att
                  ON att.attrelid = con.conrelid AND att.attnum = k.attnum
            )                                      AS child_columns,
            (
                SELECT string_agg(quote_ident(att.attname), ', ' ORDER BY k.ord)
                FROM unnest(con.confkey) WITH ORDINALITY AS k(attnum, ord)
                JOIN pg_attribute att
                  ON att.attrelid = con.confrelid AND att.attnum = k.attnum
            )                                      AS parent_columns
        FROM pg_constraint con
        JOIN pg_class     child     ON child.oid = con.conrelid
        JOIN pg_namespace child_ns  ON child_ns.oid = child.relnamespace
        JOIN pg_class     parent    ON parent.oid = con.confrelid
        JOIN pg_namespace parent_ns ON parent_ns.oid = parent.relnamespace
        WHERE con.contype = 'f'
          AND child_ns.nspname NOT IN ('pg_catalog', 'information_schema')
        ORDER BY child_ns.nspname, child.relname, con.conname
    LOOP
        statement := format(
            'SELECT count(*) FROM %I.%I c WHERE (c.%s) IS NOT NULL '
            'AND NOT EXISTS (SELECT 1 FROM %I.%I p WHERE (c.%s) = (p.%s))',
            fk.child_schema, fk.child_table,
            replace(fk.child_columns, ', ', ', c.'),
            fk.parent_schema, fk.parent_table,
            replace(fk.child_columns, ', ', ', c.'),
            replace(fk.parent_columns, ', ', ', p.')
        );

        EXECUTE statement INTO violations;
        checked := checked + 1;

        IF violations > 0 THEN
            total := total + violations;
            RAISE WARNING 'referential integrity: % rows in %.% violate %',
                violations, fk.child_schema, fk.child_table, fk.constraint_name;
        END IF;
    END LOOP;

    RAISE NOTICE 'referential integrity: % foreign keys checked', checked;

    IF total > 0 THEN
        RAISE EXCEPTION 'referential integrity: % orphaned rows across the schema', total;
    END IF;
END
$$;

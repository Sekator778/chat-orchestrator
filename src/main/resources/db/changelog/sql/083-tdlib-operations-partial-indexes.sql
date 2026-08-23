-- cs083: recreate the two tdlib_operations indexes that were meant to be partial.
--
-- 046-tdlib-operations.yaml declares `where: "status = 'IN_PROGRESS'"` on both
-- createIndex changes, but Liquibase's createIndex has no `where` attribute -
-- it was accepted and silently dropped, so both indexes were created without a
-- predicate.
--
-- For uq_tdlib_operations_active_type that is not a cosmetic difference. The
-- lock is taken with
--     INSERT ... ON CONFLICT (operation_type, bot_instance_id)
--                 WHERE status = 'IN_PROGRESS' DO NOTHING RETURNING *
-- and a non-partial unique index still satisfies that inference (an index with
-- no predicate is implied by any WHERE). So the conflict fires against rows of
-- ANY status: the first COMPLETED row for a given (operation_type,
-- bot_instance_id) makes the lock permanently unacquirable, and the insert
-- returns nothing - which the service reports as "another operation is in
-- progress". MESSAGE_SYNC therefore ran exactly once per bot instance and then
-- went silent until deleteOldOperations() removed the row 24 h later.
--
-- Recreating both indexes with their intended predicate restores the documented
-- semantics: one ACTIVE operation per (type, instance), history rows ignored.

DROP INDEX IF EXISTS bot.uq_tdlib_operations_active_type;

CREATE UNIQUE INDEX uq_tdlib_operations_active_type
    ON bot.tdlib_operations (operation_type, bot_instance_id)
    WHERE status = 'IN_PROGRESS';

-- Same silent drop; this one only costs index selectivity when scanning for
-- stale locks, but it should match what 046 said it was creating.
DROP INDEX IF EXISTS bot.idx_tdlib_operations_timeout;

CREATE INDEX idx_tdlib_operations_timeout
    ON bot.tdlib_operations (timeout_at)
    WHERE status = 'IN_PROGRESS';

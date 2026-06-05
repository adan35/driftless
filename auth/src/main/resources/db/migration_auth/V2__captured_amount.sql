-- Spec 03 — Auth Lifecycle Saga, migration V2 (append-only; V1 is never edited).
--
-- Adds captured_amount_minor to the authorization aggregate so a reversal of a PARTIAL capture posts
-- the inverse of the AMOUNT ACTUALLY CAPTURED, not the authorized amount (review C1). A capture may
-- settle any amount in (0, authorized]; before this column the reversal recomputed its inverse from
-- amount_minor (the authorized figure), over-crediting the cardholder when the capture was partial.
-- Both legs of that reversal still balanced (so the global zero-drift gate stayed green) while real
-- value moved incorrectly — exactly the class of defect this column closes.
--
-- The column is nullable: it is set only when a capture settles (a never-captured authorization has
-- no captured amount), and a positive-when-present check keeps it consistent with the money model.

ALTER TABLE "authorization" ADD COLUMN captured_amount_minor BIGINT;

ALTER TABLE "authorization"
    ADD CONSTRAINT ck_authorization_captured_amount_positive
        CHECK (captured_amount_minor IS NULL OR captured_amount_minor > 0);

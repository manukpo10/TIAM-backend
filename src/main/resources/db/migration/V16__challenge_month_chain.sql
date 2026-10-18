-- Desafío 30 días: one link for every month a buyer owns.
--
-- Each month stays its own purchase (its own start date, its own progress), but a month bought from the player
-- ("Seguí con el Mes 2") points at the buyer's FIRST purchase through parent_purchase_id, and every month is reached
-- from the first purchase's link. NULL = a first purchase (or any purchase made before this existed, which keeps
-- working exactly as before). The parent is always the first purchase, never a middle link, so the chain is flat.
ALTER TABLE challenge_purchases
    ADD COLUMN parent_purchase_id BIGINT REFERENCES challenge_purchases(id);

CREATE INDEX idx_challenge_purchases_parent ON challenge_purchases(parent_purchase_id);

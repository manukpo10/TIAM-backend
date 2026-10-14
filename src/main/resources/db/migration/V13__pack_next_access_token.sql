-- Pack access re-send: a replacement token that sits NEXT TO the working one. "I lost my link" is public and
-- unauthenticated, so it must not be able to invalidate a buyer's working link (a stranger who knows an email
-- could otherwise lock the buyer out). The new token only replaces the old one the first time it is used.
ALTER TABLE pack_orders ADD COLUMN next_access_token VARCHAR(64);
ALTER TABLE pack_orders ADD COLUMN next_access_token_issued_at TIMESTAMPTZ;
CREATE UNIQUE INDEX idx_pack_orders_next_access_token ON pack_orders(next_access_token) WHERE next_access_token IS NOT NULL;

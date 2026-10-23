-- Refresh-token grace window (protocol: 认证 / 宽限期).
-- replaced_by: hash of the token issued when this one was rotated.
ALTER TABLE refresh_tokens ADD COLUMN replaced_by TEXT;
-- token_gen: incremented on every token issuance for the device; embedded in
-- access tokens as the "gen" claim.
ALTER TABLE devices ADD COLUMN token_gen INTEGER NOT NULL DEFAULT 0;
-- min_access_gen: access tokens with gen below this are rejected. Raised when
-- a grace-window retry supersedes the previously issued pair.
ALTER TABLE devices ADD COLUMN min_access_gen INTEGER NOT NULL DEFAULT 0;

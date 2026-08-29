-- Adds phone number storage (AES-GCM encrypted, via UserPiiTypeHandler) and blind-index
-- columns for exact-match search on phone/RRN (see vault-crypto README.md "4. 암호화된
-- 컬럼 검색 - Blind Index" and UserMapper.search()).
--
-- Not run automatically - MyBatis (unlike the JPA setup this app used to have) does not
-- manage schema. Run this by hand against the target database, then run
-- bootstrap_blind_index_keys.py's printed `vault kv put` commands to provision the
-- blind-index HMAC keys before starting an app build that depends on them.
--
-- Existing rows: phone/phone_blind_idx start NULL (no backfill needed - phone is a
-- brand-new field with no prior data). id_no_blind_idx also starts NULL for any existing
-- users and needs a one-time backfill (decrypt each row's id_no, compute its blind index,
-- write it back) before RRN search will find pre-existing rows - there is no batch job
-- for this yet; add one analogous to DekReencryptionService if/when there's real existing
-- user data to backfill.

ALTER TABLE ebiz.users ADD COLUMN IF NOT EXISTS phone VARCHAR(255);
ALTER TABLE ebiz.users ADD COLUMN IF NOT EXISTS phone_blind_idx VARCHAR(64);
ALTER TABLE ebiz.users ADD COLUMN IF NOT EXISTS id_no_blind_idx VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_users_phone_blind_idx ON ebiz.users (phone_blind_idx);
CREATE INDEX IF NOT EXISTS idx_users_id_no_blind_idx ON ebiz.users (id_no_blind_idx);

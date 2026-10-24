-- ADT-70: serialize the ownership-limit/cooldown check and creation per owner.
-- Rows are retained after creation/disband; deleting one could split the lock
-- between concurrent requests. No player FK: API callers need not be cached.
CREATE TABLE firm_creation_lock (
    proprietor_uuid_bin BINARY(16) NOT NULL PRIMARY KEY
) ENGINE=InnoDB;

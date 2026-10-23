-- PAR-499: allow an explicitly renamed personal account to change owner.
-- Existing shared player/account names remain reserved to their original identity.
DROP PROCEDURE IF EXISTS global_names_set;
CREATE PROCEDURE global_names_set(IN p_source VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin,
                                 IN p_subject VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin,
                                 IN p_name VARCHAR(255) CHARACTER SET utf8mb4,
                                 IN p_entity VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin)
BEGIN
    DECLARE old_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL;
    DECLARE old_entity VARCHAR(80) DEFAULT NULL;
    DECLARE held_entity VARCHAR(80);
    DECLARE held_count INT UNSIGNED;
    DECLARE new_name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
    SET new_name=TRIM(p_name);
    IF new_name IS NULL OR new_name='' THEN
        SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO=1644,
            MESSAGE_TEXT='GLOBAL_NAME_INVALID: choose a nonblank name';
    END IF;
    -- Create/lock the subject row first. A locking read of an absent row takes
    -- a gap lock that can block Treasury's separate default-account transaction
    -- while Business holds its firm transaction open. Placeholder rows are only
    -- transient inside the caller's atomic statement, never a conflict winner.
    INSERT INTO global_name_claims(source_kind,subject_id) VALUES(p_source,p_subject)
    ON DUPLICATE KEY UPDATE subject_id=subject_id;
    SELECT name_key,entity_key INTO old_name,old_entity FROM global_name_claims
      WHERE source_kind=p_source AND subject_id=p_subject FOR UPDATE;
    IF old_name=new_name AND BINARY old_entity<>BINARY p_entity THEN
        -- An owner change may move this one projection to a new identity only
        -- when no other projection still holds the spelling. Lock the unique
        -- reservation so concurrent claimants cannot race that decision.
        SELECT entity_key,claim_count INTO held_entity,held_count FROM global_name_reservations
          WHERE name_key=new_name FOR UPDATE;
        IF held_count<>1 OR BINARY held_entity<>BINARY old_entity THEN
            SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO=1644,
                MESSAGE_TEXT='GLOBAL_NAME_CONFLICT: name still reserved by another projection';
        END IF;
        UPDATE global_name_reservations SET entity_key=p_entity WHERE name_key=new_name;
        UPDATE global_name_claims SET entity_key=p_entity
          WHERE source_kind=p_source AND subject_id=p_subject;
    ELSEIF old_name IS NULL OR NOT (old_name=new_name AND BINARY old_entity=BINARY p_entity) THEN
        -- The unique key serializes independent writers. A conflicting claim
        -- rolls back the increment and the caller's entire source statement.
        INSERT INTO global_name_reservations(name_key,entity_key,claim_count)
        VALUES(new_name,p_entity,1)
        ON DUPLICATE KEY UPDATE claim_count=claim_count+1;
        SELECT entity_key INTO held_entity FROM global_name_reservations
          WHERE name_key=new_name FOR UPDATE;
        IF BINARY held_entity<>BINARY p_entity THEN
            SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO=1644,
                MESSAGE_TEXT='GLOBAL_NAME_CONFLICT: name reserved by another identity; choose another name or ask an administrator';
        END IF;
        UPDATE global_name_claims SET name_key=new_name,entity_key=p_entity
          WHERE source_kind=p_source AND subject_id=p_subject;
        IF old_name IS NOT NULL THEN
            -- Delete the reservation only after its last projection has moved.
            -- Delete-before-decrement avoids an intermediate count of zero.
            DELETE FROM global_name_reservations WHERE name_key=old_name AND claim_count=1;
            UPDATE global_name_reservations SET claim_count=claim_count-1 WHERE name_key=old_name;
        END IF;
    END IF;
END;

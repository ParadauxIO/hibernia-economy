-- PAR-101: independently managed memberships; one binding per group/source.
ALTER TABLE explorer_group_member
    MODIFY source ENUM('manual','luckperms','business','realty') NOT NULL DEFAULT 'manual',
    ADD COLUMN sync_selector VARCHAR(255) NULL;

CREATE TABLE explorer_group_sync (
    group_id INT UNSIGNED NOT NULL,
    source ENUM('business','realty') NOT NULL,
    selector VARCHAR(255) NOT NULL,
    PRIMARY KEY (group_id, source),
    CONSTRAINT fk_group_sync_group FOREIGN KEY (group_id) REFERENCES explorer_group(group_id) ON DELETE CASCADE
);

-- Add user role for USER / ADMIN / INTERNAL permission boundary.
-- Execute this on MySQL before testing INTERNAL mode.

ALTER TABLE `ums_user`
    ADD COLUMN `role` varchar(20) NOT NULL DEFAULT 'USER' COMMENT 'USER/ADMIN/INTERNAL' AFTER `status`;

CREATE INDEX `idx_role` ON `ums_user` (`role`);

UPDATE `ums_user`
SET `role` = 'USER'
WHERE `role` IS NULL OR `role` = '';

-- Local debug account. Change the id if your test account is not user 1.
UPDATE `ums_user`
SET `role` = 'INTERNAL'
WHERE `id` = 1;

import 'server-only';
import { sql } from 'kysely';

/** Queries must alias explorer_group_member as gm. Unknown sources fail closed. */
export const activeGroupMembership = sql`(gm.source = 'manual' OR (
  gm.synced_at > CURRENT_TIMESTAMP - INTERVAL 1 HOUR AND (
    (gm.source = 'luckperms' AND EXISTS (
      SELECT 1 FROM explorer_group g WHERE g.group_id = gm.group_id AND g.luckperms_node IS NOT NULL
    )) OR (gm.source IN ('business', 'realty') AND EXISTS (
      SELECT 1 FROM explorer_group_sync s WHERE s.group_id = gm.group_id AND s.source = gm.source
        AND BINARY s.selector = BINARY gm.sync_selector
    ))
  )
))`;

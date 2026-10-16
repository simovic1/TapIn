-- Establish an explicit initial state at the joined-on date for legacy members.
-- Preserve all existing transitions. With no history, the only known state is the current status.
INSERT INTO membership_status_events (membership_id, old_status, new_status, effective_at)
SELECT m.id, NULL,
       COALESCE((SELECT COALESCE(e.old_status, e.new_status) FROM membership_status_events e
                 WHERE e.membership_id=m.id ORDER BY e.effective_at,e.id LIMIT 1), m.status),
       m.joined_on::timestamp AT TIME ZONE c.time_zone
FROM club_memberships m JOIN clubs c ON c.id=m.club_id
WHERE NOT EXISTS (SELECT 1 FROM membership_status_events e WHERE e.membership_id=m.id
                  AND e.effective_at <= m.joined_on::timestamp AT TIME ZONE c.time_zone);

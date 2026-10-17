-- Local test data for trying the trainer and member roles by hand. LOCAL DEVELOPMENT ONLY.
-- Run: scripts/dev-seed.sh   (or: docker exec -i tapin-db-1 psql -U tapin -d tapin -v ON_ERROR_STOP=1 < scripts/dev-seed.sql)
--
-- Every account below has the password  trening-lokalno  (test value, bcrypt hash below).
--   trener@bd.test            Marko Trener   MEMBER + TRAINER  (roll call, schedule, members, reports)
--   clan01..clan10@bd.test    10 members, REGISTERED for today's training
--   clan11, clan12@bd.test    members NOT registered (to test "add a walk-in member" on the roll call)
--
-- Trainings created:
--   * today: started 20 minutes ago, ends in 70 minutes, clan01-10 registered, nobody marked yet
--     (roll call, "mark remaining absent", corrections; after it ends it becomes HELD on its own)
--   * tomorrow 18:30 Zagreb: 4 registered, the rest can register/cancel themselves
--   * 6 held trainings in the last three weeks with mixed attendance (statistics, belts, history)
--
-- Safe to run again: existing accounts are kept; a training is only added when its time slot is free.
-- Seeded attendance has no audit rows (it did not come through the roll call).

DO $$
DECLARE
    pwd      CONSTANT TEXT := '{bcrypt}$2a$10$XHYphVez6Qxoz73TxT3yA.KOW7EI.jkILBbT9aEvug4B/a89gJKri';
    club     BIGINT := (SELECT id FROM clubs);
    names    TEXT[][] := ARRAY[
        ['clan01', 'Ana', 'Horvat', 'WHITE'],   ['clan02', 'Ivan', 'Kovačević', 'BLUE'],
        ['clan03', 'Petra', 'Babić', 'WHITE'],  ['clan04', 'Luka', 'Marić', 'PURPLE'],
        ['clan05', 'Maja', 'Jurić', 'WHITE'],   ['clan06', 'Josip', 'Novak', 'BLUE'],
        ['clan07', 'Iva', 'Knežević', 'WHITE'], ['clan08', 'Tomislav', 'Vuković', 'BROWN'],
        ['clan09', 'Lucija', 'Matić', 'WHITE'], ['clan10', 'Filip', 'Pavlović', 'BLUE'],
        ['clan11', 'Nina', 'Šarić', 'WHITE'],   ['clan12', 'Marin', 'Tomić', 'WHITE']];
    trainer_user BIGINT;
    trainer_m    BIGINT;
    m_ids        BIGINT[] := '{}';
    uid          BIGINT;
    mid          BIGINT;
    s_today      BIGINT;
    s_tomorrow   BIGINT;
    s            BIGINT;
    i            INT;
    k            INT;
    t_start      TIMESTAMPTZ;
    zone         TEXT := (SELECT time_zone FROM clubs);
BEGIN
    -- ---------------------------------------------------------------- accounts
    INSERT INTO users (email, first_name, last_name, account_status, password_hash)
    VALUES ('trener@bd.test', 'Marko', 'Trener', 'ACTIVE', pwd)
    ON CONFLICT (lower(email)) DO NOTHING;
    trainer_user := (SELECT id FROM users WHERE lower(email) = 'trener@bd.test');
    INSERT INTO club_memberships (club_id, user_id, joined_on, status)
    VALUES (club, trainer_user, DATE '2024-01-01', 'ACTIVE') ON CONFLICT (club_id, user_id) DO NOTHING;
    trainer_m := (SELECT id FROM club_memberships WHERE user_id = trainer_user);
    INSERT INTO membership_roles (membership_id, role) VALUES (trainer_m, 'MEMBER'), (trainer_m, 'TRAINER')
    ON CONFLICT DO NOTHING;
    IF NOT EXISTS (SELECT 1 FROM membership_status_events WHERE membership_id = trainer_m) THEN
        INSERT INTO membership_status_events (membership_id, old_status, new_status, effective_at)
        VALUES (trainer_m, NULL, 'ACTIVE', (DATE '2024-01-01')::timestamp AT TIME ZONE zone);
    END IF;

    FOR i IN 1 .. array_length(names, 1) LOOP
        INSERT INTO users (email, first_name, last_name, account_status, password_hash)
        VALUES (names[i][1] || '@bd.test', names[i][2], names[i][3], 'ACTIVE', pwd)
        ON CONFLICT (lower(email)) DO NOTHING;
        uid := (SELECT id FROM users WHERE lower(email) = names[i][1] || '@bd.test');
        INSERT INTO club_memberships (club_id, user_id, joined_on, status)
        VALUES (club, uid, DATE '2025-09-01', 'ACTIVE') ON CONFLICT (club_id, user_id) DO NOTHING;
        mid := (SELECT id FROM club_memberships WHERE user_id = uid);
        INSERT INTO membership_roles (membership_id, role) VALUES (mid, 'MEMBER') ON CONFLICT DO NOTHING;
        IF NOT EXISTS (SELECT 1 FROM membership_status_events WHERE membership_id = mid) THEN
            INSERT INTO membership_status_events (membership_id, old_status, new_status, effective_at)
            VALUES (mid, NULL, 'ACTIVE', (DATE '2025-09-01')::timestamp AT TIME ZONE zone);
        END IF;
        IF NOT EXISTS (SELECT 1 FROM member_belt_periods WHERE membership_id = mid) THEN
            INSERT INTO member_belt_periods (membership_id, belt, awarded_on, effective_from_date, awarded_by_user_id,
                                             created_by_user_id, note)
            VALUES (mid, names[i][4], DATE '2025-09-01', DATE '2025-09-01', trainer_user, trainer_user, 'Testni podatak');
        END IF;
        m_ids := m_ids || mid;
    END LOOP;

    -- ---------------------------------------------------------------- today's training (in progress)
    t_start := date_trunc('minute', now()) - interval '20 minutes';
    IF NOT EXISTS (SELECT 1 FROM training_sessions
                   WHERE tstzrange(start_at, end_at, '[)') && tstzrange(t_start, t_start + interval '90 minutes', '[)')) THEN
        INSERT INTO training_sessions (club_id, start_at, end_at, status, title, created_by_user_id)
        VALUES (club, t_start, t_start + interval '90 minutes', 'SCHEDULED', 'Fundamentals (test)', trainer_user)
        RETURNING id INTO s_today;
        INSERT INTO session_coaches (session_id, membership_id) VALUES (s_today, trainer_m);
        FOR k IN 1 .. 10 LOOP
            INSERT INTO training_registrations (session_id, club_id, membership_id, status, registered_at)
            VALUES (s_today, club, m_ids[k], 'REGISTERED', now() - interval '3 hours' + k * interval '5 minutes');
        END LOOP;
        RAISE NOTICE 'Today''s training % created: started %, 10 registered', s_today, t_start;
    ELSE
        RAISE NOTICE 'Today''s slot is taken by another training; nothing added for today';
    END IF;

    -- ---------------------------------------------------------------- tomorrow 18:30 Zagreb
    t_start := ((now() AT TIME ZONE zone)::date + 1 + time '18:30') AT TIME ZONE zone;
    IF NOT EXISTS (SELECT 1 FROM training_sessions
                   WHERE tstzrange(start_at, end_at, '[)') && tstzrange(t_start, t_start + interval '90 minutes', '[)')) THEN
        INSERT INTO training_sessions (club_id, start_at, end_at, status, title, created_by_user_id)
        VALUES (club, t_start, t_start + interval '90 minutes', 'SCHEDULED', 'Open mat (test)', trainer_user)
        RETURNING id INTO s_tomorrow;
        INSERT INTO session_coaches (session_id, membership_id) VALUES (s_tomorrow, trainer_m);
        FOR k IN 1 .. 4 LOOP
            INSERT INTO training_registrations (session_id, club_id, membership_id, status, registered_at)
            VALUES (s_tomorrow, club, m_ids[k], 'REGISTERED', now() - interval '1 hour');
        END LOOP;
    END IF;

    -- ---------------------------------------------------------------- 6 held trainings (last 3 weeks)
    FOR i IN 1 .. 6 LOOP
        t_start := ((now() AT TIME ZONE zone)::date - (i * 3 + 1) + time '18:30') AT TIME ZONE zone;
        CONTINUE WHEN EXISTS (SELECT 1 FROM training_sessions
                              WHERE tstzrange(start_at, end_at, '[)') && tstzrange(t_start, t_start + interval '90 minutes', '[)'));
        INSERT INTO training_sessions (club_id, start_at, end_at, status, title, created_by_user_id)
        VALUES (club, t_start, t_start + interval '90 minutes', 'HELD', 'Trening (test)', trainer_user)
        RETURNING id INTO s;
        INSERT INTO session_coaches (session_id, membership_id) VALUES (s, trainer_m);
        FOR k IN 1 .. 10 LOOP
            -- registered: everyone except every 4th; attendance: mostly present, some absent, one left unmarked
            IF (k + i) % 4 <> 0 THEN
                INSERT INTO training_registrations (session_id, club_id, membership_id, status, registered_at)
                VALUES (s, club, m_ids[k], 'REGISTERED', t_start - interval '5 hours');
            END IF;
            IF (k + i) % 5 = 0 THEN
                INSERT INTO training_attendances (session_id, club_id, membership_id, status, updated_by_user_id, updated_at)
                VALUES (s, club, m_ids[k], 'ABSENT', trainer_user, t_start + interval '2 hours');
            ELSIF (k * i) % 7 <> 3 THEN
                INSERT INTO training_attendances (session_id, club_id, membership_id, status, updated_by_user_id, updated_at)
                VALUES (s, club, m_ids[k], 'PRESENT', trainer_user, t_start + interval '2 hours');
            END IF;
        END LOOP;
    END LOOP;
END $$;

SELECT s.id, to_char(s.start_at AT TIME ZONE c.time_zone, 'DD.MM. HH24:MI') AS zagreb, s.status, s.title,
       (SELECT count(*) FROM training_registrations r WHERE r.session_id = s.id AND r.status = 'REGISTERED') AS registered
FROM training_sessions s JOIN clubs c ON c.id = s.club_id
WHERE s.title LIKE '%(test)'
ORDER BY s.start_at DESC;

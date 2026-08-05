insert into leads (id, customer_id, given_name, phone, state, email, current_provider, current_premium)
values (1, 'comparato', 'John',   '+61457099876', 'WA',  'john@example.com',   'HBF',  '$350-$450'),
       (2, 'comparato', 'Lauren', '+61457099877', 'VIC', 'lauren@example.com', 'Bupa', null),
       (3, 'comparato', 'Jane',   '+61457099878', 'NSW', 'jane@example.com',   null,   null)
on conflict (id) do nothing;

-- Explicit ids do not advance a bigserial sequence, so without this the first runtime
-- insert would collide on id 1. Nothing inserts leads today; this stops that from being
-- a trap for whoever wires up real lead ingestion.
select setval(pg_get_serial_sequence('leads', 'id'), (select max(id) from leads));

-- A completed conversation for lead 1 (John), so the app is worth opening without an
-- OPENAI_API_KEY: every message body below is copied verbatim from
-- evals/transcripts/scenario-1-happy-path.md, which is output from a real live API run.
-- AgentService.start resumes a lead's existing conversation, so clicking John lands here and
-- costs nothing. Leads 2 and 3 have no seeded conversation and do call the model.
insert into conversations (id, lead_id, customer_id, status, objection_count,
                           created_at, updated_at)
values (1, 1, 'comparato', 'GOAL_MET', 0,
        timestamptz '2026-08-05 01:00:00+00', timestamptz '2026-08-05 01:11:00+00')
on conflict (id) do nothing;

select setval(pg_get_serial_sequence('conversations', 'id'),
              (select max(id) from conversations));

-- AgentService.reset reuses the conversation id, deletes its messages and its booking, and
-- sets the status back to ACTIVE - and this file runs again on the next boot. `on conflict
-- (id) do nothing` does not protect the children: their ids went with them, so nothing would
-- conflict and the seed would reappear inside a conversation whose status now says ACTIVE.
-- Hence the guard: seed the children only while conversation 1 has no messages at all. A reset
-- writes its fresh opener in the same transaction that clears the thread, so "no messages"
-- means seeded-and-never-touched and nothing else.
--
-- The booking has to be inserted BEFORE the messages. Both statements read the same guard, and
-- the message insert falsifies it.
insert into bookings (id, conversation_id, calendly_event_id, start_time)
select 1, 1, 'evt_stub_comparato', timestamptz '2026-08-06 09:00:00+08'
where not exists (select 1 from messages where conversation_id = 1)
on conflict (id) do nothing;

select setval(pg_get_serial_sequence('bookings', 'id'),
              (select coalesce(max(id), 1) from bookings));

insert into messages (id, conversation_id, direction, body, prompt_version, model,
                      structured_output, created_at)
select * from (values
       (1, 1, 'OUTBOUND',
        'Hi John, it''s Anna from Comparato - you asked us about health cover on our site. We''ve been finding a lot of HBF members better value lately. Are you looking to save money or improve your cover?

Reply ''stop'' to opt out',
        'system-v1@d7055e29ec0f', 'gpt-5.6-terra',
        '{"message":"Hi John, it''s Anna from Comparato - you asked us about health cover on our site. We''ve been finding a lot of HBF members better value lately. Are you looking to save money or improve your cover?","stage":"SITUATION","goalMet":false,"unsubscribed":false,"endConversation":false,"endReason":"NONE","objectionRaised":false}',
        timestamptz '2026-08-05 01:00:00+00'),
       (2, 1, 'INBOUND',
        'bit of both really',
        null, null,
        null,
        timestamptz '2026-08-05 01:02:00+00'),
       (3, 1, 'OUTBOUND',
        'Got it. What are you covered for now - hospital, extras, or both?',
        'system-v1@d7055e29ec0f', 'gpt-5.6-terra',
        '{"message":"Got it. What are you covered for now - hospital, extras, or both?","stage":"PREFERENCE","goalMet":false,"unsubscribed":false,"endConversation":false,"endReason":"NONE","objectionRaised":false}',
        timestamptz '2026-08-05 01:03:00+00'),
       (4, 1, 'INBOUND',
        'hospital mainly, and dental for the kids',
        null, null,
        null,
        timestamptz '2026-08-05 01:05:00+00'),
       (5, 1, 'OUTBOUND',
        'That makes sense. An advisor can check hospital cover and dental options for the kids against what you''re paying now. Are you free today or tomorrow for a quick 15 min call?',
        'system-v1@d7055e29ec0f', 'gpt-5.6-terra',
        '{"message":"That makes sense. An advisor can check hospital cover and dental options for the kids against what you''re paying now. Are you free today or tomorrow for a quick 15 min call?","stage":"SUGGEST_CALL","goalMet":false,"unsubscribed":false,"endConversation":false,"endReason":"NONE","objectionRaised":false}',
        timestamptz '2026-08-05 01:06:00+00'),
       (6, 1, 'INBOUND',
        'yeah tomorrow morning could work',
        null, null,
        null,
        timestamptz '2026-08-05 01:08:00+00'),
       (7, 1, 'OUTBOUND',
        'Tomorrow I can do 9:00am, 9:30am or 11:00am. Which suits you?',
        'system-v1@d7055e29ec0f', 'gpt-5.6-terra',
        '{"message":"Tomorrow I can do 9:00am, 9:30am or 11:00am. Which suits you?","stage":"OFFER_TIMES","goalMet":false,"unsubscribed":false,"endConversation":false,"endReason":"NONE","objectionRaised":false}',
        timestamptz '2026-08-05 01:09:00+00'),
       (8, 1, 'INBOUND',
        'the first one',
        null, null,
        null,
        timestamptz '2026-08-05 01:10:00+00'),
       (9, 1, 'OUTBOUND',
        'You''re booked for Thursday 6 August at 9:00am. The call takes about 15 minutes.',
        'system-v1@d7055e29ec0f', 'gpt-5.6-terra',
        '{"message":"You''re booked for Thursday 6 August at 9:00am. The call takes about 15 minutes.","stage":"CONFIRM","goalMet":true,"unsubscribed":false,"endConversation":true,"endReason":"BOOKED","objectionRaised":false}',
        timestamptz '2026-08-05 01:11:00+00')
) as seeded
where not exists (select 1 from messages where conversation_id = 1)
on conflict (id) do nothing;

-- coalesce because reset() can empty these two tables, and setval(seq, null) is a silent no-op.
select setval(pg_get_serial_sequence('messages', 'id'),
              (select coalesce(max(id), 1) from messages));

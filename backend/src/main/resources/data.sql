insert into leads (id, customer_id, given_name, phone, state, email, current_provider, current_premium)
values (1, 'comparato', 'John',   '+61457099876', 'WA',  'john@example.com',   'HBF',  '$350-$450'),
       (2, 'comparato', 'Lauren', '+61457099877', 'VIC', 'lauren@example.com', 'Bupa', null),
       (3, 'comparato', 'Jane',   '+61457099878', 'NSW', 'jane@example.com',   null,   null)
on conflict (id) do nothing;

-- Explicit ids do not advance a bigserial sequence, so without this the first runtime
-- insert would collide on id 1. Nothing inserts leads today; this stops that from being
-- a trap for whoever wires up real lead ingestion.
select setval(pg_get_serial_sequence('leads', 'id'), (select max(id) from leads));

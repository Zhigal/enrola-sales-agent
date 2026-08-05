insert into leads (id, customer_id, given_name, phone, state, email, current_provider, current_premium)
values (1, 'comparato', 'John',   '+61457099876', 'WA',  'john@example.com',   'HBF',  '$350-$450'),
       (2, 'comparato', 'Lauren', '+61457099877', 'VIC', 'lauren@example.com', 'Bupa', null),
       (3, 'comparato', 'Jane',   '+61457099878', 'NSW', 'jane@example.com',   null,   null)
on conflict (id) do nothing;

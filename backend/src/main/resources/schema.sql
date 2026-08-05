create table if not exists leads (
    id               bigserial primary key,
    customer_id      text not null,
    given_name       text not null,
    phone            text not null,
    state            text not null,
    email            text not null,
    current_provider text,
    current_premium  text
);

create table if not exists conversations (
    id              bigserial primary key,
    lead_id         bigint not null references leads (id),
    customer_id     text not null,
    status          text not null,
    objection_count int not null default 0,
    created_at      timestamptz not null,
    updated_at      timestamptz not null
);

create table if not exists messages (
    id                bigserial primary key,
    conversation_id   bigint not null references conversations (id) on delete cascade,
    direction         text not null,
    body              text not null,
    prompt_version    text,
    model             text,
    tokens_in         int,
    tokens_out        int,
    structured_output text,
    created_at        timestamptz not null
);

create table if not exists bookings (
    id                bigserial primary key,
    conversation_id   bigint not null references conversations (id) on delete cascade,
    calendly_event_id text not null,
    start_time        timestamptz not null
);

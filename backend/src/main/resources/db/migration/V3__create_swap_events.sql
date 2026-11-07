-- Opt-in events from the app. event_id is created on the phone and reused on retries,
-- so the primary key is what makes uploads idempotent.
create table swap_event (
    event_id         uuid             primary key,
    install_id       uuid             not null,
    type             text             not null check (type in ('SHOWN', 'ACCEPTED', 'DISMISSED', 'VERIFIED')),
    suggestion_id    uuid             not null,
    swap_count       integer          not null check (swap_count between 1 and 3),
    kg_co2e_saved    double precision not null,
    cost_delta_cents bigint           not null,
    factor_version   integer          not null,
    occurred_at      timestamptz      not null,
    received_at      timestamptz      not null default now()
);

-- The metrics query groups events by suggestion: shown -> accepted -> verified.
create index swap_event_suggestion on swap_event (suggestion_id);

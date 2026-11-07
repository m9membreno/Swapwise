-- Parsed receipt lines keyed by SHA-256(prompt version + normalized line).
-- The receipt line itself is never stored.
create table parse_cache (
    cache_key      char(64)         primary key,
    prompt_version text             not null,
    is_grocery     boolean          not null,
    name           text             not null,
    category       text             not null,
    quantity       double precision not null check (quantity > 0),
    unit           text             not null,
    created_at     timestamptz      not null default now()
);

-- Lets old prompt versions be cleaned up in one statement.
create index parse_cache_prompt_version on parse_cache (prompt_version);

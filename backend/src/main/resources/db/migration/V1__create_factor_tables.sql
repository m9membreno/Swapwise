-- Carbon factors are versioned: an import adds a new release, it never edits old rows.
create table factor_release (
    version     integer primary key,
    source      text        not null,
    imported_at timestamptz not null default now(),
    is_current  boolean     not null default false
);

-- At most one current release.
create unique index factor_release_one_current on factor_release (is_current) where is_current;

create table carbon_factor (
    version             integer          not null references factor_release (version),
    category            text             not null,
    kg_co2e_per_kg      double precision not null,
    kg_co2e_per_kg_low  double precision not null,
    kg_co2e_per_kg_high double precision not null,
    typical_item_kg     double precision,
    source_codes        text             not null,
    primary key (version, category),
    constraint carbon_factor_range check (
        kg_co2e_per_kg_low >= 0
        and kg_co2e_per_kg_low <= kg_co2e_per_kg
        and kg_co2e_per_kg <= kg_co2e_per_kg_high
    ),
    constraint carbon_factor_item_weight check (typical_item_kg is null or typical_item_kg > 0)
);

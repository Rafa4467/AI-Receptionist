-- Restaurants (Multi-tenant root)
create table if not exists restaurants (
                                           id              uuid primary key,
                                           name            text not null,
                                           timezone        text not null default 'Europe/Vienna',
                                           phone_e164      text,
                                           greeting_text   text,
                                           voice           text default 'coral',
                                           created_at      timestamptz not null default now()
    );

-- Öffnungszeiten pro Wochentag (0=Sonntag ... 6=Samstag)
create table if not exists opening_hours (
                                             id              bigserial primary key,
                                             restaurant_id   uuid not null references restaurants(id) on delete cascade,
    weekday         int not null check (weekday between 0 and 6),
    open_time       time not null,
    close_time      time not null,
    -- optional: separate lunch/dinner windows -> mehrere Rows pro weekday ok
    unique (restaurant_id, weekday, open_time, close_time)
    );

-- Ausnahmen / Schließtage (z.B. Urlaub, Feiertag)
create table if not exists closures (
                                        id              bigserial primary key,
                                        restaurant_id   uuid not null references restaurants(id) on delete cascade,
    start_date      date not null,
    end_date        date not null,
    reason          text
    );

-- Reservierungsregeln
create table if not exists reservation_rules (
                                                 restaurant_id     uuid primary key references restaurants(id) on delete cascade,
    slot_minutes      int not null default 15,    -- Timeslot Raster
    default_duration  int not null default 120,   -- Dauer in Minuten
    max_party_size    int not null default 12,
    min_notice_min    int not null default 30,    -- früheste Buchung in Minuten ab jetzt
    phone_confirm     boolean not null default true
    );

-- Kapazität (einfach & schnell): wie viele Personen max parallel im Slot
-- Wenn du später Tische modellieren willst, kann man das erweitern.
create table if not exists capacity_rules (
                                              restaurant_id   uuid primary key references restaurants(id) on delete cascade,
    capacity_total  int not null default 60
    );

-- Reservierungen
create table if not exists reservations (
                                            id              uuid primary key,
                                            restaurant_id   uuid not null references restaurants(id) on delete cascade,

    start_ts        timestamptz not null,
    end_ts          timestamptz not null,

    party_size      int not null check (party_size > 0),
    name            text not null,
    phone_raw       text,
    phone_e164      text,

    status          text not null check (status in ('PENDING','CONFIRMED','CANCELLED','NO_SHOW')),
    confirmation_code text, -- für SMS "JA/NEIN" oder Link später

    notes           text,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
    );

create index if not exists idx_res_by_rest_start on reservations(restaurant_id, start_ts);
create index if not exists idx_res_by_rest_status on reservations(restaurant_id, status);
create index if not exists idx_res_by_phone on reservations(restaurant_id, phone_e164);

-- Updated_at trigger (optional)
create or replace function set_updated_at()
returns trigger as $$
begin
  new.updated_at = now();
return new;
end;
$$ language plpgsql;

drop trigger if exists trg_res_updated on reservations;
create trigger trg_res_updated
    before update on reservations
    for each row execute function set_updated_at();
-- Spendly schema.
--
-- Run once in the Supabase SQL editor (Dashboard -> SQL Editor -> New query).
--
-- Re-running it is safe: every statement is idempotent, so an existing project
-- can be brought up to date with the same query.
--
-- The shape mirrors src/lib/types.ts exactly: transactions are the money that
-- actually moved, budget_lines are the 'Aylıq rasxod' plan, income_plans are
-- 'BÜDCƏ İCMALI'!C11:C12, and categories are the list the user maintains.
-- Totals are never stored — they are derived in the app, so the database
-- cannot disagree with the spreadsheet logic.

-- ---------------------------------------------------------------------------
-- Tables
-- ---------------------------------------------------------------------------

create table if not exists public.transactions (
  id          text not null,
  user_id     uuid not null default auth.uid()
                references auth.users (id) on delete cascade,
  date        date not null,
  type        text not null check (type in ('income', 'expense')),
  category    text not null,
  description text not null check (length(btrim(description)) > 0),
  -- Amounts are always stored positive; direction comes from `type`.
  amount      numeric(14, 2) not null check (amount > 0),
  note        text,
  created_at  timestamptz not null default now(),
  -- Ids are minted in the browser and are only ever unique to one person.
  -- Accounts made while the app handed out a starting set of categories and a
  -- plan template all carry the same ids for those rows. So the owner is part
  -- of the key — two people holding the same id is normal, not a conflict.
  primary key (user_id, id)
);

create table if not exists public.budget_lines (
  id          text not null,
  user_id     uuid not null default auth.uid()
                references auth.users (id) on delete cascade,
  -- 'YYYY-MM'. One spreadsheet file was one month.
  month       text not null check (month ~ '^\d{4}-\d{2}$'),
  description text not null check (length(btrim(description)) > 0),
  category    text not null,
  -- Zero is valid: the sheet has tracked-but-unbudgeted lines.
  planned     numeric(14, 2) not null default 0 check (planned >= 0),
  created_at  timestamptz not null default now(),
  -- Keyed by owner as well, for the reason given on `transactions`.
  primary key (user_id, id)
);

-- Categories are user data: the app seeds a starting set and the user adds to,
-- renames and removes them. Rows elsewhere reference a category by name, the
-- way the spreadsheet did — the id is here so a rename stays one record.
create table if not exists public.categories (
  id         text not null,
  user_id    uuid not null default auth.uid()
               references auth.users (id) on delete cascade,
  name       text not null check (length(btrim(name)) > 0),
  type       text not null check (type in ('income', 'expense')),
  -- What the category is for, used by the needs/wants frameworks. Null means
  -- unclassified, which the app reports rather than guessing around.
  kind       text check (kind is null or kind in
               ('essential', 'discretionary', 'debt', 'saving')),
  created_at timestamptz not null default now(),
  -- Keyed by owner as well, for the reason given on `transactions`.
  primary key (user_id, id),
  -- One name per side of the ledger. An expense and an income category may
  -- share a name, because nothing looks a category up without its type.
  unique (user_id, type, name)
);

-- The planned side of income: a figure per income category, as
-- {"Maaş": 990.00}. The sheet had two fixed rows here, which is what the
-- legacy salary/additional columns below were; they are kept so an existing
-- project's data survives this migration, and are read only when `amounts` is
-- empty.
create table if not exists public.income_plans (
  user_id    uuid not null default auth.uid()
               references auth.users (id) on delete cascade,
  month      text not null check (month ~ '^\d{4}-\d{2}$'),
  amounts    jsonb not null default '{}'::jsonb,
  salary     numeric(14, 2) not null default 0 check (salary >= 0),
  additional numeric(14, 2) not null default 0 check (additional >= 0),
  primary key (user_id, month)
);

-- Money set aside. A pot is a goal with a name; an entry is one movement into
-- or out of it.
--
-- These are not transactions and must not be stored as any: setting money
-- aside does not consume it, so a deposit belongs in no spending total, and a
-- withdrawal belongs in no income total. Keeping them apart is the whole point
-- of the table — it is what lets the app say "spendable" and "saved" as two
-- different figures that still add up to one.
create table if not exists public.savings_pots (
  id         text not null,
  user_id    uuid not null default auth.uid()
               references auth.users (id) on delete cascade,
  name       text not null check (length(btrim(name)) > 0),
  -- What the pot is being filled towards. Null means no target, which the
  -- screens report as a balance rather than as progress towards nothing.
  target     numeric(14, 2) check (target is null or target > 0),
  created_at timestamptz not null default now(),
  -- Keyed by owner as well, for the reason given on `transactions`.
  primary key (user_id, id),
  unique (user_id, name)
);

create table if not exists public.savings_entries (
  id         text not null,
  user_id    uuid not null default auth.uid()
               references auth.users (id) on delete cascade,
  date       date not null,
  -- The pot's name, the way every other row in this schema names what it
  -- belongs to. A rename carries the entries with it.
  pot        text not null,
  -- Positive, always. `direction` carries the sign.
  amount     numeric(14, 2) not null check (amount > 0),
  direction  text not null check (direction in ('in', 'out')),
  -- Where a deposit came from, and the reason this table exists at all:
  --   income   — set aside out of money already earned, so it leaves the
  --              spendable side without being spending.
  --   external — arrived from outside straight into the pot, so it touches
  --              neither income nor spending.
  -- A withdrawal has no source, and must not carry one.
  source     text check (
               (direction = 'in' and source in ('income', 'external')) or
               (direction = 'out' and source is null)),
  note       text,
  created_at timestamptz not null default now(),
  primary key (user_id, id)
);

create index if not exists savings_entries_user_date_idx
  on public.savings_entries (user_id, date);

-- The savings side of the month's plan: a figure per pot, as
-- {"Ehtiyat fondu": 400.00}. Shaped like income_plans because it is the same
-- kind of statement — what is meant to happen, against which what did happen
-- is measured. A pot's `target` says how much in the end; this says how much
-- this month, which is the part a budget can hold you to.
create table if not exists public.savings_plans (
  user_id    uuid not null default auth.uid()
               references auth.users (id) on delete cascade,
  month      text not null check (month ~ '^\d{4}-\d{2}$'),
  amounts    jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  primary key (user_id, month)
);

-- Brings a project keyed on the id alone up to date.
--
-- A bare `id` primary key is global, but ids are not: accounts made while the
-- app seeded a starting set all carry the same ids. The second person to sign up
-- collided with the first one's rows — and because row level security hides
-- those rows, the collision surfaced as "new row violates row-level security
-- policy (USING expression)" rather than as a duplicate key. Widening the key
-- to (user_id, id) gives each account its own id space.
do $$
declare
  t       text;
  pk_name text;
  pk_cols text[];
begin
  foreach t in array array['transactions', 'budget_lines', 'categories']
  loop
    -- The column names of the current primary key, sorted so the comparison
    -- below does not depend on the order they were declared in.
    select c.conname,
           (select array_agg(a.attname::text order by a.attname)
              from pg_attribute a
             where a.attrelid = c.conrelid
               and a.attnum = any (c.conkey))
      into pk_name, pk_cols
      from pg_constraint c
     where c.conrelid = format('public.%I', t)::regclass
       and c.contype = 'p';

    if pk_cols is distinct from array['id', 'user_id']::text[] then
      if pk_name is not null then
        execute format('alter table public.%I drop constraint %I', t, pk_name);
      end if;
      execute format('alter table public.%I add primary key (user_id, id)', t);
    end if;
  end loop;
end $$;

-- Brings a project created before income categories were editable up to date.
alter table public.income_plans
  add column if not exists amounts jsonb not null default '{}'::jsonb;

-- Brings a project created before categories carried a kind up to date.
alter table public.categories
  add column if not exists kind text;

do $$
begin
  alter table public.categories
    add constraint categories_kind_check
    check (kind is null or kind in ('essential', 'discretionary', 'debt', 'saving'));
exception
  when duplicate_object then null;
end $$;

-- Every query the app makes is "my rows, for these months".
create index if not exists transactions_user_date_idx
  on public.transactions (user_id, date);
create index if not exists budget_lines_user_month_idx
  on public.budget_lines (user_id, month);

-- ---------------------------------------------------------------------------
-- Row level security
--
-- The publishable key is public: it is in the JavaScript bundle of every
-- visitor. These policies are what actually keep the data private, so RLS must
-- stay enabled on every table.
-- ---------------------------------------------------------------------------

alter table public.transactions enable row level security;
alter table public.budget_lines enable row level security;
alter table public.income_plans enable row level security;
alter table public.categories enable row level security;
alter table public.savings_pots enable row level security;
alter table public.savings_entries enable row level security;
alter table public.savings_plans enable row level security;

do $$
declare
  t text;
begin
  foreach t in array array['transactions', 'budget_lines', 'income_plans',
                           'categories', 'savings_pots', 'savings_entries',
                           'savings_plans']
  loop
    execute format('drop policy if exists owner_select on public.%I', t);
    execute format('drop policy if exists owner_insert on public.%I', t);
    execute format('drop policy if exists owner_update on public.%I', t);
    execute format('drop policy if exists owner_delete on public.%I', t);

    execute format(
      'create policy owner_select on public.%I for select
         to authenticated using (user_id = auth.uid())', t);
    execute format(
      'create policy owner_insert on public.%I for insert
         to authenticated with check (user_id = auth.uid())', t);
    execute format(
      'create policy owner_update on public.%I for update
         to authenticated using (user_id = auth.uid())
         with check (user_id = auth.uid())', t);
    execute format(
      'create policy owner_delete on public.%I for delete
         to authenticated using (user_id = auth.uid())', t);
  end loop;
end $$;

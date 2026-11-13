-- RejTag: private friendship and foreground-only live-location prototype.
-- RejTag objects are isolated under rejtag_private and public.rejtag_*; do not alter RejHub objects.
-- All access is through the authenticated RPCs below. Supabase Auth accounts are separate from RejHub community accounts.
create schema if not exists rejtag_private;
revoke all on schema rejtag_private from public, anon, authenticated;

create table rejtag_private.invites (
  token_hash bytea primary key,
  owner_id uuid not null references auth.users(id) on delete cascade,
  expires_at timestamptz not null default (now() + interval '24 hours')
);
create table rejtag_private.friendships (
  user_a uuid not null references auth.users(id) on delete cascade,
  user_b uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_a, user_b),
  check (user_a < user_b)
);
create table rejtag_private.shares (
  owner_id uuid not null references auth.users(id) on delete cascade,
  viewer_id uuid not null references auth.users(id) on delete cascade,
  primary key (owner_id, viewer_id),
  check (owner_id <> viewer_id)
);
create table rejtag_private.positions (
  owner_id uuid primary key references auth.users(id) on delete cascade,
  lat double precision not null check (lat between -90 and 90),
  lon double precision not null check (lon between -180 and 180),
  accuracy double precision not null check (accuracy >= 0 and accuracy <= 100000),
  fix_at timestamptz not null,
  updated_at timestamptz not null default now()
);
-- Defense in depth; the schema and tables are not directly granted to clients.
alter table rejtag_private.invites enable row level security;
alter table rejtag_private.friendships enable row level security;
alter table rejtag_private.shares enable row level security;
alter table rejtag_private.positions enable row level security;

create function public.rejtag_invite() returns text
language plpgsql security definer set search_path = pg_catalog, rejtag_private as $$
declare uid uuid := auth.uid(); token text;
begin
  if uid is null then raise exception 'not authenticated'; end if;
  delete from rejtag_private.invites where owner_id = uid or expires_at < now();
  token := replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', '');
  insert into rejtag_private.invites(token_hash, owner_id)
    values (sha256(convert_to(token, 'UTF8')), uid);
  return token;
end $$;

create function public.rejtag_accept_invite(p_token text) returns uuid
language plpgsql security definer set search_path = pg_catalog, rejtag_private as $$
declare uid uuid := auth.uid(); inviter uuid;
begin
  if uid is null then raise exception 'not authenticated'; end if;
  if p_token !~ '^[0-9a-f]{64}$' then raise exception 'invalid invite'; end if;
  select owner_id into inviter from rejtag_private.invites
    where token_hash = sha256(convert_to(p_token, 'UTF8')) and expires_at > now() for update;
  if inviter is null or inviter = uid then raise exception 'invalid invite'; end if;
  insert into rejtag_private.friendships(user_a, user_b)
    values (least(uid, inviter), greatest(uid, inviter)) on conflict do nothing;
  delete from rejtag_private.invites where token_hash = sha256(convert_to(p_token, 'UTF8'));
  return inviter;
end $$;

create function public.rejtag_friends() returns table(friend_id uuid, sharing_with_friend boolean, friend_sharing boolean)
language plpgsql security definer set search_path = pg_catalog, rejtag_private as $$
declare uid uuid := auth.uid();
begin
  if uid is null then raise exception 'not authenticated'; end if;
  return query select f.friend_id,
    exists(select 1 from rejtag_private.shares s where s.owner_id = uid and s.viewer_id = f.friend_id),
    exists(select 1 from rejtag_private.shares s where s.owner_id = f.friend_id and s.viewer_id = uid)
  from (select case when user_a = uid then user_b else user_a end as friend_id
        from rejtag_private.friendships where user_a = uid or user_b = uid) f;
end $$;

create function public.rejtag_set_sharing(p_friend uuid, p_enabled boolean) returns void
language plpgsql security definer set search_path = pg_catalog, rejtag_private as $$
declare uid uuid := auth.uid();
begin
  if uid is null then raise exception 'not authenticated'; end if;
  perform pg_advisory_xact_lock(hashtextextended(uid::text, 0));
  if p_friend is null or p_enabled is null or not exists (
    select 1 from rejtag_private.friendships where user_a = least(uid, p_friend) and user_b = greatest(uid, p_friend)
  ) then raise exception 'not friends'; end if;
  if p_enabled then
    insert into rejtag_private.shares(owner_id, viewer_id) values(uid, p_friend) on conflict do nothing;
  else
    delete from rejtag_private.shares where owner_id = uid and viewer_id = p_friend;
    if not exists(select 1 from rejtag_private.shares where owner_id = uid) then
      delete from rejtag_private.positions where owner_id = uid;
    end if;
  end if;
end $$;

create function public.rejtag_stop_all() returns void
language plpgsql security definer set search_path = pg_catalog, rejtag_private as $$
declare uid uuid := auth.uid();
begin
  if uid is null then raise exception 'not authenticated'; end if;
  perform pg_advisory_xact_lock(hashtextextended(uid::text, 0));
  delete from rejtag_private.shares where owner_id = uid;
  delete from rejtag_private.positions where owner_id = uid;
  delete from rejtag_private.invites where owner_id = uid;
end $$;

create function public.rejtag_remove_friend(p_friend uuid) returns void
language plpgsql security definer set search_path = pg_catalog, rejtag_private as $$
declare uid uuid := auth.uid();
begin
  if uid is null then raise exception 'not authenticated'; end if;
  if p_friend is null then raise exception 'invalid friend'; end if;
  perform pg_advisory_xact_lock(hashtextextended(least(uid, p_friend)::text, 0));
  perform pg_advisory_xact_lock(hashtextextended(greatest(uid, p_friend)::text, 0));
  if not exists (select 1 from rejtag_private.friendships where user_a = least(uid, p_friend) and user_b = greatest(uid, p_friend)) then
    raise exception 'not friends';
  end if;
  delete from rejtag_private.shares where (owner_id = uid and viewer_id = p_friend) or (owner_id = p_friend and viewer_id = uid);
  delete from rejtag_private.friendships where user_a = least(uid, p_friend) and user_b = greatest(uid, p_friend);
  delete from rejtag_private.positions p where p.owner_id in (uid, p_friend)
    and not exists (select 1 from rejtag_private.shares s where s.owner_id = p.owner_id);
end $$;

create function public.rejtag_publish(p_lat double precision, p_lon double precision, p_accuracy double precision, p_fix_at timestamptz) returns void
language plpgsql security definer set search_path = pg_catalog, rejtag_private as $$
declare uid uuid := auth.uid();
begin
  if uid is null then raise exception 'not authenticated'; end if;
  perform pg_advisory_xact_lock(hashtextextended(uid::text, 0));
  if not exists (select 1 from rejtag_private.shares where owner_id = uid) then raise exception 'sharing disabled'; end if;
  if p_lat is null or p_lon is null or p_accuracy is null or p_fix_at is null
     or p_lat not between -90 and 90 or p_lon not between -180 and 180
     or p_accuracy not between 0 and 100000
     or p_fix_at < now() - interval '2 minutes' or p_fix_at > now() + interval '30 seconds'
  then raise exception 'invalid position'; end if;
  insert into rejtag_private.positions(owner_id, lat, lon, accuracy, fix_at, updated_at)
    values(uid, p_lat, p_lon, p_accuracy, p_fix_at, now())
    on conflict(owner_id) do update set lat=excluded.lat, lon=excluded.lon, accuracy=excluded.accuracy,
      fix_at=excluded.fix_at, updated_at=excluded.updated_at
      where rejtag_private.positions.fix_at <= excluded.fix_at;
end $$;

create function public.rejtag_read_position(p_friend uuid)
returns table(lat double precision, lon double precision, accuracy double precision, fix_at timestamptz)
language plpgsql security definer set search_path = pg_catalog, rejtag_private as $$
declare uid uuid := auth.uid();
begin
  if uid is null then raise exception 'not authenticated'; end if;
  return query select p.lat, p.lon, p.accuracy, p.fix_at from rejtag_private.positions p
    where p.owner_id = p_friend and p.fix_at > now() - interval '2 minutes'
      and p.updated_at > now() - interval '2 minutes'
      and exists(select 1 from rejtag_private.shares s where s.owner_id = p_friend and s.viewer_id = uid);
end $$;

revoke all on function public.rejtag_invite(), public.rejtag_accept_invite(text), public.rejtag_friends(),
 public.rejtag_set_sharing(uuid, boolean), public.rejtag_stop_all(), public.rejtag_remove_friend(uuid),
 public.rejtag_publish(double precision, double precision, double precision, timestamptz), public.rejtag_read_position(uuid) from public, anon;
grant execute on function public.rejtag_invite(), public.rejtag_accept_invite(text), public.rejtag_friends(),
 public.rejtag_set_sharing(uuid, boolean), public.rejtag_stop_all(), public.rejtag_remove_friend(uuid),
 public.rejtag_publish(double precision, double precision, double precision, timestamptz), public.rejtag_read_position(uuid) to authenticated;

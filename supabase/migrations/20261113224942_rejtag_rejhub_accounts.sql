-- Migrate the unused RejTag prototype to RejHub community identities.
-- Fail rather than deleting or silently reassigning any existing prototype data.
lock table rejtag_private.invites, rejtag_private.friendships, rejtag_private.shares, rejtag_private.positions in access exclusive mode;
do $$ begin
  if exists(select 1 from rejtag_private.invites) or exists(select 1 from rejtag_private.friendships)
     or exists(select 1 from rejtag_private.shares) or exists(select 1 from rejtag_private.positions) then
    raise exception 'RejTag prototype contains data; account migration requires a separate plan';
  end if;
end $$;

-- Old Supabase Auth RPCs no longer accept requests after this migration.
revoke all on function public.rejtag_invite(), public.rejtag_accept_invite(text), public.rejtag_friends(),
 public.rejtag_set_sharing(uuid, boolean), public.rejtag_stop_all(), public.rejtag_remove_friend(uuid),
 public.rejtag_publish(double precision, double precision, double precision, timestamptz), public.rejtag_read_position(uuid)
 from public, anon, authenticated;

alter table rejtag_private.invites drop constraint invites_owner_id_fkey,
  add constraint invites_owner_id_fkey foreign key (owner_id) references public.community_users(id) on delete cascade;
alter table rejtag_private.friendships drop constraint friendships_user_a_fkey, drop constraint friendships_user_b_fkey,
  add constraint friendships_user_a_fkey foreign key (user_a) references public.community_users(id) on delete cascade,
  add constraint friendships_user_b_fkey foreign key (user_b) references public.community_users(id) on delete cascade;
alter table rejtag_private.shares drop constraint shares_owner_id_fkey, drop constraint shares_viewer_id_fkey,
  add constraint shares_owner_id_fkey foreign key (owner_id) references public.community_users(id) on delete cascade,
  add constraint shares_viewer_id_fkey foreign key (viewer_id) references public.community_users(id) on delete cascade;
alter table rejtag_private.positions drop constraint positions_owner_id_fkey,
  add constraint positions_owner_id_fkey foreign key (owner_id) references public.community_users(id) on delete cascade;

-- The server verifies each session using the same function as other RejHub apps.
-- The account ID is never supplied by the client.
create function rejtag_private.session_user_id(p_session text) returns uuid
language plpgsql security definer set search_path = pg_catalog, rejtag_private as $$
declare uid uuid;
begin
  select s.user_id into uid from public.community_session_user(p_session) s;
  if uid is null then raise exception 'not authenticated'; end if;
  return uid;
end $$;
revoke all on function rejtag_private.session_user_id(text) from public, anon, authenticated;

create function public.rejtag_v2_invite(p_session text) returns text
language plpgsql security definer set search_path = pg_catalog, rejtag_private as $$
declare uid uuid := rejtag_private.session_user_id(p_session); token text;
begin
  if uid is null then raise exception 'not authenticated'; end if;
  delete from rejtag_private.invites where owner_id = uid or expires_at < now();
  token := replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', '');
  insert into rejtag_private.invites(token_hash, owner_id)
    values (sha256(convert_to(token, 'UTF8')), uid);
  return token;
end $$;

create function public.rejtag_v2_accept_invite(p_session text, p_token text) returns uuid
language plpgsql security definer set search_path = pg_catalog, rejtag_private as $$
declare uid uuid := rejtag_private.session_user_id(p_session); inviter uuid;
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

create function public.rejtag_v2_friends(p_session text) returns table(friend_id uuid, username text, sharing_with_friend boolean, friend_sharing boolean)
language plpgsql security definer set search_path = pg_catalog, rejtag_private as $$
declare uid uuid := rejtag_private.session_user_id(p_session);
begin
  if uid is null then raise exception 'not authenticated'; end if;
  return query select f.friend_id, u.username::text,
    exists(select 1 from rejtag_private.shares s where s.owner_id = uid and s.viewer_id = f.friend_id),
    exists(select 1 from rejtag_private.shares s where s.owner_id = f.friend_id and s.viewer_id = uid)
  from (select case when user_a = uid then user_b else user_a end as friend_id
        from rejtag_private.friendships where user_a = uid or user_b = uid) f
  join public.community_users u on u.id = f.friend_id;
end $$;

create function public.rejtag_v2_set_sharing(p_session text, p_friend uuid, p_enabled boolean) returns void
language plpgsql security definer set search_path = pg_catalog, rejtag_private as $$
declare uid uuid := rejtag_private.session_user_id(p_session);
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

create function public.rejtag_v2_stop_all(p_session text) returns void
language plpgsql security definer set search_path = pg_catalog, rejtag_private as $$
declare uid uuid := rejtag_private.session_user_id(p_session);
begin
  if uid is null then raise exception 'not authenticated'; end if;
  perform pg_advisory_xact_lock(hashtextextended(uid::text, 0));
  delete from rejtag_private.shares where owner_id = uid;
  delete from rejtag_private.positions where owner_id = uid;
  delete from rejtag_private.invites where owner_id = uid;
end $$;

create function public.rejtag_v2_remove_friend(p_session text, p_friend uuid) returns void
language plpgsql security definer set search_path = pg_catalog, rejtag_private as $$
declare uid uuid := rejtag_private.session_user_id(p_session);
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

create function public.rejtag_v2_publish(p_session text, p_lat double precision, p_lon double precision, p_accuracy double precision, p_fix_at timestamptz) returns void
language plpgsql security definer set search_path = pg_catalog, rejtag_private as $$
declare uid uuid := rejtag_private.session_user_id(p_session);
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

create function public.rejtag_v2_read_position(p_session text, p_friend uuid)
returns table(lat double precision, lon double precision, accuracy double precision, fix_at timestamptz)
language plpgsql security definer set search_path = pg_catalog, rejtag_private as $$
declare uid uuid := rejtag_private.session_user_id(p_session);
begin
  if uid is null then raise exception 'not authenticated'; end if;
  return query select p.lat, p.lon, p.accuracy, p.fix_at from rejtag_private.positions p
    where p.owner_id = p_friend and p.fix_at > now() - interval '2 minutes'
      and p.updated_at > now() - interval '2 minutes'
      and exists(select 1 from rejtag_private.shares s where s.owner_id = p_friend and s.viewer_id = uid);
end $$;

-- End only the session issued to this client and remove its active location shares.
-- Other RejHub sessions are untouched. Failure rolls back both actions.
create function public.rejtag_v2_logout(p_session text) returns void
language plpgsql security definer set search_path = pg_catalog, rejtag_private as $$
declare uid uuid := rejtag_private.session_user_id(p_session);
begin
  perform pg_advisory_xact_lock(hashtextextended(uid::text, 0));
  delete from rejtag_private.shares where owner_id = uid;
  delete from rejtag_private.positions where owner_id = uid;
  delete from rejtag_private.invites where owner_id = uid;
  delete from public.community_sessions
    where user_id = uid and token_hash = encode(sha256(convert_to('rejson_comm_sess_v1:' || trim(p_session), 'UTF8')), 'hex');
  if not found then raise exception 'session unavailable'; end if;
end $$;

-- The site uses a public anon key; each function authenticates its RejHub session itself.
revoke all on function public.rejtag_v2_invite(text), public.rejtag_v2_accept_invite(text, text), public.rejtag_v2_friends(text),
 public.rejtag_v2_set_sharing(text, uuid, boolean), public.rejtag_v2_stop_all(text), public.rejtag_v2_logout(text), public.rejtag_v2_remove_friend(text, uuid),
 public.rejtag_v2_publish(text, double precision, double precision, double precision, timestamptz), public.rejtag_v2_read_position(text, uuid) from public, anon, authenticated;
grant execute on function public.rejtag_v2_invite(text), public.rejtag_v2_accept_invite(text, text), public.rejtag_v2_friends(text),
 public.rejtag_v2_set_sharing(text, uuid, boolean), public.rejtag_v2_stop_all(text), public.rejtag_v2_logout(text), public.rejtag_v2_remove_friend(text, uuid),
 public.rejtag_v2_publish(text, double precision, double precision, double precision, timestamptz), public.rejtag_v2_read_position(text, uuid) to anon, authenticated;

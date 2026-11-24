-- Sotigram messaging and owner capabilities
create table if not exists public.message_reactions (
  message_id uuid not null references public.messages(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  emoji text not null check (char_length(emoji) between 1 and 32),
  created_at timestamptz not null default now(),
  primary key (message_id, user_id, emoji)
);
alter table public.message_reactions enable row level security;

create table if not exists public.message_receipts (
  message_id uuid not null references public.messages(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  read_at timestamptz not null default now(),
  primary key (message_id, user_id)
);
alter table public.message_receipts enable row level security;

create table if not exists public.user_blocks (
  blocker_id uuid not null references auth.users(id) on delete cascade,
  blocked_id uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (blocker_id, blocked_id),
  check (blocker_id <> blocked_id)
);
alter table public.user_blocks enable row level security;

create table if not exists public.stories (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  media_path text not null,
  media_type text not null check (media_type in ('image','video')),
  caption text not null default '' check (char_length(caption) <= 500),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default (now() + interval '24 hours')
);
create index if not exists stories_active_idx on public.stories (expires_at desc);
alter table public.stories enable row level security;

create table if not exists public.saved_messages (
  user_id uuid not null references auth.users(id) on delete cascade,
  message_id uuid not null references public.messages(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (user_id, message_id)
);
alter table public.saved_messages enable row level security;

create or replace function app_private.is_sotigram_owner()
returns boolean language sql stable security definer
set search_path = pg_catalog, public
as $$
  select exists (select 1 from public.user_roles r where r.user_id = auth.uid() and r.role = 'owner');
$$;
revoke all on function app_private.is_sotigram_owner() from public;
grant execute on function app_private.is_sotigram_owner() to authenticated;

create policy "members read reactions in their conversations"
on public.message_reactions for select to authenticated
using (exists (select 1 from public.messages m where m.id = message_id and app_private.is_conversation_member(m.conversation_id)));
create policy "users add own reactions"
on public.message_reactions for insert to authenticated
with check (user_id = auth.uid() and exists (select 1 from public.messages m where m.id = message_id and app_private.is_conversation_member(m.conversation_id)));
create policy "users remove own reactions"
on public.message_reactions for delete to authenticated using (user_id = auth.uid());

create policy "members read receipts in their conversations"
on public.message_receipts for select to authenticated
using (exists (select 1 from public.messages m where m.id = message_id and app_private.is_conversation_member(m.conversation_id)));
create policy "users mark own messages as read"
on public.message_receipts for insert to authenticated
with check (user_id = auth.uid() and exists (select 1 from public.messages m where m.id = message_id and app_private.is_conversation_member(m.conversation_id)));

create policy "users manage blocks they created"
on public.user_blocks for all to authenticated using (blocker_id = auth.uid()) with check (blocker_id = auth.uid());

create policy "signed in users read active stories"
on public.stories for select to authenticated using (expires_at > now());
create policy "users publish own stories"
on public.stories for insert to authenticated
with check (user_id = auth.uid() and expires_at <= now() + interval '25 hours');
create policy "users delete own stories"
on public.stories for delete to authenticated using (user_id = auth.uid());

create policy "users manage their saved messages"
on public.saved_messages for all to authenticated using (user_id = auth.uid()) with check (user_id = auth.uid());

create or replace function public.sotigram_create_direct_conversation(other_user uuid)
returns uuid language plpgsql security definer
set search_path = pg_catalog, public, app_private
as $$
declare me uuid := auth.uid(); conversation_id uuid;
begin
  if me is null then raise exception 'not_authenticated'; end if;
  if other_user is null or other_user = me then raise exception 'invalid_recipient'; end if;
  if not exists (select 1 from auth.users where id = other_user) then raise exception 'user_not_found'; end if;
  if exists (select 1 from public.user_blocks b where (b.blocker_id=me and b.blocked_id=other_user) or (b.blocker_id=other_user and b.blocked_id=me)) then
    raise exception 'conversation_not_allowed';
  end if;
  select c.id into conversation_id
  from public.conversations c
  join public.conversation_members a on a.conversation_id=c.id and a.user_id=me
  join public.conversation_members b on b.conversation_id=c.id and b.user_id=other_user
  where c.kind='direct' and (select count(*) from public.conversation_members x where x.conversation_id=c.id)=2
  order by c.created_at desc limit 1;
  if conversation_id is null then
    insert into public.conversations(kind,created_by) values ('direct',me) returning id into conversation_id;
    insert into public.conversation_members(conversation_id,user_id,member_role)
    values (conversation_id,me,'owner'),(conversation_id,other_user,'member');
  end if;
  return conversation_id;
end;
$$;
revoke all on function public.sotigram_create_direct_conversation(uuid) from public, anon;
grant execute on function public.sotigram_create_direct_conversation(uuid) to authenticated;

create or replace function public.sotigram_create_group(group_title text, member_ids uuid[] default '{}')
returns uuid language plpgsql security definer
set search_path = pg_catalog, public, app_private
as $$
declare me uuid := auth.uid(); conversation_id uuid; member_id uuid;
begin
  if me is null then raise exception 'not_authenticated'; end if;
  if nullif(trim(group_title),'') is null or char_length(group_title)>80 then raise exception 'invalid_group_title'; end if;
  if coalesce(cardinality(member_ids),0)>99 then raise exception 'too_many_members'; end if;
  insert into public.conversations(kind,title,created_by) values ('group',trim(group_title),me) returning id into conversation_id;
  insert into public.conversation_members(conversation_id,user_id,member_role) values (conversation_id,me,'owner');
  for member_id in select distinct x from unnest(coalesce(member_ids,'{}'::uuid[])) x where x is not null and x<>me loop
    if exists (select 1 from public.user_blocks b where (b.blocker_id=me and b.blocked_id=member_id) or (b.blocker_id=member_id and b.blocked_id=me)) then continue; end if;
    if exists (select 1 from auth.users u where u.id=member_id) then
      insert into public.conversation_members(conversation_id,user_id,member_role) values (conversation_id,member_id,'member') on conflict do nothing;
    end if;
  end loop;
  return conversation_id;
end;
$$;
revoke all on function public.sotigram_create_group(text,uuid[]) from public, anon;
grant execute on function public.sotigram_create_group(text,uuid[]) to authenticated;

create or replace function public.sotigram_toggle_reaction(target_message uuid, reaction_emoji text)
returns boolean language plpgsql security definer
set search_path = pg_catalog, public, app_private
as $$
declare me uuid := auth.uid();
begin
  if me is null then raise exception 'not_authenticated'; end if;
  if char_length(coalesce(reaction_emoji,'')) not between 1 and 32 then raise exception 'invalid_emoji'; end if;
  if not exists (select 1 from public.messages m where m.id=target_message and app_private.is_conversation_member(m.conversation_id)) then raise exception 'message_not_found'; end if;
  delete from public.message_reactions where message_id=target_message and user_id=me and emoji=reaction_emoji;
  if found then return false; end if;
  insert into public.message_reactions(message_id,user_id,emoji) values (target_message,me,reaction_emoji);
  return true;
end;
$$;
revoke all on function public.sotigram_toggle_reaction(uuid,text) from public, anon;
grant execute on function public.sotigram_toggle_reaction(uuid,text) to authenticated;

create or replace function public.sotigram_mark_read(target_message uuid)
returns void language plpgsql security definer
set search_path = pg_catalog, public, app_private
as $$
begin
  if auth.uid() is null then raise exception 'not_authenticated'; end if;
  if not exists (select 1 from public.messages m where m.id=target_message and app_private.is_conversation_member(m.conversation_id)) then raise exception 'message_not_found'; end if;
  insert into public.message_receipts(message_id,user_id) values (target_message,auth.uid())
  on conflict (message_id,user_id) do update set read_at=excluded.read_at;
end;
$$;
revoke all on function public.sotigram_mark_read(uuid) from public, anon;
grant execute on function public.sotigram_mark_read(uuid) to authenticated;

create or replace function public.sotigram_owner_overview()
returns jsonb language plpgsql security definer
set search_path = pg_catalog, public, app_private
as $$
begin
  if auth.uid() is null or not app_private.is_sotigram_owner() then raise exception 'owner_only'; end if;
  return jsonb_build_object(
    'users',(select count(*) from public.profiles),
    'chats',(select count(*) from public.conversations),
    'messages',(select count(*) from public.messages),
    'open_reports',(select count(*) from public.reports where status in ('open','reviewing')),
    'admins',(select count(distinct user_id) from public.user_roles where role in ('owner','admin','moderator'))
  );
end;
$$;
revoke all on function public.sotigram_owner_overview() from public, anon;
grant execute on function public.sotigram_owner_overview() to authenticated;

create or replace function public.sotigram_owner_set_role(target_user uuid, new_role text)
returns void language plpgsql security definer
set search_path = pg_catalog, public, app_private
as $$
begin
  if auth.uid() is null or not app_private.is_sotigram_owner() then raise exception 'owner_only'; end if;
  if new_role not in ('admin','moderator') then raise exception 'invalid_role'; end if;
  if target_user is null or target_user=auth.uid() then raise exception 'invalid_target'; end if;
  insert into public.user_roles(user_id,role) values (target_user,new_role) on conflict (user_id,role) do nothing;
end;
$$;
revoke all on function public.sotigram_owner_set_role(uuid,text) from public, anon;
grant execute on function public.sotigram_owner_set_role(uuid,text) to authenticated;

create or replace function public.sotigram_owner_remove_role(target_user uuid, old_role text)
returns void language plpgsql security definer
set search_path = pg_catalog, public, app_private
as $$
begin
  if auth.uid() is null or not app_private.is_sotigram_owner() then raise exception 'owner_only'; end if;
  if old_role not in ('admin','moderator') then raise exception 'invalid_role'; end if;
  delete from public.user_roles where user_id=target_user and role=old_role;
end;
$$;
revoke all on function public.sotigram_owner_remove_role(uuid,text) from public, anon;
grant execute on function public.sotigram_owner_remove_role(uuid,text) to authenticated;

create or replace function public.sotigram_owner_update_report(target_report uuid, new_status text)
returns void language plpgsql security definer
set search_path = pg_catalog, public, app_private
as $$
begin
  if auth.uid() is null or not app_private.is_sotigram_owner() then raise exception 'owner_only'; end if;
  if new_status not in ('reviewing','resolved','rejected') then raise exception 'invalid_status'; end if;
  update public.reports set status=new_status where id=target_report;
  if not found then raise exception 'report_not_found'; end if;
end;
$$;
revoke all on function public.sotigram_owner_update_report(uuid,text) from public, anon;
grant execute on function public.sotigram_owner_update_report(uuid,text) to authenticated;

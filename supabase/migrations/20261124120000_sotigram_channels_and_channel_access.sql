-- Sotigram public channels with server-checked membership and publisher permissions.
create table if not exists public.sotigram_channels (
  conversation_id uuid primary key references public.conversations(id) on delete cascade,
  handle text unique,
  is_public boolean not null default true,
  created_at timestamptz not null default now()
);
alter table public.sotigram_channels enable row level security;
drop policy if exists "members read channel metadata" on public.sotigram_channels;
create policy "members read channel metadata" on public.sotigram_channels
for select to authenticated using (app_private.is_conversation_member(conversation_id));

create or replace function public.sotigram_create_channel(channel_title text, channel_description text default '')
returns uuid language plpgsql security definer
set search_path = pg_catalog, public, app_private
as $$
declare me uuid := auth.uid(); cid uuid; base_handle text;
begin
 if me is null then raise exception 'not_authenticated'; end if;
 if nullif(trim(channel_title),'') is null or char_length(trim(channel_title)) > 80 then raise exception 'invalid_channel_title'; end if;
 if char_length(coalesce(channel_description,'')) > 500 then raise exception 'description_too_long'; end if;
 insert into public.conversations(kind,title,description,created_by)
 values ('channel',trim(channel_title),trim(coalesce(channel_description,'')),me) returning id into cid;
 insert into public.conversation_members(conversation_id,user_id,member_role) values(cid,me,'owner');
 base_handle := 'ch_' || replace(cid::text,'-','');
 insert into public.sotigram_channels(conversation_id,handle,is_public) values(cid,base_handle,true);
 return cid;
end $$;
revoke all on function public.sotigram_create_channel(text,text) from public, anon;
grant execute on function public.sotigram_create_channel(text,text) to authenticated;

create or replace function public.sotigram_join_channel(target_channel uuid)
returns void language plpgsql security definer
set search_path = pg_catalog, public, app_private
as $$
begin
 if auth.uid() is null then raise exception 'not_authenticated'; end if;
 if not exists(select 1 from public.conversations c join public.sotigram_channels sc on sc.conversation_id=c.id where c.id=target_channel and c.kind='channel' and sc.is_public) then raise exception 'channel_not_found'; end if;
 insert into public.conversation_members(conversation_id,user_id,member_role)
 values(target_channel,auth.uid(),'member') on conflict do nothing;
end $$;
revoke all on function public.sotigram_join_channel(uuid) from public, anon;
grant execute on function public.sotigram_join_channel(uuid) to authenticated;

create or replace function public.sotigram_discover_channels()
returns table(id uuid,title text,description text,subscribers bigint)
language sql stable security definer
set search_path = pg_catalog, public
as $$
 select c.id,c.title,c.description,count(cm.user_id)::bigint
 from public.conversations c join public.sotigram_channels sc on sc.conversation_id=c.id
 left join public.conversation_members cm on cm.conversation_id=c.id
 where c.kind='channel' and sc.is_public
 group by c.id,c.title,c.description
 order by c.created_at desc limit 100;
$$;
revoke all on function public.sotigram_discover_channels() from public, anon;
grant execute on function public.sotigram_discover_channels() to authenticated;

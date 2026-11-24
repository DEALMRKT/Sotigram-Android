-- Sotigram username availability and first-owner bootstrap.
-- Applied to Supabase project tryhiueaukgahtonattf.

create or replace function public.sotigram_username_available(_username text)
returns boolean
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  clean_username text := lower(btrim(coalesce(_username, '')));
begin
  if clean_username !~ '^[a-z0-9_]{3,32}$' then
    return false;
  end if;
  if exists (select 1 from public.profiles p where lower(p.username) = clean_username) then
    return false;
  end if;
  if clean_username = 'razrab' then
    return not exists (select 1 from auth.users);
  end if;
  return true;
end;
$$;

revoke all on function public.sotigram_username_available(text) from public;
grant execute on function public.sotigram_username_available(text) to anon, authenticated;

create or replace function app_private.handle_new_sotigram_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  chosen_username text;
  is_owner_bootstrap boolean := false;
begin
  chosen_username := coalesce(
    nullif(new.raw_user_meta_data ->> 'username', ''),
    split_part(new.email, '@', 1)
  );

  if lower(chosen_username) = 'razrab' then
    perform pg_advisory_xact_lock(hashtext('sotigram-first-owner-bootstrap'));
    is_owner_bootstrap := (select count(*) = 1 from auth.users);
    if not is_owner_bootstrap then
      raise exception 'This username is reserved.';
    end if;
  end if;

  insert into public.profiles (id, username, display_name, bio, collection_number)
  values (
    new.id,
    chosen_username,
    coalesce(nullif(new.raw_user_meta_data ->> 'display_name', ''), chosen_username),
    '',
    case when is_owner_bootstrap then '+888888888888' else null end
  )
  on conflict (id) do nothing;

  insert into public.sotigram_wallets (user_id, balance)
  values (new.id, 0)
  on conflict (user_id) do nothing;

  if is_owner_bootstrap then
    insert into public.user_roles (user_id, role)
    values (new.id, 'owner')
    on conflict do nothing;
  end if;
  return new;
end;
$$;

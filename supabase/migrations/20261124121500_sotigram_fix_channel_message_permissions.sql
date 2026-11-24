drop policy if exists "members send messages as themselves" on public.messages;
create policy "members send messages as themselves"
on public.messages for insert to authenticated
with check (
 sender_id = (select auth.uid())
 and app_private.is_conversation_member(messages.conversation_id)
 and (
   not exists(select 1 from public.conversations c where c.id=messages.conversation_id and c.kind='channel')
   or exists(
     select 1 from public.conversation_members cm
     where cm.conversation_id=messages.conversation_id
       and cm.user_id=auth.uid()
       and cm.member_role in ('owner','admin','editor')
   )
 )
);
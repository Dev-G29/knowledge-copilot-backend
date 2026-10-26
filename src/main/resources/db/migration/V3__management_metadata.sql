-- Conversation titles and activity time for the sidebar.
alter table conversations add column title varchar(120);
alter table conversations add column updated_at timestamp(6) with time zone;

update conversations c
set title = left(first_message.query, 120)
from (
    select distinct on (conversation_id) conversation_id, query
    from chat_messages
    order by conversation_id, timestamp
) first_message
where first_message.conversation_id = c.id;

update conversations c
set updated_at = coalesce(
        (select max(m.timestamp) from chat_messages m where m.conversation_id = c.id),
        c.created_at);

alter table conversations alter column updated_at set not null;
create index if not exists idx_conversations_user_updated on conversations (user_id, updated_at desc);

-- Keep citations and confidence with each answer so reloaded history still shows them.
alter table chat_messages add column sources jsonb;
alter table chat_messages add column confidence double precision;

-- Document metadata for the library page.
alter table documents add column content_type varchar(100);
alter table documents add column size_bytes bigint;

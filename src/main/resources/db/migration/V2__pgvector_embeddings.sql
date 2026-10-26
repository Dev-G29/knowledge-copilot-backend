-- Move embeddings from JSON text into a native pgvector column so similarity search
-- runs inside Postgres instead of loading every chunk into the JVM.

create extension if not exists vector;

alter table document_chunks add column embedding vector(3072);

-- Existing embeddings are JSON arrays like "[0.1, 0.2, ...]", which pgvector parses directly.
update document_chunks
set embedding = embedding_vector::vector
where embedding_vector is not null
  and embedding_vector like '[%'
  and json_array_length(embedding_vector::json) = 3072;

alter table document_chunks drop column embedding_vector;

-- HNSW on plain vector is capped at 2000 dimensions; halfvec supports up to 4000.
-- Queries must use the same expression (embedding::halfvec(3072)) to hit this index.
create index if not exists idx_document_chunks_embedding_hnsw
    on document_chunks using hnsw ((embedding::halfvec(3072)) halfvec_cosine_ops);

create index if not exists idx_document_chunks_document_id on document_chunks (document_id);
create index if not exists idx_chat_messages_conversation_id on chat_messages (conversation_id, timestamp);
create index if not exists idx_conversations_user_id on conversations (user_id);

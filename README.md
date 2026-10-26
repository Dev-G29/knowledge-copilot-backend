# Knowledge Copilot: backend

Spring Boot API for Knowledge Copilot, a retrieval-augmented generation (RAG) assistant. Admins upload PDF, DOCX or TXT files. Users ask questions, and Gemini answers using only the most relevant passages, citing its sources.

**Stack:** Java 21 · Spring Boot 3.3 · Spring AI 1.1 (Google Gemini) · PostgreSQL 16 + pgvector · Flyway · JWT

## How it works

1. **Ingest**: PDFBox and Apache POI extract text, which is split into ~800-character passages with overlap.
2. **Embed**: each passage is embedded with `gemini-embedding-001` (3072 dimensions, task type `RETRIEVAL_DOCUMENT`) and stored in a `vector(3072)` column.
3. **Retrieve**: the question is embedded (`RETRIEVAL_QUERY`) and Postgres finds the nearest passages through an HNSW index on `halfvec`. Results are filtered by score and balanced across documents.
4. **Generate**: `gemini-2.5-flash` answers from those passages plus recent turns. Answers come back whole (`/chat/query`) or streamed (`/chat/stream`).

## API

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/auth/register` | public | Create a user, returns a JWT |
| POST | `/auth/login` | public | Email or username + password, returns a JWT |
| GET | `/auth/me` | user | Current user's profile |
| POST | `/chat/query` | user | Ask a question, `{query, conversationId?}` |
| POST | `/chat/stream` | user | Same, as `text/event-stream`: `meta` → `token`* → `done` \| `error` |
| GET | `/chat/conversations` | user | Your recent conversations |
| GET | `/chat/conversations/{id}` | owner | Messages with sources and confidence |
| PATCH | `/chat/conversations/{id}` | owner | Rename, `{title}` |
| DELETE | `/chat/conversations/{id}` | owner | Delete a conversation |
| GET | `/documents` | user | Indexed documents with chunk counts |
| POST | `/documents/upload` | admin | Multipart `file` (PDF, DOCX, TXT; up to 50 MB) |
| DELETE | `/documents/{id}` | admin | Remove a document and its embeddings |
| GET | `/actuator/health` | public | Liveness/readiness |

Errors use one JSON shape: `{timestamp, status, error, message, path, details[]}`. Chat requests are rate-limited per user, and login/register per IP (HTTP 429).

## Run locally

Requires **JDK 21**. The build uses Lombok, which doesn't support newer JDKs yet.

**No API key needed (UI development):**

```bash
./mvnw spring-boot:test-run
```

This starts the API on `:8080` with:
- a throwaway pgvector container (Docker or Colima must be running);
- a demo AI that streams answers built from the retrieved passages.

Admin login: `admin@example.com` / `admin12345`.

**With Gemini:**

```bash
docker run -d --name kc-db -p 5432:5432 -e POSTGRES_DB=knowledgeCopilot -e POSTGRES_PASSWORD=1234 pgvector/pgvector:pg16
export GEMINI_API_KEY=...            # https://aistudio.google.com/app/apikey
export ADMIN_PASSWORD=choose-one     # seeds admin@example.com on first start
./mvnw spring-boot:run
```

**Tests** run against a real pgvector database through Testcontainers, with Gemini replaced by deterministic fakes:

```bash
./mvnw test
```

They cover migrations (including converting the old Render schema), vector search, streaming, auth, ownership, CORS and rate limiting.

## Configuration

| Variable | Default | Notes |
|---|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | local Postgres | JDBC connection |
| `GEMINI_API_KEY` | — | Required for chat and embeddings |
| `GEMINI_CHAT_MODEL` | `gemini-2.5-flash` | |
| `JWT_SECRET` | dev-only value | **Required** with `SPRING_PROFILES_ACTIVE=prod` (e.g. `openssl rand -base64 48`) |
| `JWT_EXPIRATION_MINUTES` | `1440` | |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:3000` | Comma-separated, wildcards allowed |
| `ADMIN_EMAIL` / `ADMIN_USERNAME` / `ADMIN_PASSWORD` | — | Admin is seeded only when a password is set |
| `ADMIN_SYNC_PASSWORD` | `false` | `true` resets an existing admin's password on startup |
| `RATE_LIMIT_CHAT_PER_MINUTE` | `20` | Per user |
| `RAG_MIN_RELEVANCE_SCORE` | `0.25` | Minimum cosine similarity for a passage to be used |

Schema changes go through Flyway (`src/main/resources/db/migration`). Hibernate only validates the schema.

## Deployment

The production stack is Caddy + backend + pgvector on an Oracle Cloud Always Free ARM VM, with nightly backups and GitHub Actions CD. It costs $0, has no cold starts, and no database expiry. Step-by-step guide: **[deploy/README.md](deploy/README.md)**.

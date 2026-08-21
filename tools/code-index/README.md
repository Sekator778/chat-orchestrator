# Code Vector Index (Phase 0, artifact 4)

Semantic search over the whole repository, backed by [Qdrant](https://qdrant.tech/).
This is the fourth Phase 0 artifact from the MultiAgent dev guide, section 6.9:
a vector database an agent can query *before* writing code, so it knows what is
already implemented and avoids duplication and conflicts.

The first three Phase 0 artifacts are documents and live in [`memory-bank/`](../../memory-bank/)
(`architecture.md` = module map, `current-state.md` = status map,
`decisions.md` = decision records).

## Why this design

- **Local embeddings (FastEmbed).** Embeddings are computed in-process with an
  ONNX model (`BAAI/bge-small-en-v1.5`). No API key, no third-party calls, no
  cost; code never leaves the machine.
- **Docker only.** Qdrant and the indexer both run in containers. No local
  Python or Qdrant install is required.
- **Idempotent.** Chunk IDs are deterministic, so re-running the indexer
  updates chunks in place instead of creating duplicates.

## Usage

From this directory (`tools/code-index/`):

```bash
# 1. Start the vector database (long-lived service).
docker compose up -d qdrant

# 2. Build / refresh the index (one-shot; re-run after large code changes).
docker compose --profile tools run --rm indexer

# 3. Search.
docker compose run --rm indexer python search.py "kafka consumer retry backoff"
docker compose run --rm indexer python search.py --limit 10 "persona resolution"
```

Qdrant dashboard: <http://localhost:6333/dashboard>

## How agents use it

Before implementing a task, a developer agent searches the index for the
feature it is about to build. If `search.py` returns existing matches, the
agent reads those files instead of writing a new module. This complements the
`memory-bank/` documents: the documents give the high-level map, the index
gives line-level recall across the entire codebase.

For an agent driven by Claude Code, native file search (grep/glob + reading
files) already covers most recall; this index additionally serves
budget-tier or non-Claude agents that lack agentic file traversal.

## Configuration

Defaults are set in `docker-compose.yml` and can be overridden via environment
variables:

| Variable | Default | Purpose |
|----------|---------|---------|
| `QDRANT_URL` | `http://qdrant:6333` | Qdrant endpoint |
| `REPO_PATH` | `/repo` | Repository root inside the container |
| `COLLECTION` | `telegram_userbot_code` | Qdrant collection name |
| `EMBED_MODEL` | `BAAI/bge-small-en-v1.5` | FastEmbed model |

## Generated data (never committed)

`qdrant_storage/` (vector data) and `fastembed_cache/` (downloaded model) are
created next to this file and are gitignored.

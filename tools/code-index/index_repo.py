"""Phase 0 - index the repository into Qdrant for semantic code search.

Walks the repository, splits source files into overlapping line windows,
embeds each window locally with FastEmbed (no API key needed) and upserts
the vectors into a Qdrant collection. Point IDs are deterministic, so
re-running updates chunks in place instead of duplicating them.

Run inside Docker (see docker-compose.yml):

    docker compose --profile tools run --rm indexer
"""

import hashlib  # noqa: F401  (kept for callers that prefer hash-based ids)
import os
import sys
import time
import uuid
from pathlib import Path

from qdrant_client import QdrantClient

REPO = Path(os.environ.get("REPO_PATH", "/repo"))
QDRANT_URL = os.environ.get("QDRANT_URL", "http://qdrant:6333")
COLLECTION = os.environ.get("COLLECTION", "telegram_userbot_code")
EMBED_MODEL = os.environ.get("EMBED_MODEL", "BAAI/bge-small-en-v1.5")

INCLUDE_EXT = {
    ".java", ".py", ".kt", ".xml", ".yml", ".yaml", ".sql", ".md",
    ".properties", ".gradle", ".ts", ".js", ".sh", ".ps1", ".txt",
}
EXCLUDE_DIRS = {
    ".git", ".idea", ".cache", ".mvn", "target", "build", "out", "dist",
    "node_modules", "logs", "temp", ".tmp", "tdlib_db", "tdlib_files",
    "tdlib-files", "tdlib-sessions", "tdlib-db", "qdrant_storage",
    "fastembed_cache", "media", "media-dev", "test-media",
}
CHUNK_LINES = 80
OVERLAP = 15
MAX_FILE_BYTES = 400_000
BATCH = 128

_NS = uuid.NAMESPACE_URL


def iter_files(root):
    """Yield indexable source files, pruning excluded directories."""
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIRS]
        for name in filenames:
            path = Path(dirpath) / name
            if path.suffix.lower() not in INCLUDE_EXT:
                continue
            try:
                if path.stat().st_size > MAX_FILE_BYTES:
                    continue
            except OSError:
                continue
            yield path


def chunk(lines):
    """Yield (start_line, end_line, text) overlapping line windows."""
    n = len(lines)
    if n == 0:
        return
    step = CHUNK_LINES - OVERLAP
    i = 0
    while i < n:
        end = min(i + CHUNK_LINES, n)
        yield i + 1, end, "".join(lines[i:end])
        if end == n:
            return
        i += step


def stable_id(rel, start, end):
    """Deterministic UUID for a chunk, so re-indexing is idempotent."""
    return str(uuid.uuid5(_NS, f"{rel}:{start}-{end}"))


def connect():
    """Return a Qdrant client once the server is reachable."""
    client = QdrantClient(url=QDRANT_URL)
    for _ in range(30):
        try:
            client.get_collections()
            return client
        except Exception:  # noqa: BLE001 - retry any startup error
            time.sleep(2)
    raise SystemExit(f"Qdrant not reachable at {QDRANT_URL}")


def main():
    if not REPO.is_dir():
        print(f"ERROR: repo path not found: {REPO}", file=sys.stderr)
        sys.exit(1)

    client = connect()
    client.set_model(EMBED_MODEL)

    documents, metadata, ids = [], [], []
    files = 0
    total_chunks = 0

    def flush():
        if not documents:
            return
        client.add(
            collection_name=COLLECTION,
            documents=documents,
            metadata=metadata,
            ids=ids,
        )
        documents.clear()
        metadata.clear()
        ids.clear()

    for path in iter_files(REPO):
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        rel = path.relative_to(REPO).as_posix()
        lang = path.suffix.lstrip(".").lower()
        produced = False
        for start, end, body in chunk(text.splitlines(keepends=True)):
            if not body.strip():
                continue
            documents.append(body)
            metadata.append({
                "path": rel,
                "start_line": start,
                "end_line": end,
                "language": lang,
            })
            ids.append(stable_id(rel, start, end))
            total_chunks += 1
            produced = True
            if len(documents) >= BATCH:
                flush()
        if produced:
            files += 1

    flush()
    print(f"Indexed {total_chunks} chunks from {files} files "
          f"into collection '{COLLECTION}'.")


if __name__ == "__main__":
    main()

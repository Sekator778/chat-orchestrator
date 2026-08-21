"""Phase 0 - semantic search over the indexed repository.

Usage (inside Docker):

    docker compose run --rm indexer python search.py "kafka retry backoff"
    docker compose run --rm indexer python search.py --limit 10 "persona resolution"

Prints the best-matching code locations as `score  path:start-end  [lang]`.
"""

import os
import sys
import time

from qdrant_client import QdrantClient

QDRANT_URL = os.environ.get("QDRANT_URL", "http://qdrant:6333")
COLLECTION = os.environ.get("COLLECTION", "telegram_userbot_code")
EMBED_MODEL = os.environ.get("EMBED_MODEL", "BAAI/bge-small-en-v1.5")


def connect():
    client = QdrantClient(url=QDRANT_URL)
    for _ in range(30):
        try:
            client.get_collections()
            return client
        except Exception:  # noqa: BLE001 - retry any startup error
            time.sleep(2)
    raise SystemExit(f"Qdrant not reachable at {QDRANT_URL}")


def main(argv):
    limit = 8
    args = list(argv)
    if len(args) >= 2 and args[0] == "--limit":
        limit = int(args[1])
        args = args[2:]
    query = " ".join(args).strip()
    if not query:
        print('Usage: python search.py [--limit N] "search query"',
              file=sys.stderr)
        sys.exit(2)

    client = connect()
    client.set_model(EMBED_MODEL)
    hits = client.query(
        collection_name=COLLECTION, query_text=query, limit=limit)

    if not hits:
        print("No results. Has the index been built? Run: "
              "docker compose --profile tools run --rm indexer")
        return
    for hit in hits:
        meta = hit.metadata
        print(f"{hit.score:.3f}  {meta['path']}:"
              f"{meta['start_line']}-{meta['end_line']}  [{meta['language']}]")


if __name__ == "__main__":
    main(sys.argv[1:])

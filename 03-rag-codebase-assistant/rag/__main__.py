"""Command line: python -m rag ingest <path> | python -m rag ask "<question>" | python -m rag serve"""

import argparse
import json
import logging
import sys

from .config import get_settings
from .models import AskRequest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="rag", description="Codebase RAG assistant")
    sub = parser.add_subparsers(dest="command", required=True)

    p_ingest = sub.add_parser("ingest", help="index a folder (relative to INGEST_ROOT)")
    p_ingest.add_argument("path", nargs="?", default=".")

    p_ask = sub.add_parser("ask", help="ask a question from the terminal")
    p_ask.add_argument("question")
    p_ask.add_argument("-k", "--top-k", type=int)
    p_ask.add_argument("-l", "--language", action="append", help="filter, repeatable: java, python, markdown, pdf")
    p_ask.add_argument("-p", "--project", help="filter by top-level folder")
    p_ask.add_argument("--mode", choices=["hybrid", "vector", "keyword"], default="hybrid")
    p_ask.add_argument("--json", action="store_true", help="print the raw response")

    p_serve = sub.add_parser("serve", help="run the API + chat UI")
    p_serve.add_argument("--host", default="127.0.0.1")
    p_serve.add_argument("--port", type=int, default=8000)

    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    logging.getLogger("httpx").setLevel(logging.WARNING)

    if args.command == "serve":
        import uvicorn
        uvicorn.run("rag.api:app", host=args.host, port=args.port)
        return 0

    from .service import RagService
    service = RagService(get_settings())

    if args.command == "ingest":
        result = service.ingest(args.path)
        print(json.dumps(result.model_dump(), indent=2))
        return 0

    response = service.ask(AskRequest(question=args.question, top_k=args.top_k, languages=args.language,
                                      project=args.project, mode=args.mode))
    if args.json:
        print(response.model_dump_json(indent=2))
        return 0
    print(response.answer)
    if response.warning:
        print(f"\n(warning: {response.warning})", file=sys.stderr)
    print("\nSources:")
    for s in response.sources:
        mark = "*" if s.cited else " "
        print(f" {mark}[{s.id}] {s.location}  (score {s.score})")
    return 0


if __name__ == "__main__":
    sys.exit(main())

"""Retrieval evaluation: does the right file show up in the top k, and how high?

Indexes projects 01 and 02 into a separate data dir (so this project's own docs, which mention
the same identifiers, can't leak into the results) and reports hit@k and MRR per search mode.

    python eval/run_eval.py            # real embedding model
    python eval/run_eval.py -k 6
"""

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from rag.config import Settings  # noqa: E402
from rag.generator import ExtractiveGenerator  # noqa: E402
from rag.service import RagService  # noqa: E402

PROJECTS = ["01-HFT-matching-engine", "02-distributed-order-inventory-system"]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("-k", type=int, default=4)
    parser.add_argument("--data-dir", default=str(ROOT / "data" / "eval"))
    parser.add_argument("-v", "--verbose", action="store_true", help="print misses")
    args = parser.parse_args()

    settings = Settings(data_dir=Path(args.data_dir), ingest_root=ROOT.parent, llm_provider="none",
                        redis_url=None, _env_file=None)
    service = RagService(settings, generator=ExtractiveGenerator())
    for project in PROJECTS:
        service.ingest(project)

    questions = [json.loads(line) for line in (ROOT / "eval" / "questions.jsonl").read_text().splitlines() if line]
    print(f"{len(questions)} questions, {service.store.count()} chunks indexed, k={args.k}\n")
    print(f"{'mode':<9} {'hit@k':>7} {'MRR':>6}   {'concept':>8} {'identifier':>11}")

    for mode in ("vector", "keyword", "hybrid"):
        hits, rr, by_kind = 0, 0.0, {}
        for item in questions:
            results = service.retriever.search(item["q"], args.k, mode)
            rank = next((i for i, r in enumerate(results, start=1)
                         if any(r.chunk.path.endswith(e) for e in item["expect"])), None)
            hit = rank is not None
            hits += hit
            rr += 1 / rank if rank else 0
            k = by_kind.setdefault(item["kind"], [0, 0])
            k[0] += hit
            k[1] += 1
            if args.verbose and not hit:
                print(f"  miss [{mode}] {item['q']}  ->  {[r.chunk.path.split('/')[-1] for r in results]}")
        n = len(questions)
        kinds = {kind: f"{v[0]}/{v[1]}" for kind, v in by_kind.items()}
        print(f"{mode:<9} {hits / n:>7.0%} {rr / n:>6.2f}   {kinds.get('concept', '-'):>8} {kinds.get('identifier', '-'):>11}")


if __name__ == "__main__":
    main()

# AI-Powered Codebase & Documentation Assistant (RAG)

Ask questions about a codebase in plain English and get answers that cite the exact files
and line ranges they came from. The service indexes Java, Python, Markdown and PDF files
(plus JS/TS, SQL, YAML, Lua, ...). It searches them with **hybrid retrieval** (pgvector
semantic search + Lucene BM25), and has an LLM answer **only from what it found**.

Built with Java 21, Spring Boot 3.5, PostgreSQL + pgvector, Apache Lucene, local ONNX
embeddings, Redis and the Claude API (official Anthropic Java SDK).

![Chat UI](docs/chat.png)

## Results at a glance

**Retrieval quality** (`mvn test -Peval`): 30 questions about the two other internship
projects (`eval/questions.jsonl`), each with a known correct file. 17 are conceptual
("How does checkout prevent overselling?") and 13 are code-name lookups
("Where is `previousResult` defined?", "`DEAD_LETTER_QUEUE`").

| Index | Retrieval mode | hit@4 | MRR | Concept MRR | Identifier MRR |
|---|---|---|---|---|---|
| Projects 1 + 2 | **hybrid** | **93%** | 0.79 | 0.67 | **0.95** |
| Projects 1 + 2 | vector only | 93% | 0.81 | 0.70 | 0.94 |
| Projects 1 + 2 | keyword only | 83% | 0.70 | 0.50 | 0.95 |
| + this project (noisy) | **hybrid** | **93%** | **0.77** | 0.64 | **0.95** |
| + this project (noisy) | vector only | 90% | 0.72 | 0.67 | 0.79 |
| + this project (noisy) | keyword only | 80% | 0.67 | 0.45 | 0.95 |

On a clean codebase, vector search with path-aware chunks is already strong, and hybrid
matches it. The difference shows up in the **noisy** run, where this project is indexed
too and its tests and UI *mention* the same identifiers. Vector search alone then loses
code-name lookups (identifier MRR 0.94 → 0.79; it returns test files instead of the class
that defines `previousResult`). Hybrid stays at 0.95. Keyword search alone is weak on
conceptual questions. That robustness is why hybrid is the default.

**Other measured behaviour:**

| Check | Result |
|---|---|
| Indexing projects 1 + 2 (96 files → 247 chunks, CPU only) | ~12 s; re-runs skip unchanged files (16 files checked in < 0.1 s) |
| Retrieval per question (warm) | ~30 ms |
| Repeated or re-phrased question | served from Redis, no LLM call |
| 35 questions in a burst from one client | 30 answered, the rest get `429` + `Retry-After` |
| `/ingest` without the admin token / with `../../etc` | `401` / `400` |
| Automated tests | 36 passing (unit + integration against real Postgres/pgvector) |

## How a question is answered

```mermaid
flowchart LR
    Q[Question + filters] --> C{Redis cache}
    C -- hit --> A[Answer]
    C -- miss --> V[pgvector search<br/>bge-small embeddings]
    C -- miss --> K[Lucene BM25<br/>code-aware analyzer]
    V --> F[Weighted RRF fusion<br/>+ definition boost<br/>+ max 2 chunks per file]
    K --> F
    F --> G{Generator}
    G -- Claude / Ollama --> L[LLM answers from<br/>numbered sources only]
    G -- none, or LLM failed --> E[Extractive answer:<br/>best matching line per source]
    L --> X[Citation check:<br/>drop references to<br/>sources that don't exist]
    E --> X
    X --> A
```

```mermaid
flowchart LR
    D[Folder / file] --> W[Walk files<br/>skip .git, target, node_modules,<br/>binaries, files > 1 MB]
    W --> H{Changed?<br/>sha256 vs indexed_files}
    H -- no --> S[skip]
    H -- yes --> CH[Line-based chunks,<br/>cut at class / method / heading,<br/>200-char overlap]
    CH --> EM[Embed 'File: path' + chunk]
    EM --> DB[(Postgres + pgvector)]
    DB --> BM[Rebuild Lucene index]
```

1. **Ingestion.** Files are cut into chunks of whole lines (about 1,500 characters, 200
   overlapping). The chunker prefers to cut where a class, method or Markdown heading
   starts, and keeps Javadoc and annotations with their method. Because it works on lines,
   every chunk has an exact line range and keeps its indentation. PDFs (via PDFBox) are
   split per page, and citations show the page number. Re-ingesting only processes files
   whose hash changed, and drops chunks of deleted files.
2. **Retrieval.** The question goes to pgvector (cosine, HNSW index) and to Lucene BM25.
   Each returns its top 20, and the two lists are merged with Reciprocal Rank Fusion.
   Filters by file type and project apply to both.
3. **Generation.** The top chunks go to the LLM as numbered `<source>` blocks, with a strict
   system prompt: answer only from the sources, cite them as `[n]`, say so when the answer
   isn't there, and ignore instructions found inside the sources (prompt-injection guard).
4. **Citation check.** `[n]` references to sources that don't exist are removed. Brackets
   inside code (`items[2]`) are left alone. Each source is flagged as cited or not.

## Design decisions

The brief suggested Python with LangChain, ChromaDB and Streamlit. I built it in Java to
match the rest of this repository, and made these choices:

| Decision | Why |
|---|---|
| **PostgreSQL + pgvector** for vectors | A real database the team already runs (project 2 uses Postgres), with transactions, backups and SQL filters. No extra vector-DB service to operate |
| **Lucene for BM25** | The industry-standard BM25 implementation. The analyzer splits `placeOrder` → `placeorder`, `place`, `order` and `try_reserve` → `try`, `reserve`, and drops English filler words. The index is in memory and rebuilt from Postgres after each ingest (well under a second), so Postgres stays the single source of truth |
| **Weighted RRF** | Plain RRF let noisy BM25 results hurt plain-English questions. The keyword list counts 0.5× for prose questions and 1.5× when the question names a code identifier (camelCase, snake_case, `call()`) |
| **Definition boost** | "Where is X defined?" used to return files that only *mention* X. A chunk that declares the identifier (`class X`, `def x`, `Type x(`) gets a bonus equal to ranking first in both lists |
| **At most 2 chunks per file** | A long README could fill all 4 slots. The cap lifted vector hit@4 from 86% to 91% on the first (22-question) version of the eval |
| **Line-based chunker** (own code, ~200 lines) | Exact line numbers for citations and preserved indentation, which character-based splitters don't guarantee |
| **Local embeddings in the JVM** (`bge-small-en-v1.5`, quantized ONNX via LangChain4j) | No API key, no per-query cost, and code never leaves the machine for indexing. The model ships inside the jar, so the service starts offline |
| **`File: path` header on every embedded chunk** | A method body alone often doesn't say which class or module it belongs to. The path does |
| **Three answer modes** | Claude when `ANTHROPIC_API_KEY` is set, Ollama for a free local model, otherwise *extractive* answers (the best matching line of each source). If the LLM call fails, the answer falls back to extractive mode with a warning instead of an error |
| **Cache key includes the index version** | Every ingest that changes something bumps the version, so cached answers never refer to stale code |
| **Platform threads, not virtual threads** | Embedding runs in native ONNX code and the HTTP clients lock internally. On Java 21 both pin virtual threads, the same problem that deadlocked project 2 under load |

## Running it

### With Docker (recommended)

```bash
cd 03-rag-codebase-assistant
mvn -DskipTests package                 # builds target/rag-assistant.jar
export ANTHROPIC_API_KEY=sk-ant-...     # optional - without it you get extractive answers
docker compose up -d --build            # Postgres + pgvector, Redis, the app
```

Open **http://localhost:8000**. On first start it indexes projects 1 and 2 of this repository
in the background (about 15 s). To index another codebase:

```bash
REPO_PATH=/path/to/any/repo INGEST_ON_START=. docker compose up -d
```

### Locally

Needs Java 21, Maven, and a Postgres with the pgvector extension, for example
`docker run -p 5432:5432 -e POSTGRES_DB=rag -e POSTGRES_USER=rag -e POSTGRES_PASSWORD=rag pgvector/pgvector:pg16`.

```bash
INGEST_ON_START=01-HFT-matching-engine mvn spring-boot:run
```

### Choosing the LLM

| Setting | What answers |
|---|---|
| `ANTHROPIC_API_KEY=...` | Claude via the official Java SDK (`claude-opus-5-5` by default; `CLAUDE_MODEL` / `CLAUDE_EFFORT` to change). Requests opt into server-side refusal fallback (`fallbacksDefault()`) |
| `OLLAMA_URL=http://localhost:11434` (+ `OLLAMA_MODEL`) | any model running in [Ollama](https://ollama.com), free and fully local |
| neither | extractive answers: the best matching line of each source, with citations |

`LLM_PROVIDER=claude|ollama|none` forces a choice. The default, `auto`, picks in the
order above.

## API

| Method | Path | Notes |
|---|---|---|
| POST | `/ask` | `{question, top_k?, languages?, project?, mode?}` (`mode`: hybrid / vector / keyword) → answer, sources, generator, cached, timings. Rate limited per client IP |
| POST | `/ingest` | `{path}` relative to `INGEST_ROOT`. Needs header `X-Admin-Token`; disabled when `ADMIN_TOKEN` is unset. Paths outside the root (including via symlinks) are refused |
| GET | `/stats` | files, chunks, chunks per language and project, generator, cache backend |
| GET | `/health` | liveness |
| GET | `/` | chat UI |

```bash
curl -s localhost:8000/ask -H 'Content-Type: application/json' \
  -d '{"question": "Where is reserveAndEnqueue defined?", "top_k": 2}'
```

Real response from the running container, no LLM configured (snippet shortened):

```json
{
  "answer": "No language model is configured, so here are the most relevant passages:\n\n- `CheckoutService.java:111-133`: private CheckoutResponse reserveAndEnqueue(AuthUser user, String key, String fingerprint, UUID orderId, [1]\n- `StockReservationService.java:34-64`: private final RedisScript<List<Long>> reserveScript; [2]",
  "sources": [
    {
      "id": 1,
      "path": "02-distributed-order-inventory-system/order-service/src/main/java/com/zaalime/orders/service/checkout/CheckoutService.java",
      "location": "02-distributed-order-inventory-system/order-service/src/main/java/com/zaalime/orders/service/checkout/CheckoutService.java:111-133",
      "language": "java",
      "start_line": 111,
      "end_line": 133,
      "score": 0.073,
      "snippet": "                .filter(e -> e.state() == IdempotencyStore.State.ACCEPTED) ...",
      "cited": true
    }
  ],
  "generator": "extractive",
  "cached": false,
  "warning": null,
  "timings_ms": {"retrieval": 33.6, "generation": 1.2, "total": 34.9}
}
```

With `ANTHROPIC_API_KEY` set, `answer` is Claude's written explanation with `[n]` citations
and `generator` is `claude:claude-opus-5-5`.

## Tests and evaluation

```bash
mvn test            # 36 tests; Docker must be running (Testcontainers starts pgvector)
mvn test -Peval     # retrieval eval with the real embedding model (+ -Deval.noisy=true)
```

| Test class | Covers |
|---|---|
| `CodeChunkerTest` | chunk text equals the reported line range exactly (indentation included), full coverage, cuts at declarations, Javadoc stays with its method, Markdown headings, PDF page numbers |
| `AnalyzerAndIdentifiersTest` | Lucene analyzer splits camelCase / snake_case and drops stopwords; identifier detection; declaration vs mention |
| `FusionTest` | RRF maths and weights |
| `RetrievalAndIngestTest` | (pgvector) identifier lookup, each search mode, filters, definition beats mentions, unchanged files skipped, changed file re-indexed with old chunks gone, deleted file removed, path traversal refused |
| `ApiTest` | (pgvector) citations checked, re-phrased question served from cache, cache invalidated by re-ingest, LLM failure degrades to extractive, filters, no match skips the LLM, admin token, validation, UI and stats |
| `AnswerTest` | citation cleanup ignores code, prompt format, extractive line choice, Claude request (model, effort, fallbacks, beta header, no thinking/sampling params) |
| `RateLimiterTest` | budget, retry-after, per-client buckets |

Integration tests use a deterministic hashing embedder and a scripted LLM, so they're fast
and repeatable. The eval uses the real model.

## Configuration

| Variable | Default | |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/rag`, `rag`, `rag` | Postgres with pgvector |
| `INGEST_ROOT` | `..` | only paths below this can be indexed |
| `INGEST_ON_START` | – | comma-separated paths to index at startup |
| `CHUNK_SIZE`, `CHUNK_OVERLAP` | `1500`, `200` | characters |
| `DEFAULT_TOP_K`, `CANDIDATE_POOL` | `4`, `20` | sources per answer, candidates per retriever |
| `ANTHROPIC_API_KEY`, `CLAUDE_MODEL`, `CLAUDE_EFFORT` | –, `claude-opus-5-5`, `medium` | |
| `OLLAMA_URL`, `OLLAMA_MODEL` | –, `llama3.1` | |
| `REDIS_URL` | – | answer cache; in-memory LRU when unset |
| `CACHE_TTL` | `1h` | |
| `RATE_LIMIT_PER_MINUTE` | `30` | per client IP on `/ask` |
| `ADMIN_TOKEN` | – | enables `POST /ingest` |

## Project structure

```
03-rag-codebase-assistant/
├── src/main/java/com/zaalime/rag/
│   ├── ingest/    SourceFiles (walk + read, PDFBox), CodeChunker (line-based), Chunk
│   ├── embed/     Embedder, BgeEmbedder (in-process ONNX)
│   ├── store/     ChunkStore (pgvector, indexed files, index version)
│   ├── search/    CodeAnalyzer + KeywordIndex (Lucene BM25), Identifiers, HybridRetriever (RRF)
│   ├── answer/    ClaudeGenerator, OllamaGenerator, ExtractiveGenerator, Prompts, Citations
│   ├── service/   IngestService, AskService, AnswerCache, DTOs
│   ├── web/       RagController, RateLimiter, ApiErrors
│   └── config/    RagConfig (wiring, LLM choice), RagProperties
├── src/main/resources/
│   ├── db/migration/V1__init.sql    pgvector schema + HNSW index
│   └── static/                      chat UI
├── src/test/java/...                tests + RetrievalEval
├── eval/questions.jsonl
├── Dockerfile
└── docker-compose.yml
```

## Known limitations

- **Not tested against the live Claude API here.** No API key was available while building
  this. The Claude integration is covered by a unit test of the exact request it builds
  (model, effort, refusal fallback, beta header) and by tests of how answers, refusals and
  truncation are handled. The live run should be checked once a key is set.
- **Answers are not streamed.** The UI waits for the full answer, which can take a few
  seconds with an LLM. Streaming over SSE would be the next step.
- **The Lucene index and rate limiter are in-process.** That's fine for one instance.
  Several instances would each rebuild Lucene from Postgres (consistent), but the rate
  limit would apply per instance.
- **The jar is large (~240 MB)** because ONNX Runtime ships native libraries for every
  platform, plus the embedding model.
- **The eval set is small (30 questions)** and was written alongside the tuning. Treat the
  numbers as a sanity check, not a benchmark.

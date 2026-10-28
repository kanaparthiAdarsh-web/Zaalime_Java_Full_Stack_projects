import hmac
import logging
import threading
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from .config import Settings, get_settings
from .models import AskRequest, AskResponse, IngestRequest, IngestResult
from .ratelimit import RateLimiter
from .service import IngestPathError, RagService

log = logging.getLogger(__name__)
STATIC = Path(__file__).parent / "static"


def create_app(service: RagService | None = None, settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        # loading the embedding model takes a few seconds, do it once at startup
        app.state.rag = service or RagService(settings)
        if settings.ingest_on_start:
            def initial_ingest():
                for path in (p.strip() for p in settings.ingest_on_start.split(",") if p.strip()):
                    try:
                        log.info("Initial ingest of '%s': %s", path, app.state.rag.ingest(path).model_dump())
                    except Exception:
                        log.exception("Initial ingest of '%s' failed", path)
            threading.Thread(target=initial_ingest, name="initial-ingest", daemon=True).start()
        yield

    app = FastAPI(title="Codebase RAG Assistant", version="1.0.0", lifespan=lifespan)
    limiter = RateLimiter(settings.rate_limit_per_minute)

    def rag(request: Request) -> RagService:
        return request.app.state.rag

    def require_admin(x_admin_token: str | None = Header(default=None)) -> None:
        if not settings.admin_token:
            raise HTTPException(403, "Ingestion over HTTP is disabled. Set ADMIN_TOKEN or use the CLI.")
        if not x_admin_token or not hmac.compare_digest(x_admin_token, settings.admin_token):
            raise HTTPException(401, "Missing or wrong X-Admin-Token header")

    @app.post("/ask", response_model=AskResponse)
    async def ask(req: AskRequest, request: Request, svc: RagService = Depends(rag)) -> AskResponse:
        client = request.client.host if request.client else "unknown"
        allowed, retry_after = limiter.allow(client)
        if not allowed:
            raise HTTPException(429, "Too many questions, slow down a little",
                                headers={"Retry-After": str(retry_after)})
        # retrieval and the LLM call are blocking; keep the event loop free
        return await run_in_threadpool(svc.ask, req)

    @app.post("/ingest", response_model=IngestResult, dependencies=[Depends(require_admin)])
    async def ingest(req: IngestRequest, svc: RagService = Depends(rag)) -> IngestResult:
        try:
            return await run_in_threadpool(svc.ingest, req.path)
        except IngestPathError as e:
            raise HTTPException(400, str(e)) from e

    @app.get("/stats")
    async def stats(svc: RagService = Depends(rag)) -> dict:
        return {**svc.store.stats(), "generator": svc.generator.name, "embedding_model": svc.embedder.name,
                "cache": svc.cache.backend}

    @app.get("/health")
    async def health() -> dict:
        return {"status": "ok"}

    @app.get("/", include_in_schema=False)
    async def index() -> FileResponse:
        return FileResponse(STATIC / "index.html")

    app.mount("/static", StaticFiles(directory=STATIC), name="static")
    return app


app = create_app()

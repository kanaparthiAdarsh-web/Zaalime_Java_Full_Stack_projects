import textwrap
from pathlib import Path

import pytest

from rag.config import Settings
from rag.embeddings import HashingEmbedder
from rag.generator import ExtractiveGenerator
from rag.service import RagService

ORDER_SERVICE = textwrap.dedent('''\
    package shop;

    /** Places orders and reserves stock so nothing is oversold. */
    public class OrderService {

        private final StockReservation reservations;

        public OrderService(StockReservation reservations) {
            this.reservations = reservations;
        }

        public Order placeOrder(long productId, int quantity) {
            if (!reservations.tryReserve(productId, quantity)) {
                throw new OutOfStockException(productId);
            }
            return new Order(productId, quantity);
        }
    }
    ''')

RATE_LIMITER = textwrap.dedent('''\
    import time


    class TokenBucket:
        """Limits how many requests a client may send per second."""

        def __init__(self, rate: float, capacity: int):
            self.rate = rate
            self.capacity = capacity
            self.tokens = capacity
            self.updated = time.monotonic()

        def allow(self) -> bool:
            now = time.monotonic()
            self.tokens = min(self.capacity, self.tokens + (now - self.updated) * self.rate)
            self.updated = now
            if self.tokens >= 1:
                self.tokens -= 1
                return True
            return False
    ''')

README = textwrap.dedent('''\
    # Shop

    ## Deployment

    Run `docker compose up` and open the dashboard on port 8080.

    ## Payments

    Payments are captured only after the order has been confirmed by the warehouse.
    ''')


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    root = tmp_path / "repo"
    (root / "shop" / "src").mkdir(parents=True)
    (root / "shop" / "src" / "OrderService.java").write_text(ORDER_SERVICE)
    (root / "shop" / "README.md").write_text(README)
    (root / "tools").mkdir()
    (root / "tools" / "rate_limiter.py").write_text(RATE_LIMITER)
    (root / "shop" / "target").mkdir()
    (root / "shop" / "target" / "Ignored.java").write_text("class Ignored {}")  # build output, skipped
    return root


@pytest.fixture
def settings(tmp_path: Path, repo: Path) -> Settings:
    return Settings(data_dir=tmp_path / "data", ingest_root=repo, llm_provider="none", redis_url=None,
                    admin_token="secret-token", rate_limit_per_minute=1000, chunk_size=400, chunk_overlap=50,
                    _env_file=None)


@pytest.fixture
def service(settings: Settings) -> RagService:
    svc = RagService(settings, embedder=HashingEmbedder(), generator=ExtractiveGenerator())
    svc.ingest(".")
    return svc

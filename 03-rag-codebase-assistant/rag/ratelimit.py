"""Per-client token bucket. Every /ask can cost an LLM call, so it is limited per IP."""

import threading
import time


class RateLimiter:

    def __init__(self, per_minute: int):
        self._rate = per_minute / 60.0
        self._capacity = float(per_minute)
        self._buckets: dict[str, tuple[float, float]] = {}  # client -> (tokens, last refill)
        self._lock = threading.Lock()

    def allow(self, client: str) -> tuple[bool, int]:
        """Returns (allowed, seconds until the next token if not)."""
        if self._capacity <= 0:
            return True, 0
        now = time.monotonic()
        with self._lock:
            if len(self._buckets) > 10_000:
                self._prune(now)
            tokens, last = self._buckets.get(client, (self._capacity, now))
            tokens = min(self._capacity, tokens + (now - last) * self._rate)
            if tokens >= 1:
                self._buckets[client] = (tokens - 1, now)
                return True, 0
            self._buckets[client] = (tokens, now)
            return False, max(1, int((1 - tokens) / self._rate) + 1)

    def _prune(self, now: float) -> None:
        # a bucket idle long enough to be full again carries no state worth keeping
        full_after = self._capacity / self._rate
        for client, (_, last) in list(self._buckets.items()):
            if now - last > full_after:
                del self._buckets[client]

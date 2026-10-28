"""Answer cache. Redis when REDIS_URL is set (shared across instances), otherwise in-process LRU."""

import hashlib
import json
import logging
import threading
import time
from collections import OrderedDict

log = logging.getLogger(__name__)


def cache_key(question: str, **params) -> str:
    normalized = " ".join(question.lower().split())
    payload = json.dumps({"q": normalized, **params}, sort_keys=True, default=str)
    return "rag:answer:" + hashlib.sha256(payload.encode()).hexdigest()


class AnswerCache:

    def __init__(self, redis_url: str | None, ttl_seconds: int, max_local_entries: int = 1000):
        self._ttl = ttl_seconds
        self._redis = None
        if redis_url:
            import redis
            self._redis = redis.Redis.from_url(redis_url, socket_timeout=0.5, socket_connect_timeout=0.5)
        self._local: OrderedDict[str, tuple[float, str]] = OrderedDict()
        self._max_local = max_local_entries
        self._lock = threading.Lock()

    @property
    def backend(self) -> str:
        return "redis" if self._redis is not None else "memory"

    def get(self, key: str) -> dict | None:
        if self._redis is not None:
            try:
                raw = self._redis.get(key)
                return json.loads(raw) if raw else None
            except Exception as e:  # a cache outage must never break answering
                log.warning("Cache read failed: %s", e)
                return None
        with self._lock:
            hit = self._local.get(key)
            if not hit:
                return None
            expires, raw = hit
            if expires < time.monotonic():
                del self._local[key]
                return None
            self._local.move_to_end(key)
            return json.loads(raw)

    def put(self, key: str, value: dict) -> None:
        raw = json.dumps(value)
        if self._redis is not None:
            try:
                self._redis.set(key, raw, ex=self._ttl)
            except Exception as e:
                log.warning("Cache write failed: %s", e)
            return
        with self._lock:
            self._local[key] = (time.monotonic() + self._ttl, raw)
            self._local.move_to_end(key)
            while len(self._local) > self._max_local:
                self._local.popitem(last=False)

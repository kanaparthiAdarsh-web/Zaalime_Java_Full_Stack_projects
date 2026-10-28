FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    MODEL_CACHE_DIR=/opt/models

WORKDIR /app
COPY requirements.txt .
RUN pip install -r requirements.txt

# bake the embedding model into the image so the container starts without downloading anything
RUN python -c "from fastembed import TextEmbedding; TextEmbedding('BAAI/bge-small-en-v1.5', cache_dir='/opt/models')"

COPY rag ./rag

RUN useradd --create-home app && mkdir -p /app/data && chown -R app /app/data /opt/models
USER app

EXPOSE 8000
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')"
CMD ["uvicorn", "rag.api:app", "--host", "0.0.0.0", "--port", "8000"]

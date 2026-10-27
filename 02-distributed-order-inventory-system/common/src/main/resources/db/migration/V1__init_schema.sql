CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE products (
    id          BIGSERIAL PRIMARY KEY,
    sku         VARCHAR(40)    NOT NULL UNIQUE,
    name        VARCHAR(120)   NOT NULL,
    description VARCHAR(500),
    price       NUMERIC(12, 2) NOT NULL CHECK (price > 0),
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- the CHECK is the last line of defence against overselling:
-- even if Redis and Postgres ever drift, the database refuses negative stock
CREATE TABLE inventory (
    product_id BIGINT PRIMARY KEY REFERENCES products (id) ON DELETE CASCADE,
    quantity   INT         NOT NULL CHECK (quantity >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE orders (
    id              UUID PRIMARY KEY,
    user_id         BIGINT         NOT NULL REFERENCES users (id),
    idempotency_key VARCHAR(100)   NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    total_amount    NUMERIC(14, 2) NOT NULL,
    failure_reason  VARCHAR(255),
    created_at      TIMESTAMPTZ    NOT NULL,
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_orders_user_idem UNIQUE (user_id, idempotency_key)
);

CREATE INDEX idx_orders_user_created ON orders (user_id, created_at DESC);

CREATE TABLE order_items (
    id           BIGSERIAL PRIMARY KEY,
    order_id     UUID           NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id   BIGINT         NOT NULL REFERENCES products (id),
    product_name VARCHAR(120)   NOT NULL,
    quantity     INT            NOT NULL CHECK (quantity > 0),
    unit_price   NUMERIC(12, 2) NOT NULL
);

CREATE INDEX idx_order_items_order ON order_items (order_id);

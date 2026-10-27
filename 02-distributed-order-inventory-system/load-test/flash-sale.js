// Flash sale: 1000 virtual users hit checkout at the same moment for a product
// that only has STOCK units. Afterwards we check nothing was oversold.
//
//   k6 run load-test/flash-sale.js
//   k6 run -e BASE_URL=http://localhost:8080 -e STOCK=500 -e BUYERS=1000 load-test/flash-sale.js

import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const STOCK = Number(__ENV.STOCK || 500);
const BUYERS = Number(__ENV.BUYERS || 1000);
const USERS = Number(__ENV.USERS || 50);
// p95 budget for checkout; the default assumes everything (k6 included) runs on one laptop
const P95_MS = Number(__ENV.P95_MS || 5000);
const ADMIN_USER = __ENV.ADMIN_USER || 'admin';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'admin12345';

const accepted = new Counter('orders_accepted');
const soldOut = new Counter('orders_sold_out');
const unexpected = new Counter('orders_unexpected');
const checkoutLatency = new Trend('checkout_latency', true);

http.setResponseCallback(http.expectedStatuses(200, 201, 202, 409));

export const options = {
    scenarios: {
        flash_sale: {
            executor: 'per-vu-iterations',
            vus: BUYERS,
            iterations: 1,
            maxDuration: '2m',
        },
    },
    setupTimeout: '120s',
    teardownTimeout: '90s',
    thresholds: {
        orders_unexpected: ['count==0'],
        checks: ['rate==1.0'],
        checkout_latency: [`p(95)<${P95_MS}`],
    },
};


function post(path, body, token, extraHeaders = {}) {
    const headers = { 'Content-Type': 'application/json', ...extraHeaders };
    if (token) headers.Authorization = `Bearer ${token}`;
    return http.post(`${BASE_URL}${path}`, JSON.stringify(body), { headers });
}

// auth is rate limited at the gateway (429), and the stack may still be warming up (0 / 5xx)
function withRetry(fn) {
    for (let i = 0; i < 30; i++) {
        const res = fn();
        if (res.status !== 429 && res.status !== 0 && res.status < 500) return res;
        sleep(1);
    }
    fail('gave up after retries');
}

export function setup() {
    const login = withRetry(() => post('/api/auth/login', { username: ADMIN_USER, password: ADMIN_PASSWORD }));
    if (login.status !== 200) fail(`admin login failed: ${login.status} ${login.body}`);
    const adminToken = login.json('token');

    const sku = `FLASH-${Date.now()}`;
    const product = post('/api/products',
        { sku, name: 'Load test flash item', price: '99.00', initialStock: STOCK }, adminToken);
    if (product.status !== 201) fail(`create product failed: ${product.status} ${product.body}`);

    const tokens = [];
    const runId = Date.now();
    for (let i = 0; i < USERS; i++) {
        const res = withRetry(() => post('/api/auth/register', { username: `lt_${runId}_${i}`, password: 'password123' }));
        if (res.status !== 201) fail(`register failed: ${res.status} ${res.body}`);
        tokens.push(res.json('token'));
    }
    return { productId: product.json('id'), tokens, adminToken };
}

export default function (data) {
    const token = data.tokens[exec.vu.idInTest % data.tokens.length];
    const res = post('/api/orders',
        { items: [{ productId: data.productId, quantity: 1 }] },
        token,
        { 'Idempotency-Key': `${exec.vu.idInTest}-${exec.scenario.iterationInTest}-${Date.now()}` });

    checkoutLatency.add(res.timings.duration);
    if (res.status === 202) accepted.add(1);
    else if (res.status === 409) soldOut.add(1);
    else unexpected.add(1);

    check(res, { 'accepted or sold out': (r) => r.status === 202 || r.status === 409 });
}

export function teardown(data) {
    // give the worker a moment to drain the queue
    let product;
    for (let i = 0; i < 30; i++) {
        product = http.get(`${BASE_URL}/api/products/${data.productId}`).json();
        if (product.committedStock === product.availableStock) break;
        sleep(1);
    }
    console.log(`final stock -> available (Redis): ${product.availableStock}, committed (Postgres): ${product.committedStock}`);
    check(product, {
        'no oversell in Redis': (p) => p.availableStock >= 0,
        'no oversell in Postgres': (p) => p.committedStock >= 0,
        'Redis and Postgres agree': (p) => p.availableStock === p.committedStock,
        'everything sold when demand > stock': (p) => BUYERS < STOCK || p.availableStock === 0,
    });
}

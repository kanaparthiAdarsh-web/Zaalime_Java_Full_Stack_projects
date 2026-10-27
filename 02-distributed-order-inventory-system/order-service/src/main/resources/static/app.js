(() => {
    'use strict';

    const $ = (id) => document.getElementById(id);
    const MAX_LOG_ROWS = 200;

    const state = {
        session: loadSession(),
        products: new Map(),   // id -> product
        orders: new Map(),     // id -> order (only mine)
        counters: { PENDING: 0, COMPLETED: 0, FAILED: 0 },
        eventsThisSecond: 0,
    };

    // ---------- session ----------

    function loadSession() {
        try {
            const raw = localStorage.getItem('session');
            if (!raw) return null;
            const s = JSON.parse(raw);
            return new Date(s.expiresAt) > new Date() ? s : null;
        } catch {
            return null;
        }
    }

    function saveSession(s) {
        state.session = s;
        try {
            if (s) localStorage.setItem('session', JSON.stringify(s));
            else localStorage.removeItem('session');
        } catch { /* private mode - session just won't survive a reload */ }
        renderSession();
    }

    // ---------- api ----------

    async function api(path, { method = 'GET', body, idempotencyKey } = {}) {
        const headers = { 'Content-Type': 'application/json' };
        if (state.session) headers.Authorization = 'Bearer ' + state.session.token;
        if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;

        const res = await fetch(path, { method, headers, body: body ? JSON.stringify(body) : undefined });
        if (res.status === 401 && state.session) {
            saveSession(null);
            throw new Error('Session expired, please log in again');
        }
        const text = await res.text();
        const data = text ? JSON.parse(text) : null;
        if (!res.ok) {
            const msg = (data && (data.detail || data.title)) || res.statusText || ('HTTP ' + res.status);
            const err = new Error(msg);
            err.status = res.status;
            throw err;
        }
        return data;
    }

    // ---------- rendering helpers ----------

    function el(tag, attrs = {}, ...children) {
        const node = document.createElement(tag);
        for (const [k, v] of Object.entries(attrs)) {
            if (k === 'class') node.className = v;
            else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
            else node.setAttribute(k, v);
        }
        for (const c of children) {
            if (c == null) continue;
            node.append(c instanceof Node ? c : document.createTextNode(String(c)));
        }
        return node;
    }

    const money = (v) => v == null ? '-' : '$' + Number(v).toFixed(2);
    const time = (iso) => new Date(iso).toLocaleTimeString();
    const shortId = (id) => String(id).slice(0, 8);

    function setMsg(node, text, ok) {
        node.textContent = text || '';
        node.className = 'msg ' + (text ? (ok ? 'ok' : 'bad') : '');
    }

    // ---------- session UI ----------

    function renderSession() {
        const s = state.session;
        $('auth-panel').classList.toggle('hidden', !!s);
        $('user-box').classList.toggle('hidden', !s);
        $('admin-panel').classList.toggle('hidden', !(s && s.role === 'ADMIN'));
        if (s) {
            $('user-name').textContent = s.username;
            $('user-role').textContent = s.role.toLowerCase();
            loadCart();
            loadOrders();
        } else {
            $('cart').replaceChildren(el('p', { class: 'muted' }, 'Sign in to use the cart.'));
            $('cart-total').textContent = '';
            $('checkout-btn').disabled = true;
            state.orders.clear();
            renderOrders();
        }
        renderProducts();
    }

    async function authenticate(mode) {
        const username = $('auth-username').value.trim();
        const password = $('auth-password').value;
        $('auth-error').textContent = '';
        try {
            const res = await api('/api/auth/' + mode, { method: 'POST', body: { username, password } });
            saveSession(res);
            $('auth-password').value = '';
        } catch (e) {
            $('auth-error').textContent = e.message;
        }
    }

    // ---------- products ----------

    async function loadProducts() {
        try {
            const list = await api('/api/products');
            state.products = new Map(list.map((p) => [p.id, p]));
            renderProducts();
        } catch (e) {
            $('products').replaceChildren(el('p', { class: 'error' }, 'Could not load products: ' + e.message));
        }
    }

    function stockClass(n) {
        return 'stock' + (n <= 0 ? ' out' : n <= 5 ? ' low' : '');
    }

    function renderProducts() {
        const signedIn = !!state.session;
        const isAdmin = signedIn && state.session.role === 'ADMIN';
        const cards = [...state.products.values()].map((p) => {
            const qty = el('input', { type: 'number', min: '1', max: '10', value: '1', 'aria-label': 'Quantity' });
            const card = el('div', { class: 'product', 'data-id': p.id },
                el('span', { class: 'sku' }, p.sku),
                el('h3', {}, p.name),
                el('span', { class: 'price' }, money(p.price)),
                el('div', { class: 'stock-line' },
                    el('span', {}, 'Available'),
                    el('span', { class: stockClass(p.availableStock), 'data-role': 'available' }, p.availableStock)),
                el('div', { class: 'stock-line' },
                    el('span', {}, 'Committed (DB)'),
                    el('span', { 'data-role': 'committed' }, p.committedStock ?? '-')),
            );
            if (signedIn) {
                const disabled = p.availableStock <= 0;
                const add = el('button', { class: 'btn btn-ghost', onclick: () => addToCart(p.id, +qty.value) }, 'Add');
                const buy = el('button', { class: 'btn', onclick: () => buyNow(p.id, +qty.value, buy) }, 'Buy now');
                if (disabled) { add.disabled = true; buy.disabled = true; }
                card.append(el('div', { class: 'actions' }, qty, add, buy));
            }
            if (isAdmin) {
                const amount = el('input', { type: 'number', min: '1', value: '10', 'aria-label': 'Restock amount' });
                card.append(el('div', { class: 'restock' }, amount,
                    el('button', { class: 'btn btn-ghost btn-small', onclick: () => restock(p.id, +amount.value) }, 'Restock')));
            }
            return card;
        });
        $('products').replaceChildren(...(cards.length ? cards : [el('p', { class: 'muted' }, 'No products yet.')]));
    }

    function updateStock(evt) {
        const p = state.products.get(evt.productId);
        if (!p) {
            loadProducts(); // new product
            return;
        }
        p.availableStock = evt.availableStock;
        if (evt.committedStock != null) p.committedStock = evt.committedStock;

        const card = document.querySelector(`.product[data-id="${evt.productId}"]`);
        if (!card) return;
        const avail = card.querySelector('[data-role="available"]');
        avail.textContent = p.availableStock;
        avail.className = stockClass(p.availableStock) + ' flash';
        requestAnimationFrame(() => requestAnimationFrame(() => avail.classList.remove('flash')));
        card.querySelector('[data-role="committed"]').textContent = p.committedStock ?? '-';
        card.querySelectorAll('.actions .btn').forEach((b) => { b.disabled = p.availableStock <= 0; });
    }

    async function restock(productId, quantity) {
        try {
            await api(`/api/products/${productId}/restock`, { method: 'POST', body: { quantity } });
            setMsg($('admin-msg'), 'Restocked', true);
        } catch (e) {
            setMsg($('admin-msg'), e.message, false);
        }
    }

    // ---------- cart & checkout ----------

    async function loadCart() {
        try {
            renderCart(await api('/api/cart'));
        } catch { /* shown on next action */ }
    }

    function renderCart(cart) {
        const rows = cart.items.map((i) => el('div', { class: 'cart-item' },
            el('span', {}, `${i.quantity} × ${i.productName}`),
            el('span', {}, money(i.lineTotal), ' ',
                el('button', { title: 'Remove', onclick: () => removeFromCart(i.productId) }, '✕'))));
        $('cart').replaceChildren(...(rows.length ? rows : [el('p', { class: 'muted' }, 'Cart is empty.')]));
        $('cart-total').textContent = rows.length ? 'Total ' + money(cart.total) : '';
        $('checkout-btn').disabled = rows.length === 0;
    }

    async function addToCart(productId, quantity) {
        try {
            const current = (await api('/api/cart')).items.find((i) => i.productId === productId);
            const next = Math.min((current ? current.quantity : 0) + quantity, 10);
            renderCart(await api(`/api/cart/items/${productId}`, { method: 'PUT', body: { quantity: next } }));
            setMsg($('checkout-msg'), '');
        } catch (e) {
            setMsg($('checkout-msg'), e.message, false);
        }
    }

    async function removeFromCart(productId) {
        try {
            renderCart(await api(`/api/cart/items/${productId}`, { method: 'DELETE' }));
        } catch (e) {
            setMsg($('checkout-msg'), e.message, false);
        }
    }

    // One key per *click*. A retry of the same click (network blip) reuses it,
    // so the server can tell a retry from a second purchase.
    async function submitWithRetry(send) {
        const key = crypto.randomUUID();
        for (let attempt = 1; ; attempt++) {
            try {
                return await send(key);
            } catch (e) {
                const retriable = e.status === undefined || e.status === 503 || e.status === 429;
                if (!retriable || attempt === 3) throw e;
                await new Promise((r) => setTimeout(r, 300 * attempt));
            }
        }
    }

    async function checkoutCart() {
        const btn = $('checkout-btn');
        btn.disabled = true;
        try {
            const res = await submitWithRetry((key) => api('/api/cart/checkout', { method: 'POST', idempotencyKey: key }));
            setMsg($('checkout-msg'), `Order ${shortId(res.orderId)} queued`, true);
            trackOrder(res);
            loadCart();
        } catch (e) {
            setMsg($('checkout-msg'), e.message, false);
            btn.disabled = false;
        }
    }

    async function buyNow(productId, quantity, btn) {
        btn.disabled = true;
        try {
            const res = await submitWithRetry((key) => api('/api/orders', {
                method: 'POST', idempotencyKey: key, body: { items: [{ productId, quantity }] },
            }));
            setMsg($('checkout-msg'), `Order ${shortId(res.orderId)} queued`, true);
            trackOrder(res);
        } catch (e) {
            setMsg($('checkout-msg'), e.message, false);
        } finally {
            btn.disabled = (state.products.get(productId)?.availableStock ?? 0) <= 0;
        }
    }

    function trackOrder(res) {
        if (!state.orders.has(res.orderId)) {
            state.orders.set(res.orderId, {
                id: res.orderId, status: res.status, totalAmount: res.totalAmount,
                createdAt: new Date().toISOString(), items: [],
            });
        }
        renderOrders();
        // fill in the item list
        api('/api/orders/' + res.orderId).then((o) => { mergeOrder(o); renderOrders(); }).catch(() => {});
    }

    // ---------- orders ----------

    const STATUS_RANK = { PENDING: 0, PROCESSING: 1, COMPLETED: 2, FAILED: 2 };

    function mergeOrder(o) {
        const known = state.orders.get(o.id);
        // websocket events can arrive before the HTTP response - never move a status backwards
        if (known && STATUS_RANK[known.status] > STATUS_RANK[o.status]) o.status = known.status;
        state.orders.set(o.id, { ...known, ...o });
    }

    async function loadOrders() {
        try {
            const page = await api('/api/orders?size=20');
            page.content.forEach(mergeOrder);
            renderOrders();
        } catch { /* ignore */ }
    }

    function renderOrders() {
        const list = [...state.orders.values()]
            .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
            .slice(0, 20);
        const rows = list.map((o) => el('tr', {},
            el('td', {}, el('code', { title: o.id }, shortId(o.id))),
            el('td', {}, (o.items || []).map((i) => `${i.quantity}× ${i.productName}`).join(', ') || '…'),
            el('td', {}, money(o.totalAmount)),
            el('td', {}, el('span', { class: 'pill s-' + o.status, title: o.failureReason || '' }, o.status)),
            el('td', {}, time(o.createdAt))));
        $('orders').replaceChildren(...(rows.length ? rows
            : [el('tr', {}, el('td', { colspan: '5', class: 'muted' }, 'No orders yet.'))]));
    }

    // ---------- live feed ----------

    function log(kind, subject, detail, ts) {
        const tbody = $('log');
        tbody.prepend(el('tr', {},
            el('td', {}, time(ts)),
            el('td', {}, el('span', { class: 'pill s-' + kind }, kind)),
            el('td', {}, subject),
            el('td', { class: 'detail' }, detail)));
        while (tbody.rows.length > MAX_LOG_ROWS) tbody.deleteRow(-1);
    }

    function onOrderEvent(evt) {
        state.eventsThisSecond++;
        if (evt.status in state.counters) state.counters[evt.status]++;
        renderCounters();
        log(evt.status, `order ${shortId(evt.orderId)} · ${evt.username}`,
            `${evt.message || ''}${evt.totalAmount != null ? ' · ' + money(evt.totalAmount) : ''}`, evt.timestamp);

        if (state.session && evt.username === state.session.username) {
            const known = state.orders.get(evt.orderId);
            if (known) {
                mergeOrder({ ...known, status: evt.status, failureReason: evt.status === 'FAILED' ? evt.message : null });
                renderOrders();
            } else if (evt.status === 'PENDING') {
                // placed from another tab / device
                trackOrder({ orderId: evt.orderId, status: evt.status, totalAmount: evt.totalAmount });
            }
        }
    }

    function onInventoryEvent(evt) {
        state.eventsThisSecond++;
        updateStock(evt);
        const committed = evt.committedStock != null ? ` · committed ${evt.committedStock}` : '';
        log('INVENTORY', evt.productName, `${evt.reason.toLowerCase()} → available ${evt.availableStock}${committed}`, evt.timestamp);
    }

    function renderCounters() {
        // "queued" = accepted but not finished yet
        const inFlight = state.counters.PENDING - state.counters.COMPLETED - state.counters.FAILED;
        $('stat-pending').textContent = Math.max(inFlight, 0);
        $('stat-completed').textContent = state.counters.COMPLETED;
        $('stat-failed').textContent = state.counters.FAILED;
    }

    function connectLiveFeed() {
        if (!window.StompJs) {
            $('ws-status').textContent = '● live feed unavailable';
            return;
        }
        const scheme = location.protocol === 'https:' ? 'wss://' : 'ws://';
        const client = new StompJs.Client({
            brokerURL: scheme + location.host + '/ws',
            reconnectDelay: 3000,
            heartbeatIncoming: 10000,
            heartbeatOutgoing: 10000,
        });
        client.onConnect = () => {
            $('ws-status').textContent = '● live';
            $('ws-status').className = 'pill pill-on';
            client.subscribe('/topic/orders', (m) => onOrderEvent(JSON.parse(m.body)));
            client.subscribe('/topic/inventory', (m) => onInventoryEvent(JSON.parse(m.body)));
            loadProducts(); // resync anything missed while disconnected
        };
        client.onWebSocketClose = () => {
            $('ws-status').textContent = '● reconnecting';
            $('ws-status').className = 'pill pill-off';
        };
        client.activate();
    }

    // ---------- wiring ----------

    $('auth-form').addEventListener('submit', (e) => {
        e.preventDefault();
        authenticate(e.submitter?.dataset.mode || 'login');
    });
    $('logout-btn').addEventListener('click', () => saveSession(null));
    $('checkout-btn').addEventListener('click', checkoutCart);
    $('refresh-products').addEventListener('click', loadProducts);
    $('refresh-orders').addEventListener('click', loadOrders);
    $('clear-log').addEventListener('click', () => $('log').replaceChildren());
    $('create-form').addEventListener('submit', async (e) => {
        e.preventDefault();
        const f = new FormData(e.target);
        try {
            await api('/api/products', {
                method: 'POST',
                body: {
                    sku: f.get('sku'), name: f.get('name'),
                    price: f.get('price'), initialStock: Number(f.get('initialStock')),
                },
            });
            e.target.reset();
            setMsg($('admin-msg'), 'Product created', true);
            loadProducts();
        } catch (err) {
            setMsg($('admin-msg'), err.message, false);
        }
    });

    setInterval(() => {
        $('stat-rate').textContent = state.eventsThisSecond;
        state.eventsThisSecond = 0;
    }, 1000);

    renderSession();
    loadProducts();
    connectLiveFeed();
})();

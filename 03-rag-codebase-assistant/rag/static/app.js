(() => {
    'use strict';

    const $ = (id) => document.getElementById(id);
    let busy = false;

    // ---------- tiny, safe markdown: escape first, then add the few constructs answers use ----------

    function escapeHtml(s) {
        return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    }

    function renderInline(text) {
        // odd pieces are inline code and stay literal, so `items[2]` never becomes a citation
        return text.split(/`([^`\n]+)`/).map((piece, i) => i % 2 === 1
            ? `<code>${piece}</code>`
            : piece
                .replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
                .replace(/\[(\d{1,2})\]/g, '<button type="button" class="cite" data-cite="$1">$1</button>'))
            .join('');
    }

    function renderMarkdown(md) {
        const parts = escapeHtml(md).split(/```[\w+-]*\n?([\s\S]*?)(?:```|$)/);
        let html = '';
        parts.forEach((part, i) => {
            if (i % 2 === 1) {
                html += `<pre><code>${part.replace(/\n$/, '')}</code></pre>`;
                return;
            }
            const blocks = part.split(/\n{2,}/).map((b) => b.trim()).filter(Boolean);
            for (const block of blocks) {
                const lines = block.split('\n');
                if (lines.every((l) => /^\s*[-*] /.test(l))) {
                    html += '<ul>' + lines.map((l) => `<li>${renderInline(l.replace(/^\s*[-*] /, ''))}</li>`).join('') + '</ul>';
                } else {
                    html += `<p>${renderInline(lines.join('<br>'))}</p>`;
                }
            }
        });
        return html;
    }

    // ---------- DOM helpers ----------

    function el(tag, attrs = {}, ...children) {
        const node = document.createElement(tag);
        for (const [k, v] of Object.entries(attrs)) {
            if (k === 'class') node.className = v;
            else if (k === 'html') node.innerHTML = v;
            else node.setAttribute(k, v);
        }
        for (const c of children) if (c != null) node.append(c instanceof Node ? c : document.createTextNode(String(c)));
        return node;
    }

    function scrollDown() {
        const box = $('messages');
        box.scrollTop = box.scrollHeight;
    }

    // ---------- index info & filters ----------

    async function loadStats() {
        try {
            const stats = await (await fetch('/stats')).json();
            $('index-info').textContent = `${stats.files} files · ${stats.chunks} chunks`;
            $('generator-info').textContent = stats.generator === 'extractive'
                ? 'No LLM configured: answers show the best matching passages.'
                : `Answers by ${stats.generator}`;
            $('project').replaceChildren(el('option', { value: '' }, 'All projects'),
                ...Object.keys(stats.projects).sort().map((p) => el('option', { value: p }, p)));
            $('languages').replaceChildren(...Object.entries(stats.by_language)
                .sort((a, b) => b[1] - a[1])
                .map(([lang, n]) => el('label', {}, el('input', { type: 'checkbox', value: lang }), `${lang} (${n})`)));
        } catch {
            $('index-info').textContent = 'index unavailable';
        }
    }

    // ---------- asking ----------

    function renderAnswer(container, res) {
        const answer = el('div', { class: 'answer', html: renderMarkdown(res.answer) });
        const meta = el('div', { class: 'meta' },
            el('span', { class: 'pill' }, res.generator),
            res.cached ? el('span', { class: 'pill accent' }, 'cached') : null,
            res.timings_ms.total != null ? el('span', { class: 'pill' }, `${Math.round(res.timings_ms.total)} ms`) : null);

        const sources = el('div', { class: 'sources' }, ...res.sources.map((s) => {
            const d = el('details', { class: 'source' + (s.cited ? '' : ' uncited'), 'data-source': s.id },
                el('summary', {},
                    el('span', { class: 'cite' }, s.id),
                    el('span', { class: 'loc' }, s.location),
                    el('span', { class: 'pill' }, s.language),
                    s.cited ? el('span', { class: 'pill accent' }, 'cited') : null),
                el('div', { class: 'body' }, el('pre', {}, el('code', {}, s.snippet))));
            return d;
        }));

        container.replaceChildren(answer,
            res.warning ? el('div', { class: 'warning' }, res.warning) : null,
            meta,
            res.sources.length ? sources : null);

        // clicking [n] opens and highlights that source
        container.querySelectorAll('.answer .cite').forEach((btn) => btn.addEventListener('click', () => {
            const target = container.querySelector(`[data-source="${btn.dataset.cite}"]`);
            if (!target) return;
            target.open = true;
            target.classList.add('flash');
            target.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            setTimeout(() => target.classList.remove('flash'), 1200);
        }));
    }

    async function ask(question) {
        if (busy || !question.trim()) return;
        busy = true;
        $('send').disabled = true;
        $('empty')?.remove();

        $('messages').append(el('div', { class: 'msg user' }, question));
        const reply = el('div', { class: 'msg bot' }, el('p', { class: 'typing' }, 'Searching the codebase…'));
        $('messages').append(reply);
        scrollDown();

        const languages = [...document.querySelectorAll('#languages input:checked')].map((c) => c.value);
        const body = {
            question,
            mode: $('mode').value,
            top_k: Number($('topk').value),
            languages: languages.length ? languages : null,
            project: $('project').value || null,
        };

        try {
            const r = await fetch('/ask', {
                method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
            });
            const data = await r.json();
            if (!r.ok) {
                const detail = Array.isArray(data.detail) ? data.detail.map((d) => d.msg).join(', ') : data.detail;
                throw new Error(detail || r.statusText);
            }
            renderAnswer(reply, data);
        } catch (e) {
            reply.replaceChildren(el('div', { class: 'warning' }, 'Request failed: ' + e.message));
        } finally {
            busy = false;
            $('send').disabled = false;
            scrollDown();
        }
    }

    // ---------- wiring ----------

    $('ask-form').addEventListener('submit', (e) => {
        e.preventDefault();
        const q = $('question').value;
        $('question').value = '';
        ask(q);
    });
    $('question').addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            $('ask-form').requestSubmit();
        }
    });
    document.querySelectorAll('.suggestion').forEach((b) => b.addEventListener('click', () => ask(b.textContent)));
    $('topk').addEventListener('input', () => { $('topk-value').textContent = $('topk').value; });

    loadStats();
})();

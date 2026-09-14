// SPDX-License-Identifier: AGPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { observeRequests } from '../../runtime/android/request-observer.mjs';

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
test('Node activity observer tracks finish/save/disconnect without body or credential capture', async () => {
    let closed = false;
    const server = http.createServer(async (req, res) => {
        for await (const _ of req) { /* Fixture consumes its request, observer does not. */ }
        if (req.url.endsWith('hold')) {
            res.writeHead(200); res.write('one');
            const timer = setInterval(() => res.write('more'), 100);
            res.on('close', () => { clearInterval(timer); closed = true; });
        } else { res.writeHead(req.url.includes('failure') ? 500 : 200); res.end('fixture'); }
    });
    server.listen(0, '127.0.0.1'); await once(server, 'listening');
    const port = server.address().port;
    const snapshots = [];
    const observer = observeRequests({ port, publish: value => snapshots.push(value) });
    const call = async route => {
        const response = await fetch(`http://127.0.0.1:${port}${route}`, { method: 'POST', body: 'PRIVATE_PROMPT', headers: { authorization: 'SECRET_HEADER' } });
        await response.text(); return response;
    };
    try {
        assert.equal((await call('/api/backends/chat-completions/generate?ignored=PRIVATE_QUERY')).status, 200);
        assert.equal((await call('/api/chats/save')).status, 200);
        assert.equal((await call('/api/chats/group/save?failure')).status, 500);
        const controller = new AbortController();
        const response = await fetch(`http://127.0.0.1:${port}/api/backends/chat-completions/generate?hold`, { method: 'POST', signal: controller.signal });
        const reader = response.body.getReader(); await reader.read(); controller.abort(); await reader.cancel().catch(() => {});
        for (let i = 0; i < 50 && !closed; i++) await delay(20);
        assert(closed);
        await delay(20);
        const state = observer.snapshot();
        assert.equal(state.generationStarted, 2);
        assert.equal(state.generationFinished, 1);
        assert.equal(state.generationDisconnected, 1);
        assert.equal(state.saveFinished, 1);
        assert.equal(state.saveFailed, 1);
        assert.equal(state.activeGeneration, 0); assert.equal(state.activeSave, 0);
        assert(!JSON.stringify(snapshots).includes('PRIVATE'));
        assert(!JSON.stringify(snapshots).includes('SECRET_HEADER'));
    } finally { observer.dispose(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
});

test('diagnostic write failures never throw into the HTTP request path', async () => {
    const server = http.createServer((_, res) => res.end('ok'));
    server.listen(0, '127.0.0.1'); await once(server, 'listening');
    const port = server.address().port;
    const observer = observeRequests({ port, publish: () => { throw new Error('disk full'); } });
    try {
        const response = await fetch(`http://127.0.0.1:${port}/api/chats/save`, { method: 'POST' });
        assert.equal(await response.text(), 'ok'); assert(observer.snapshot().writeErrors > 0);
    } finally { observer.dispose(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
});

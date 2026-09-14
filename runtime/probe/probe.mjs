// SPDX-License-Identifier: AGPL-3.0-only
// Capability probe only. No SillyTavern configuration or generation logic here.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import http from 'node:http';
import https from 'node:https';
import dns from 'node:dns/promises';
import { once } from 'node:events';
import { Worker } from 'node:worker_threads';

const [reportPath, nonce, mode = 'android'] = process.argv.slice(2);
if (!reportPath || !/^[a-f0-9]{32}$/.test(nonce ?? '') || !['android', 'host'].includes(mode)) {
    throw new Error('Usage: probe.mjs <absolute-report.json> <32-hex-nonce> [android|host]');
}
assert(path.isAbsolute(reportPath));
const directory = path.dirname(reportPath);
await fsp.mkdir(directory, { recursive: true });
const report = {
    schemaVersion: 1, nonce, mode, completed: false, passed: false,
    node: process.versions.node, versions: process.versions,
    platform: process.platform, arch: process.arch, pid: process.pid,
    startedAt: new Date().toISOString(), tests: [],
    observations: { temporal: typeof globalThis.Temporal, execPath: process.execPath },
};
function save() {
    const temporary = `${reportPath}.tmp`;
    const fd = fs.openSync(temporary, 'w', 0o600);
    try { fs.writeFileSync(fd, JSON.stringify(report, null, 2)); fs.fsyncSync(fd); }
    finally { fs.closeSync(fd); }
    fs.renameSync(temporary, reportPath);
}
save();
// This limits a diagnostic test process only, never a production generation.
const watchdog = setTimeout(() => {
    report.tests.push({ name: 'probe-watchdog', passed: false, error: 'Diagnostic exceeded 120 seconds' });
    save();
    process.exit(1);
}, 120_000);
watchdog.unref();

async function check(name, action) {
    const started = performance.now();
    try {
        const detail = await action();
        report.tests.push({ name, passed: true, durationMs: performance.now() - started, detail });
    } catch (error) {
        report.tests.push({ name, passed: false, durationMs: performance.now() - started, error: String(error?.stack ?? error) });
    }
    save();
}
async function listen(server) {
    server.listen(0, '127.0.0.1');
    await once(server, 'listening');
    return server.address().port;
}
async function close(server) {
    server.closeAllConnections();
    await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
}
await check('runtime-identity', () => {
    assert.equal(Number(process.versions.node.split('.')[0]), 26);
    if (mode === 'android') {
        assert.equal(process.platform, 'android');
        assert.equal(process.arch, 'arm64');
    }
    return { napi: process.versions.napi, modules: process.versions.modules };
});
await check('esm-tla', async () => {
    const module = await import('data:text/javascript,export const answer = await Promise.resolve(42)');
    assert.equal(module.answer, 42);
});
await check('fs-atomic', async () => {
    const before = path.join(directory, 'fs-before');
    const after = path.join(directory, 'fs-after');
    const handle = await fsp.open(before, 'w', 0o600);
    try { await handle.writeFile('角色卡/聊天/设置'); await handle.sync(); }
    finally { await handle.close(); }
    await fsp.rename(before, after);
    assert.equal(await fsp.readFile(after, 'utf8'), '角色卡/聊天/设置');
    await fsp.unlink(after);
});
await check('crypto', () => {
    assert.notDeepEqual(crypto.randomBytes(32), crypto.randomBytes(32));
    assert.equal(crypto.scryptSync('password', 'salt', 16).toString('hex'), '745731af4484f323968969eda289aeee');
    assert.equal(crypto.createHash('sha256').update('abc').digest('hex'), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
});
await check('icu-unicode', () => {
    assert.equal('角色'.match(/\p{Letter}/gu).length, 2);
    assert.equal(/\p{Letter}/u.test('😀'), false);
    assert.deepEqual(Intl.DateTimeFormat.supportedLocalesOf(['en', 'zh', 'ja']), ['en', 'zh', 'ja']);
    assert(new Intl.Segmenter('zh', { granularity: 'word' }).segment('你好世界')[Symbol.iterator]().next().value);
    return { icu: process.versions.icu };
});
await check('wasm', async () => {
    const bytes = new Uint8Array([0,97,115,109,1,0,0,0,1,7,1,96,2,127,127,1,127,3,2,1,0,7,7,1,3,97,100,100,0,0,10,9,1,7,0,32,0,32,1,106,11]);
    const { instance } = await WebAssembly.instantiate(bytes);
    assert.equal(instance.exports.add(19, 23), 42);
});
await check('wasm-simd', async () => {
    const bytes = new Uint8Array([0,97,115,109,1,0,0,0,1,5,1,96,0,1,123,3,2,1,0,10,22,1,20,0,253,12,...Array(16).fill(0),11]);
    assert(WebAssembly.validate(bytes));
    await WebAssembly.instantiate(bytes);
});
await check('worker', async () => {
    const worker = new Worker(new URL('./worker.mjs', import.meta.url), { workerData: 21 });
    try {
        const [message] = await once(worker, 'message', { signal: AbortSignal.timeout(15_000) });
        assert.deepEqual(message, { answer: 42, platform: process.platform, arch: process.arch });
    } finally { await worker.terminate(); }
    return { availableParallelism: os.availableParallelism(), cpus: os.cpus().length };
});
await check('fetch-http-stream', async () => {
    const server = http.createServer(async (req, res) => {
        res.writeHead(200, { 'Content-Type': 'text/event-stream' });
        res.write('data: one\n\n');
        await new Promise(resolve => setTimeout(resolve, 50));
        res.end('data: two\n\n');
    });
    const port = await listen(server);
    try {
        const response = await fetch(`http://127.0.0.1:${port}/`, { signal: AbortSignal.timeout(10_000) });
        assert.equal(response.status, 200);
        let text = '';
        for await (const chunk of response.body) text += new TextDecoder().decode(chunk);
        assert.equal(text, 'data: one\n\ndata: two\n\n');
        assert.equal(await new Response(new Blob(['hello'])).text(), 'hello');
    } finally { await close(server); }
});
await check('dns', async () => {
    const address = await dns.lookup('nodejs.org');
    assert([4, 6].includes(address.family));
    return { family: address.family };
});
await check('tls-validation', async () => {
    const cert = await fsp.readFile(new URL('./fixtures/localhost-cert.pem', import.meta.url));
    const key = await fsp.readFile(new URL('./fixtures/localhost-key.pem', import.meta.url));
    const server = https.createServer({ cert, key }, (_, res) => res.end('trusted fixture'));
    const port = await listen(server);
    const request = options => new Promise((resolve, reject) => {
        const req = https.get({ hostname: '127.0.0.1', port, agent: false, timeout: 10_000, ...options }, res => {
            let body = '';
            res.on('data', chunk => { body += chunk; });
            res.on('end', () => resolve(body));
            res.on('error', reject);
        });
        req.on('timeout', () => req.destroy(new Error('fixture TLS timeout')));
        req.on('error', reject);
    });
    try {
        await assert.rejects(request({ servername: 'localhost' }), error => error.code === 'DEPTH_ZERO_SELF_SIGNED_CERT');
        assert.equal(await request({ ca: cert, servername: 'localhost' }), 'trusted fixture');
        await assert.rejects(request({ ca: cert }), error => error.code === 'ERR_TLS_CERT_ALTNAME_INVALID');
    } finally { await close(server); }
});
await check('https-remote', async () => {
    // Public Node endpoint, no user data/credentials. This is intentionally not skipped offline.
    const response = await fetch('https://nodejs.org/', { signal: AbortSignal.timeout(20_000) });
    try { assert(response.ok); } finally { await response.body?.cancel(); }
});
report.completed = true;
report.passed = report.tests.every(test => test.passed === true);
report.finishedAt = new Date().toISOString();
report.observations.memoryUsage = process.memoryUsage();
save();
clearTimeout(watchdog);
process.exitCode = report.passed ? 0 : 1;
console.log(`ST_RUNTIME_PROBE ${report.passed ? 'PASS' : 'FAIL'} ${reportPath}`);

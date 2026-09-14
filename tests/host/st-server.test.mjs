// SPDX-License-Identifier: AGPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import net from 'node:net';
import crypto from 'node:crypto';
import { once } from 'node:events';
import { spawn, spawnSync, execFileSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';

const root = fileURLToPath(new URL('../../', import.meta.url));
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
async function waitUntil(predicate, ms, message) {
    const deadline = Date.now() + ms;
    while (!await predicate()) {
        if (Date.now() > deadline) throw new Error(message);
        await delay(100);
    }
}
async function unusedPort() {
    const server = net.createServer();
    server.listen(0, '127.0.0.1');
    await once(server, 'listening');
    const port = server.address().port;
    await new Promise(resolve => server.close(resolve));
    return port;
}
async function fileHash(file) {
    const hash = crypto.createHash('sha256');
    for await (const block of fs.createReadStream(file)) hash.update(block);
    return hash.digest('hex');
}

// Requires prepared payload. Deliberately fails (never skips) if it is missing.
test('official ST payload: security, real WASM, persistence and remote streaming', { timeout: 180_000 }, async t => {
    const pointerPath = path.join(root, 'build/payload/current.json');
    assert(fs.existsSync(pointerPath), 'First run: python3 tools/payload/prepare.py');
    const pointer = JSON.parse(fs.readFileSync(pointerPath));
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'st-host-regression-'));
    const payloadRoot = path.join(directory, 'payload');
    let stateRoot = path.join(directory, 'state');
    const launchPath = path.join(directory, 'launch.json');
    const reportDir = path.join(root, 'build/reports');
    fs.mkdirSync(reportDir, { recursive: true });
    const logPath = path.join(reportDir, 'st-host.log');
    const log = fs.openSync(logPath, 'w', 0o600);
    const report = { schemaVersion: 1, scope: 'linux-host-st-only', payloadId: pointer.payloadId,
        node: process.versions.node, passed: false, tests: [] };
    let child, authorization, csrf, avatar, mock;
    const cookies = new Map();
    let origin, port;
    const check = async (name, action) => {
        await t.test(name, async () => {
            try { await action(); report.tests.push({ name, passed: true }); }
            catch (error) { report.tests.push({ name, passed: false, error: error.message }); throw error; }
        });
        if (!report.tests.at(-1)?.passed) throw new Error(`Host regression stopped at ${name}`);
    };
    const request = async (route, body, options = {}) => {
        const headers = { authorization, ...(cookies.size ? { cookie: [...cookies.values()].join('; ') } : {}),
            ...(csrf ? { 'X-CSRF-Token': csrf } : {}), ...options.headers };
        const init = { headers, signal: options.signal ?? AbortSignal.timeout(15_000) };
        if (body !== undefined) {
            init.method = 'POST';
            if (body instanceof FormData) init.body = body;
            else { headers['Content-Type'] = 'application/json'; init.body = JSON.stringify(body); }
        }
        const response = await fetch(origin + route, init);
        for (const cookie of response.headers.getSetCookie()) {
            const part = cookie.split(';')[0]; cookies.set(part.slice(0, part.indexOf('=')), part);
        }
        return response;
    };
    const json = async (route, body, status = 200) => {
        const response = await request(route, body);
        const text = await response.text();
        assert.equal(response.status, status, `${route}: ${text.slice(0, 600)}`);
        return JSON.parse(text);
    };
    const rejectedLaunch = (overrides, pattern) => {
        const descriptor = { ...JSON.parse(fs.readFileSync(launchPath)), ...overrides };
        const file = path.join(directory, `rejected-${crypto.randomUUID()}.json`);
        fs.writeFileSync(file, JSON.stringify(descriptor));
        const result = spawnSync(process.execPath, [path.join(payloadRoot, 'shell/bootstrap.mjs'), file],
            { cwd: directory, encoding: 'utf8', timeout: 30_000 });
        assert.equal(result.status, 1, 'Rejected launch must fail explicitly, not hang or succeed');
        assert.match(result.stderr, pattern);
    };
    const stop = async () => {
        if (!child) return;
        if (child.exitCode === null && child.signalCode === null) {
            const exited = once(child, 'exit');
            child.kill('SIGTERM');
            const deadline = setTimeout(() => child.kill('SIGKILL'), 10_000);
            try { await exited; } finally { clearTimeout(deadline); }
        }
        assert.equal(child.exitCode, 0, 'ST should shut down cleanly');
        child = undefined;
    };
    const start = async label => {
        const started = performance.now();
        child = spawn(process.execPath, [path.join(payloadRoot, 'shell/bootstrap.mjs'), launchPath], {
            cwd: directory, stdio: ['ignore', log, log],
            env: { ...process.env, SILLYTAVERN_BASICAUTHMODE: 'false', SILLYTAVERN_DISABLECSRFPROTECTION: 'true',
                SILLYTAVERN_HOSTWHITELIST_ENABLED: 'false', SILLYTAVERN_EXTENSIONS_MODELS_AUTODOWNLOAD: 'true' },
        });
        child.once('error', error => { report.spawnError = error.message; });
        await waitUntil(() => {
            if (report.spawnError || child.exitCode !== null || child.signalCode !== null) {
                throw new Error(`ST exited before readiness: ${fs.readFileSync(logPath, 'utf8').slice(-2500)}`);
            }
            return fs.existsSync(path.join(stateRoot, 'ready.json'));
        }, 90_000, 'ST startup did not publish readiness');
        const ready = JSON.parse(fs.readFileSync(path.join(stateRoot, 'ready.json')));
        assert.equal(ready.payloadId, pointer.payloadId);
        assert.equal(ready.origin, origin);
        report[`${label}StartupMs`] = Math.round(performance.now() - started);
        const transport = JSON.parse(fs.readFileSync(path.join(stateRoot, 'transport.json')));
        authorization = `Basic ${Buffer.from(`${transport.username}:${transport.password}`).toString('base64')}`;
        assert(!fs.readFileSync(logPath, 'utf8').includes(transport.password), 'Credentials must not appear in logs');
    };
    try {
        execFileSync('python3', ['tools/payload/verify.py', '--unpack', payloadRoot], { cwd: root, stdio: ['ignore', 'pipe', 'pipe'] });
        port = await unusedPort(); origin = `http://127.0.0.1:${port}`;
        fs.mkdirSync(stateRoot, { mode: 0o700 });
        fs.writeFileSync(launchPath, JSON.stringify({ schemaVersion: 1, payloadRoot, stateRoot, port, manifestSha256: pointer.manifestSha256 }));
        // JSON is valid YAML. Include legacy aliases that ST migrates into the
        // modern keys; policy must win AFTER migration as well as over env vars.
        fs.writeFileSync(path.join(stateRoot, 'config.yaml'), JSON.stringify({
            basicAuthMode: false, whitelist: ['*'], disableCsrfProtection: true,
            listenAddress: { ipv4: '0.0.0.0' }, browserLaunch: { enabled: true },
            hostWhitelist: { enabled: false }, enableExtensionsAutoUpdate: true,
            extras: { disableAutoDownload: false }, autheliaAuth: true,
            autorunHostname: 'desktop.invalid', autorunPortOverride: 8123,
            extensions: { models: { autoDownload: true } }, fixtureCustomSetting: 'retain-me',
        }));
        await start('cold');
        const yaml = createRequire(path.join(payloadRoot, 'server/package.json'))('yaml');
        await check('unsafe legacy config and inherited env cannot weaken mobile policy', async () => {
            const config = yaml.parse(fs.readFileSync(path.join(stateRoot, 'config.yaml'), 'utf8'));
            assert.equal(config.extensions.models.autoDownload, false);
            assert.equal(config.extensions.autoUpdate, false);
            assert.equal(config.sso.autheliaAuth, false);
            assert.equal(config.disableCsrfProtection, false);
            assert.equal(config.listenAddress.ipv4, '127.0.0.1');
            assert.equal(config.fixtureCustomSetting, 'retain-me');
            assert.equal(config.browserLaunch.hostname, 'auto');
            assert.equal(config.browserLaunch.port, -1);
            assert.equal(config.extras?.disableAutoDownload, undefined);
            assert.equal(config.dataRoot, path.join(stateRoot, 'data'));
            assert.equal(config.allowKeysExposure, true);
            assert.equal(config.performance.lazyLoadCharacters, true);
            assert.equal(config.performance.useDiskCache, false);
            assert.equal(config.backups.chat.enabled, false);
        });
        await check('Basic authentication is enforced even on loopback', async () => {
            const absent = await fetch(`${origin}/csrf-token`);
            assert.equal(absent.status, 401); await absent.body.cancel();
            const wrong = await fetch(`${origin}/csrf-token`, { headers: { authorization: 'Basic eDp5' } });
            assert.equal(wrong.status, 401); await wrong.body.cancel();
            const token = await json('/csrf-token');
            csrf = token.token;
            assert.match(csrf, /^[a-f0-9]{64}$/);
        });
        await check('same-session CSRF is required; cross-session token is rejected', async () => {
            let response = await request('/api/ping', {}, { headers: { 'X-CSRF-Token': '' } });
            assert.equal(response.status, 403); await response.body.cancel();
            response = await request('/api/ping', {});
            assert.equal(response.status, 204);
            response = await fetch(`${origin}/api/ping`, { method: 'POST', headers: { authorization, 'X-CSRF-Token': csrf } });
            assert.equal(response.status, 403); await response.body.cancel();
        });
        await check('Host spoofing is blocked and CORS is not widened', async () => {
            const status = await new Promise((resolve, reject) => {
                const req = http.get({ hostname: '127.0.0.1', port, path: '/', headers: { authorization, host: 'evil.invalid' } }, res => {
                    res.resume(); res.on('end', () => resolve(res.statusCode));
                }); req.on('error', reject);
            });
            assert.equal(status, 403);
            const response = await request('/', undefined, { headers: { origin: 'https://evil.invalid', 'X-Forwarded-For': '203.0.113.1' } });
            assert.equal(response.status, 200);
            assert.equal(response.headers.get('access-control-allow-origin'), null);
            await response.body.cancel();
        });
        await check('the actual port is unreachable via LAN interfaces and IPv6 loopback', async () => {
            const addresses = Object.values(os.networkInterfaces()).flat().filter(item => item.family === 'IPv4' && !item.internal).map(item => item.address);
            assert(addresses.length > 0, 'Need a non-loopback interface to verify binding, not just config values');
            report.bindingAddressesTested = [...addresses, '::1'];
            for (const host of report.bindingAddressesTested) {
                const connected = await new Promise(resolve => {
                    const socket = net.connect({ host, port });
                    const finish = value => { socket.destroy(); resolve(value); };
                    socket.once('connect', () => finish(true));
                    socket.once('error', () => finish(false));
                    socket.setTimeout(2000, () => finish(false));
                });
                assert.equal(connected, false, `Port unexpectedly reachable at ${host}`);
            }
        });
        await check('a concurrent launcher cannot replace the active state lock or credentials', async () => {
            const transport = fs.readFileSync(path.join(stateRoot, 'transport.json'));
            const ready = fs.readFileSync(path.join(stateRoot, 'ready.json'));
            rejectedLaunch({}, /EEXIST/);
            assert(fs.readFileSync(path.join(stateRoot, 'transport.json')).equals(transport));
            assert(fs.readFileSync(path.join(stateRoot, 'ready.json')).equals(ready));
            assert.equal((await request('/api/ping', {})).status, 204);
        });
        await check('actual ST index, webpack output and version are served', async () => {
            assert((await (await request('/')).text()).includes('SillyTavern'));
            const library = await request('/lib.js'); assert.equal(library.status, 200);
            assert((await library.text()).length > 1000);
            assert.equal((await json('/version')).pkgVersion, '1.18.0');
        });
        await check('five actual tokenizer endpoints handle Chinese text', async () => {
            for (const [name, count] of [['gpt2', 6], ['llama', 5], ['claude', 4], ['llama3', 4], ['openai', 4]]) {
                const suffix = name === 'openai' ? '?model=gpt-4' : '';
                const encoded = await json(`/api/tokenizers/${name}/encode${suffix}`, { text: '你好 Android!' });
                assert.equal(encoded.count, count, name);
                assert.equal(encoded.ids.length, count, name);
            }
        });
        await check('upstream Jimp WASM codecs work with upstream fetch initialization', async () => {
            await import(pathToFileURL(path.join(payloadRoot, 'server/src/fetch-patch.js')));
            const { Jimp, JimpMime } = await import(pathToFileURL(path.join(payloadRoot, 'server/src/jimp.js')));
            const image = new Jimp({ width: 16, height: 16, color: 0xff0000ff });
            for (const mime of [JimpMime.png, JimpMime.jpeg, 'image/webp', 'image/avif']) {
                const bytes = await image.getBuffer(mime, mime === JimpMime.jpeg ? { quality: 95, jpegColorSpace: 'ycbcr' } : {});
                const decoded = await Jimp.read(bytes);
                assert.equal(decoded.bitmap.width, 16, mime);
                assert.equal(decoded.bitmap.height, 16, mime);
            }
        });
        await check('PNG character metadata survives actual API create/export/import', async () => {
            let response = await request('/api/characters/create', { ch_name: 'M2 角色', description: 'metadata 回归', first_mes: 'Hello' });
            assert.equal(response.status, 200);
            avatar = await response.text(); assert(avatar.endsWith('.png'));
            response = await request('/api/characters/export', { avatar_url: avatar, format: 'png' });
            assert.equal(response.status, 200);
            const bytes = Buffer.from(await response.arrayBuffer());
            const parser = await import(pathToFileURL(path.join(payloadRoot, 'server/src/character-card-parser.js')));
            const metadata = JSON.parse(parser.read(bytes));
            assert.equal(metadata.data.description, 'metadata 回归');
            const form = new FormData();
            form.append('file_type', 'png'); form.append('preserved_name', 'M2 Import');
            form.append('avatar', new Blob([bytes], { type: 'image/png' }), 'fixture.png');
            const imported = await json('/api/characters/import', form);
            assert.equal(imported.file_name, 'M2 Import');
            const importedCard = await json('/api/characters/get', { avatar_url: 'M2 Import.png' });
            assert.equal(importedCard.description, 'metadata 回归');
        });
        const chat = [{ chat_metadata: { integrity: 'm2-host-fixture' } },
            { name: 'User', is_user: true, mes: '你好，世界 🌍', send_date: '2026-01-01T00:00:00Z' }];
        await check('settings, chats and secrets use external state; integrity guard is preserved', async () => {
            const initial = JSON.parse((await json('/api/settings/get', {})).settings);
            initial.mobile_host_regression_marker = 'keep-after-restart';
            assert.equal((await json('/api/settings/save', initial)).result, 'ok');
            assert.equal((await json('/api/chats/save', { avatar_url: avatar, file_name: 'm2-log', chat })).ok, true);
            assert.deepEqual(await json('/api/chats/get', { avatar_url: avatar, file_name: 'm2-log' }), chat);
            const bad = structuredClone(chat); bad[0].chat_metadata.integrity = 'wrong';
            assert.equal((await json('/api/chats/save', { avatar_url: avatar, file_name: 'm2-log', chat: bad }, 400)).error, 'integrity');
            const secret = await json('/api/secrets/write', { key: 'api_key_openai', value: 'fixture-not-a-real-key' });
            assert(secret.id);
            const user = path.join(stateRoot, 'data/default-user');
            assert(fs.existsSync(path.join(user, 'settings.json')));
            assert(fs.existsSync(path.join(user, 'secrets.json')));
            assert(fs.existsSync(path.join(user, 'characters', avatar)));
        });
        await check('remote SSE proxy and client cancellation retain upstream behavior', async () => {
            const received = []; let cancelled = 0;
            mock = http.createServer(async (req, res) => {
                let body = ''; for await (const block of req) body += block;
                const data = JSON.parse(body); received.push({ url: req.url, auth: req.headers.authorization, cookie: req.headers.cookie, csrf: req.headers['x-csrf-token'], data });
                res.writeHead(200, { 'Content-Type': 'text/event-stream' });
                res.write('data: {"choices":[{"delta":{"content":"one"}}]}\n\n');
                if (data.messages[0].content === 'cancel') {
                    const timer = setInterval(() => res.write(': still running\n\n'), 100);
                    res.on('close', () => { clearInterval(timer); if (!res.writableEnded) cancelled++; });
                } else {
                    setTimeout(() => res.end('data: {"choices":[{"delta":{"content":"two"}}]}\n\ndata: [DONE]\n\n'), 80);
                }
            });
            mock.listen(0, '127.0.0.1'); await once(mock, 'listening');
            const body = { chat_completion_source: 'openai', reverse_proxy: `http://127.0.0.1:${mock.address().port}/v1`,
                proxy_password: 'fixture-not-real', model: 'gpt-4o-mini', stream: true, messages: [{ role: 'user', content: 'complete' }] };
            let response = await request('/api/backends/chat-completions/generate', body);
            assert.equal(response.status, 200);
            const stream = await response.text();
            assert(stream.includes('one') && stream.includes('two') && stream.includes('[DONE]'));
            const controller = new AbortController();
            response = await request('/api/backends/chat-completions/generate', { ...body, messages: [{ role: 'user', content: 'cancel' }] }, { signal: controller.signal });
            const reader = response.body.getReader();
            assert.equal((await reader.read()).done, false);
            controller.abort(); await reader.cancel().catch(() => {});
            await waitUntil(() => cancelled === 1, 5000, 'ST did not cancel the upstream stream after client disconnect');
            assert.equal(received.length, 2);
            assert(received.every(item => item.url.endsWith('/chat/completions') && item.auth === 'Bearer fixture-not-real'));
            assert(received.every(item => item.cookie === undefined && item.csrf === undefined), 'Do not forward local session/CSRF credentials to remote API');
            mock.closeAllConnections(); await new Promise(resolve => mock.close(resolve)); mock = undefined;
        });
        const transportBefore = fs.readFileSync(path.join(stateRoot, 'transport.json'));
        const cookieSecretBefore = fs.readFileSync(path.join(stateRoot, 'data/cookie-secret.txt'));
        await stop();
        await start('warm');
        await check('clean restart preserves origin, credentials, session and user data', async () => {
            assert(fs.readFileSync(path.join(stateRoot, 'transport.json')).equals(transportBefore));
            assert(fs.readFileSync(path.join(stateRoot, 'data/cookie-secret.txt')).equals(cookieSecretBefore));
            assert.equal((await request('/api/ping', {})).status, 204, 'old valid cookie/CSRF should survive restart');
            assert.equal(JSON.parse((await json('/api/settings/get', {})).settings).mobile_host_regression_marker, 'keep-after-restart');
            assert.deepEqual(await json('/api/chats/get', { avatar_url: avatar, file_name: 'm2-log' }), chat);
        });
        await stop();
        await check('a quiescent state snapshot restores under a different absolute sandbox path', async () => {
            const snapshot = path.join(directory, 'snapshot');
            fs.cpSync(stateRoot, snapshot, { recursive: true });
            stateRoot = path.join(directory, 'restored-state');
            fs.cpSync(snapshot, stateRoot, { recursive: true });
            const descriptor = JSON.parse(fs.readFileSync(launchPath));
            descriptor.stateRoot = stateRoot;
            fs.writeFileSync(launchPath, JSON.stringify(descriptor));
            await start('restored');
            assert.equal((await request('/api/ping', {})).status, 204);
            assert.equal(JSON.parse((await json('/api/settings/get', {})).settings).mobile_host_regression_marker, 'keep-after-restart');
            assert.deepEqual(await json('/api/chats/get', { avatar_url: avatar, file_name: 'm2-log' }), chat);
            assert(fs.readFileSync(path.join(stateRoot, 'transport.json')).equals(transportBefore));
            await stop();
        });
        await check('bad manifests, payload tampering and overlapping state paths fail before state writes', async () => {
            const rejectedState = path.join(directory, 'rejected-state');
            rejectedLaunch({ stateRoot: rejectedState, manifestSha256: '0'.repeat(64) }, /Manifest does not match/);
            assert(!fs.existsSync(rejectedState));
            const entry = path.join(payloadRoot, 'server/server.js');
            const original = fs.readFileSync(entry);
            try {
                fs.appendFileSync(entry, '\n// diagnostic tamper\n');
                rejectedLaunch({ stateRoot: rejectedState }, /Payload (size|hash) mismatch/);
                assert(!fs.existsSync(rejectedState));
            } finally { fs.writeFileSync(entry, original); }
            const invalidState = path.join(payloadRoot, 'invalid-state');
            rejectedLaunch({ stateRoot: invalidState }, /State and payload must be separate/);
            assert(!fs.existsSync(invalidState));
        });
        await check('server startup did not mutate payload files or download local ONNX models', async () => {
            const manifest = JSON.parse(fs.readFileSync(path.join(payloadRoot, 'payload-manifest.json')));
            for (const [name, file] of Object.entries(manifest.files)) assert.equal(await fileHash(path.join(payloadRoot, name)), file.sha256, name);
            const visit = directory => fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => entry.isDirectory()
                ? visit(path.join(directory, entry.name)) : [path.join(directory, entry.name)]);
            const actual = visit(payloadRoot).filter(p => p !== path.join(payloadRoot, 'payload-manifest.json'));
            assert.equal(actual.length, Object.keys(manifest.files).length, 'unexpected writes into payload');
            assert(!visit(path.join(stateRoot, 'data')).some(p => p.endsWith('.onnx')), 'no local model weights expected');
            assert(!fs.existsSync(path.join(stateRoot, 'bootstrap.lock')));
        });
        report.passed = report.tests.every(step => step.passed);
    } finally {
        if (mock) { mock.closeAllConnections(); await new Promise(resolve => mock.close(resolve)); }
        if (child && child.exitCode === null && child.signalCode === null) {
            const exited = once(child, 'exit'); child.kill('SIGKILL'); await exited;
        }
        fs.closeSync(log);
        fs.writeFileSync(path.join(reportDir, 'st-host.json'), JSON.stringify(report, null, 2));
        fs.rmSync(directory, { recursive: true, force: true });
    }
});

// SPDX-License-Identifier: AGPL-3.0-only
// Explicit live-network acceptance: not a mocked GitHub/npm success path.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import crypto from 'node:crypto';
import { once } from 'node:events';
import { spawn, execFileSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { gitFixture } from './git-fixture.mjs';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const reportDir = path.join(ROOT, 'build/reports');
fs.mkdirSync(reportDir, { recursive: true });
const work = fs.mkdtempSync(path.join(ROOT, 'build/online-test-'));
const installerRoot = path.join(work, 'installer');
const state = path.join(work, 'state');
fs.mkdirSync(state, { mode: 0o700 });
const pointer = JSON.parse(fs.readFileSync(path.join(ROOT, 'build/installer/current.json')));
execFileSync('python3', ['-c', `import sys,json; from pathlib import Path; sys.path.insert(0,'tools/app'); from prepare_installer import current,POLICY; from common import verify_zip; a,p=current(); verify_zip(a/'payload.zip',json.loads((a/'manifest.json').read_text()),Path(sys.argv[1]),POLICY); (Path(sys.argv[1])/'payload-manifest.json').write_bytes((a/'manifest.json').read_bytes())`, installerRoot], { cwd: ROOT });
const { prepareOnlineLaunch, verifyInstallation } = await import(pathToFileURL(path.join(installerRoot, 'shell/first-install.mjs')));
const required = ['ST-Prompt-Template', 'JS-Slash-Runner', 'st-theater'];
const report = { scope: 'live-github-npm-linux-host', work, passed: false, results: [] };
function save() { fs.writeFileSync(path.join(reportDir, 'online-install.json'), JSON.stringify(report, null, 2)); }

test('first-run staging + three global plugins; reuse; integrity; real server', { timeout: 600_000 }, async t => {
    let child, log;
    try {
        const server = net.createServer(); server.listen(0, '127.0.0.1'); await once(server, 'listening');
        const port = server.address().port; await new Promise(resolve => server.close(resolve));
        const launch = { schemaVersion: 1, payloadRoot: installerRoot, stateRoot: state, port, manifestSha256: pointer.manifestSha256 };
        const start = performance.now();
        let last = '';
        const result = await prepareOnlineLaunch(launch, { progress(p) {
            if (p.phase + p.detail !== last) { console.log(p.phase, p.detail); last = p.phase + p.detail; }
        } });
        report.installMs = Math.round(performance.now() - start);
        const info = JSON.parse(fs.readFileSync(path.join(state, 'installation-info.json')));
        assert.equal(info.upstream.ref, 'staging');
        assert.match(info.upstream.commit, /^[a-f0-9]{40}$/);
        assert.deepEqual(info.extensions.map(x => x.name).sort(), required.toSorted());
        report.upstream = info.upstream; report.extensions = info.extensions; save();
        report.results.push('real-network first install succeeded');
        fs.writeFileSync(path.join(state, 'user-sentinel.txt'), 'preserve-user-data');
        const originalFetch = globalThis.fetch;
        try {
            globalThis.fetch = () => { throw new Error('Offline: network must not be requested on repeat launch'); };
            const reused = await prepareOnlineLaunch(launch);
            assert.equal(reused.payloadId, result.payloadId);
        } finally { globalThis.fetch = originalFetch; }
        report.results.push('offline repeat install reused exact commit/inventory');
        // Abandoned UUID staging directories are removed; unrelated names survive.
        const base = path.dirname(result.launch.payloadRoot);
        const abandoned = path.join(base, '.install-' + crypto.randomUUID());
        fs.mkdirSync(abandoned); fs.writeFileSync(path.join(abandoned, 'partial'), 'interrupted');
        fs.mkdirSync(path.join(base, 'do-not-delete'));
        await prepareOnlineLaunch(launch);
        assert(!fs.existsSync(abandoned)); assert(fs.existsSync(path.join(base, 'do-not-delete')));
        report.results.push('abandoned stage reclaimed; unrelated directory retained');
        // Read-only roots MUST NOT allow same-size tampering to evade hashing.
        const target = path.join(result.launch.payloadRoot, 'server/server.js');
        const original = fs.readFileSync(target);
        fs.chmodSync(target, 0o600);
        const changed = Buffer.from(original); changed[0] ^= 1;
        fs.writeFileSync(target, changed); fs.chmodSync(target, 0o400);
        await assert.rejects(verifyInstallation(result.launch.payloadRoot, result.launch.manifestSha256), /content changed/);
        fs.chmodSync(target, 0o600); fs.writeFileSync(target, original); fs.chmodSync(target, 0o400);
        report.results.push('read-only same-size content tampering rejected');
        const launchPath = path.join(state, 'app-launch.json');
        fs.writeFileSync(launchPath, JSON.stringify(launch));
        const logPath = path.join(reportDir, 'online-server.log'); log = fs.openSync(logPath, 'w', 0o600);
        child = spawn(process.execPath, [path.join(ROOT, 'runtime/android/entry.mjs'), launchPath], { cwd: ROOT, stdio: ['ignore', log, log] });
        const deadline = Date.now() + 120_000;
        while (!fs.existsSync(path.join(state, 'ready.json'))) {
            if (child.exitCode !== null) throw new Error('Server exited: ' + fs.readFileSync(logPath, 'utf8').slice(-3500));
            assert(Date.now() < deadline, 'Server readiness timeout');
            await new Promise(resolve => setTimeout(resolve, 200));
        }
        const ready = JSON.parse(fs.readFileSync(path.join(state, 'ready.json')));
        assert.equal(ready.payloadId, result.payloadId);
        const identity = JSON.parse(fs.readFileSync(path.join(state, 'transport.json')));
        const headers = { authorization: 'Basic ' + Buffer.from(`${identity.username}:${identity.password}`).toString('base64') };
        const origin = `http://127.0.0.1:${port}`;
        const denied = await fetch(origin + '/version'); assert.equal(denied.status, 401); await denied.body.cancel();
        for (const ext of info.extensions) {
            const prefix = origin + '/scripts/extensions/third-party/' + ext.name + '/';
            const res = await fetch(prefix + 'manifest.json', { headers });
            assert.equal(res.status, 200); const m = await res.json();
            assert.equal(m.version, ext.version);
            const entry = await fetch(prefix + m.js, { headers });
            assert.equal(entry.status, 200); await entry.body.cancel();
        }
        report.results.push('real ST ready with Basic auth; all three plugin manifests/JS served');
        const fixture = await gitFixture(work);
        try {
            const csrfResponse = await fetch(origin + '/csrf-token', { headers });
            const cookie = csrfResponse.headers.getSetCookie().map(s => s.split(';')[0]).join('; ');
            const { token } = await csrfResponse.json();
            const install = async global => fetch(origin + '/api/extensions/install', {
                method: 'POST', headers: { ...headers, cookie, 'X-CSRF-Token': token, 'Content-Type': 'application/json' },
                body: JSON.stringify({ url: fixture.url, global }),
            });
            const blocked = await install(true);
            assert.equal(blocked.status, 500); await blocked.body.cancel();
            await verifyInstallation(result.launch.payloadRoot, result.launch.manifestSha256);
            const userInstall = await install(false);
            assert.equal(userInstall.status, 200, await userInstall.text());
            const discovered = await fetch(origin + '/api/extensions/discover', { headers: { ...headers, cookie } });
            const extensions = await discovered.json();
            for (const name of required) assert(extensions.some(e => e.name === 'third-party/' + name && e.type === 'global'));
            assert(extensions.some(e => e.name.includes('regression-extension') && e.type === 'local'));
            report.results.push('all built-ins discovered as global; global write blocked; real user-level install succeeds');
        } finally { await fixture.close(); }
        const exited = once(child, 'exit'); child.kill('SIGTERM'); await exited;
        assert.equal(child.exitCode, 0); child = null;
        assert.equal(fs.readFileSync(path.join(state, 'user-sentinel.txt'), 'utf8'), 'preserve-user-data');
        await verifyInstallation(result.launch.payloadRoot, result.launch.manifestSha256);
        report.results.push('clean shutdown; user data and immutable installation unchanged');
        // An explicit repair must keep the old pointer if the network fails.
        const pointerPath = path.join(base, 'current.json');
        const oldPointer = fs.readFileSync(pointerPath, 'utf8');
        fs.writeFileSync(path.join(state, 'reinstall-request.json'), JSON.stringify({ schemaVersion: 1 }));
        try {
            globalThis.fetch = () => { throw new Error('offline repair fixture'); };
            await assert.rejects(prepareOnlineLaunch(launch), /offline repair fixture/);
        } finally { globalThis.fetch = originalFetch; }
        assert.equal(fs.readFileSync(pointerPath, 'utf8'), oldPointer);
        assert(fs.existsSync(path.join(state, 'reinstall-request.json')));
        report.results.push('failed explicit redownload leaves active pointer and user data intact');
        // Also cover repairing a damaged installation of the SAME latest commit.
        fs.chmodSync(target, 0o600); fs.writeFileSync(target, changed); fs.chmodSync(target, 0o400);
        const repaired = await prepareOnlineLaunch(launch);
        await verifyInstallation(repaired.launch.payloadRoot, repaired.launch.manifestSha256);
        assert(!fs.existsSync(path.join(state, 'reinstall-request.json')));
        assert.equal(fs.readFileSync(path.join(state, 'user-sentinel.txt'), 'utf8'), 'preserve-user-data');
        report.results.push('successful explicit redownload repairs damaged content and preserves user data');
        report.passed = true;
    } finally {
        if (child && child.exitCode === null) { const exited = once(child, 'exit'); child.kill('SIGKILL'); await exited; }
        if (log !== undefined) fs.closeSync(log);
        save(); // Keep real downloaded fixtures and report for diagnosis; never fake output.
    }
});

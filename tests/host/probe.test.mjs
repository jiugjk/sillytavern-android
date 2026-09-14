// SPDX-License-Identifier: AGPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
// This test intentionally performs DNS/TLS to nodejs.org, like the Android probe.
// Offline is a failure, not a skipped security test.
test('probe executes all host capabilities but explicitly marks itself NOT Android evidence', { timeout: 150_000 }, async () => {
    const directory = await mkdtemp(path.join(tmpdir(), 'st-node26-probe-'));
    const reportFile = path.join(directory, 'report.json');
    const nonce = crypto.randomBytes(16).toString('hex');
    try {
        const result = await new Promise((resolve, reject) => {
            const child = spawn(process.execPath, [path.join(root, 'runtime/probe/probe.mjs'), reportFile, nonce, 'host']);
            let output = '';
            child.stdout.on('data', chunk => { output += chunk; });
            child.stderr.on('data', chunk => { output += chunk; });
            child.once('error', reject);
            child.once('close', (code, signal) => resolve({ code, signal, output }));
        });
        const report = JSON.parse(await readFile(reportFile, 'utf8'));
        assert.equal(result.code, 0, JSON.stringify({ result, report }, null, 2));
        assert.equal(report.mode, 'host');
        assert.equal(report.nonce, nonce);
        assert.equal(report.completed, true);
        assert.equal(report.passed, true);
        assert.equal(report.tests.length, 12);
        assert(report.tests.every(check => check.passed === true));
    } finally { await rm(directory, { recursive: true, force: true }); }
});

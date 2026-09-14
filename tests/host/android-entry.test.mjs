// SPDX-License-Identifier: AGPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

test('Android entry captures startup only and preserves stream output/exit', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'st-entry-'));
    try {
        const payload = path.join(root, 'payload'); const state = path.join(root, 'state');
        fs.mkdirSync(path.join(payload, 'server/src'), { recursive: true });
        fs.mkdirSync(path.join(payload, 'shell')); fs.mkdirSync(state);
        fs.writeFileSync(path.join(payload, 'server/package.json'), '{"type":"module"}');
        fs.writeFileSync(path.join(payload, 'server/src/server-events.js'), 'import EventEmitter from "node:events"; export const serverEvents=new EventEmitter(); export const EVENT_NAMES={SERVER_STARTED:"ready"};');
        fs.writeFileSync(path.join(payload, 'shell/bootstrap.mjs'), 'import {serverEvents} from "../server/src/server-events.js"; console.log("STARTUP_MARKER"); serverEvents.emit("ready"); console.log("PRIVATE_AFTER_READY");');
        const launch = path.join(root, 'launch.json');
        fs.writeFileSync(launch, JSON.stringify({ stateRoot: state, payloadRoot: payload }));
        const result = spawnSync(process.execPath, [fileURLToPath(new URL('../../runtime/android/entry.mjs', import.meta.url)), launch], { encoding: 'utf8', timeout: 10000 });
        assert.equal(result.status, 0, result.stderr);
        assert(result.stdout.includes('STARTUP_MARKER') && result.stdout.includes('PRIVATE_AFTER_READY'));
        const log = fs.readFileSync(path.join(state, 'startup.log'), 'utf8');
        assert(log.includes('STARTUP_MARKER')); assert(!log.includes('PRIVATE_AFTER_READY'));
        const exit = JSON.parse(fs.readFileSync(path.join(state, 'node-exit.json')));
        assert.equal(exit.exitCode, 0); assert.equal(exit.readySeen, true);
        assert.equal(JSON.parse(fs.readFileSync(path.join(state, 'node-info.json'))).node, process.versions.node);
    } finally { fs.rmSync(root, { recursive: true, force: true }); }
});

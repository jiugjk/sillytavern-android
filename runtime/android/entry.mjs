// SPDX-License-Identifier: AGPL-3.0-only
// App-owned diagnostics around the unmodified payload bootstrap. No fetch,
// generation, abort or ST route monkey-patching.
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { observeRequests } from './request-observer.mjs';

if (process.argv.length !== 3 || !path.isAbsolute(process.argv[2])) throw new Error('Expected private launch descriptor');
let launchPath = process.argv[2];
if (fs.statSync(launchPath).size > 16384) throw new Error('Oversized launch descriptor');
let launch = JSON.parse(fs.readFileSync(launchPath, 'utf8'));
if (!path.isAbsolute(launch.stateRoot) || !path.isAbsolute(launch.payloadRoot)) throw new Error('Absolute sandbox paths required');
const state = launch.stateRoot;
const logPath = path.join(state, 'startup.log');
const infoPath = path.join(state, 'node-info.json');
const exitPath = path.join(state, 'node-exit.json');
const activityObserver = observeRequests({ port: launch.port, output: path.join(state, 'request-activity.json') });
let ready = false;
// Startup log tail. `Buffer.concat(...).subarray(-LIMIT)` would keep the whole
// oversized backing allocation alive behind a 64 KiB view, and rewriting the
// file on every single write amplifies both copying and disk I/O. Keep an owned
// copy of the tail and coalesce the writes instead.
const LOG_LIMIT = 64 * 1024;
let log = Buffer.alloc(0);
let logDirty = false;
let logTimer = null;
function flushLog() {
    if (logTimer) { clearTimeout(logTimer); logTimer = null; }
    if (!logDirty) return;
    logDirty = false;
    try { fs.writeFileSync(logPath, log, { mode: 0o600 }); }
    catch { /* Diagnostics must not change the underlying write result. */ }
}
function appendLog(bytes) {
    // Copy the trimmed tail so no reference to a large input Buffer survives.
    const tail = bytes.length >= LOG_LIMIT
        ? bytes.subarray(bytes.length - LOG_LIMIT)
        : Buffer.concat([log, bytes]).subarray(-LOG_LIMIT);
    log = Buffer.from(tail);
    logDirty = true;
    if (!logTimer) {
        logTimer = setTimeout(flushLog, 50);
        // A pending diagnostic flush must never hold the process open.
        if (typeof logTimer.unref === 'function') logTimer.unref();
    }
}
const hooked = [];
function store(file, value) {
    const temporary = `${file}.tmp`;
    fs.writeFileSync(temporary, JSON.stringify(value), { mode: 0o600 });
    fs.renameSync(temporary, file);
}
for (const stream of [process.stdout, process.stderr]) {
    const original = stream.write;
    function write(...args) {
        if (!ready) {
            try {
                const bytes = Buffer.isBuffer(args[0]) ? args[0] : Buffer.from(String(args[0]));
                appendLog(bytes);
            } catch { /* Diagnostics must not change the underlying write result. */ }
        }
        return original.apply(this, args);
    }
    stream.write = write;
    hooked.push(() => { if (stream.write === write) stream.write = original; });
}
process.on('exit', code => {
    activityObserver.dispose();
    // Terminal flush: the coalesced tail must reach disk before we exit.
    flushLog();
    try { store(exitPath, { schemaVersion: 1, pid: process.pid, exitCode: code, readySeen: ready }); } catch { /* Abrupt OS death may leave no exit report. */ }
});
// Old diagnostic payloads remain supported; APKs now contain the installer only.
const installer = path.join(launch.payloadRoot, 'shell/first-install.mjs');
if (fs.existsSync(installer)) {
    const { prepareOnlineLaunch } = await import(pathToFileURL(installer));
    const result = await prepareOnlineLaunch(launch);
    launch = result.launch; launchPath = result.launchPath;
}
const events = await import(pathToFileURL(path.join(launch.payloadRoot, 'server/src/server-events.js')));
events.serverEvents.prependOnceListener(events.EVENT_NAMES.SERVER_STARTED, () => {
    ready = true;
    hooked.forEach(restore => restore());
    // Nothing more will be captured; publish the startup log as it stands.
    flushLog();
    try { store(infoPath, { schemaVersion: 1, pid: process.pid, node: process.versions.node,
        platform: process.platform, arch: process.arch, memoryUsage: process.memoryUsage() }); } catch { /* Optional diagnostics. */ }
});
const bootstrap = path.join(launch.payloadRoot, 'shell/bootstrap.mjs');
process.argv = [process.execPath, bootstrap, launchPath];
await import(pathToFileURL(bootstrap));

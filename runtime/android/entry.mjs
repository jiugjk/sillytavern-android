// SPDX-License-Identifier: AGPL-3.0-only
// App-owned diagnostics around the unmodified payload bootstrap. No fetch,
// generation, abort or ST route monkey-patching.
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

if (process.argv.length !== 3 || !path.isAbsolute(process.argv[2])) throw new Error('Expected private launch descriptor');
const launchPath = process.argv[2];
if (fs.statSync(launchPath).size > 16384) throw new Error('Oversized launch descriptor');
const launch = JSON.parse(fs.readFileSync(launchPath, 'utf8'));
if (!path.isAbsolute(launch.stateRoot) || !path.isAbsolute(launch.payloadRoot)) throw new Error('Absolute sandbox paths required');
const state = launch.stateRoot;
const logPath = path.join(state, 'startup.log');
const infoPath = path.join(state, 'node-info.json');
const exitPath = path.join(state, 'node-exit.json');
let ready = false;
let log = Buffer.alloc(0);
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
                log = Buffer.concat([log, bytes]).subarray(-65536);
                fs.writeFileSync(logPath, log, { mode: 0o600 });
            } catch { /* Diagnostics must not change the underlying write result. */ }
        }
        return original.apply(this, args);
    }
    stream.write = write;
    hooked.push(() => { if (stream.write === write) stream.write = original; });
}
process.on('exit', code => {
    try { store(exitPath, { schemaVersion: 1, pid: process.pid, exitCode: code, readySeen: ready }); } catch { /* Abrupt OS death may leave no exit report. */ }
});
const events = await import(pathToFileURL(path.join(launch.payloadRoot, 'server/src/server-events.js')));
events.serverEvents.prependOnceListener(events.EVENT_NAMES.SERVER_STARTED, () => {
    ready = true;
    hooked.forEach(restore => restore());
    try { store(infoPath, { schemaVersion: 1, pid: process.pid, node: process.versions.node,
        platform: process.platform, arch: process.arch, memoryUsage: process.memoryUsage() }); } catch { /* Optional diagnostics. */ }
});
const bootstrap = path.join(launch.payloadRoot, 'shell/bootstrap.mjs');
process.argv = [process.execPath, bootstrap, launchPath];
await import(pathToFileURL(bootstrap));

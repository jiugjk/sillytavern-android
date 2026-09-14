// SPDX-License-Identifier: AGPL-3.0-only
// Outer entry point only. Never modifies ST functions, routes or generation logic.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import { parseLaunch, mergeConfig, managedDirectory, checkPrivateFile, atomicPrivateWrite, transportIdentity, applyMobilePolicy } from './policy.mjs';

const digest = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
function under(parent, child) {
    const relative = path.relative(parent, child);
    return relative === '' || (!relative.startsWith(`..${path.sep}`) && relative !== '..' && !path.isAbsolute(relative));
}
function futureRealPath(value) {
    let cursor = path.resolve(value);
    const suffix = [];
    while (!fs.existsSync(cursor)) {
        suffix.unshift(path.basename(cursor));
        const parent = path.dirname(cursor);
        assert.notEqual(parent, cursor, 'Cannot resolve state location');
        cursor = parent;
    }
    return path.join(fs.realpathSync(cursor), ...suffix);
}
function safeName(name) {
    assert(typeof name === 'string' && name && !/[\\:\x00-\x1f]/.test(name), 'Unsafe payload name');
    assert(!name.startsWith('/') && name.split('/').every(part => part && part !== '.' && part !== '..'), 'Unsafe payload path');
}
async function verifyTree(root, manifest) {
    assert.equal(manifest.schemaVersion, 1);
    assert(/^[a-f0-9]{64}$/.test(manifest.payloadId));
    const files = manifest.files;
    assert(files && typeof files === 'object' && !Array.isArray(files));
    assert(Object.keys(files).length <= 60000, 'Payload has too many files');
    const actual = new Set();
    const visit = async (directory, prefix = '') => {
        for (const entry of await fsp.readdir(directory, { withFileTypes: true })) {
            const name = prefix + entry.name;
            if (name === 'payload-manifest.json') continue;
            safeName(name);
            const full = path.join(directory, entry.name);
            assert(!entry.isSymbolicLink(), 'Payload symlinks are forbidden');
            if (entry.isDirectory()) await visit(full, `${name}/`);
            else {
                assert(entry.isFile(), 'Payload special files are forbidden');
                const expected = files[name];
                assert(expected && /^[a-f0-9]{64}$/.test(expected.sha256), `Unexpected payload file: ${name}`);
                const stat = await fsp.stat(full);
                assert.equal(stat.size, expected.size, `Payload size mismatch: ${name}`);
                const hash = crypto.createHash('sha256');
                for await (const block of fs.createReadStream(full)) hash.update(block);
                assert.equal(hash.digest('hex'), expected.sha256, `Payload hash mismatch: ${name}`);
                actual.add(name);
            }
        }
    };
    await visit(root);
    assert.equal(actual.size, Object.keys(files).length, 'Payload files are missing');
    for (const name of manifest.directories) {
        safeName(name);
        const stat = await fsp.lstat(path.join(root, name));
        assert(stat.isDirectory() && !stat.isSymbolicLink(), 'Required payload directory missing/unsafe');
    }
}

async function main() {
    assert.equal(Number(process.versions.node.split('.')[0]), 26, 'Node26 is required');
    assert.equal(process.argv.length, 3, 'Usage: node <payload>/shell/bootstrap.mjs /absolute/launch.json');
    assert(path.isAbsolute(process.argv[2]), 'Launch descriptor path must be absolute');
    assert(fs.statSync(process.argv[2]).size <= 16384, 'Launch descriptor is too large');
    const launch = parseLaunch(JSON.parse(fs.readFileSync(process.argv[2], 'utf8')));
    assert(!process.env.NODE_OPTIONS, 'NODE_OPTIONS is not accepted by this diagnostic entry point');
    assert.notEqual(process.env.NODE_TLS_REJECT_UNAUTHORIZED, '0', 'TLS certificate validation must stay enabled');
    assert(globalThis.COMMAND_LINE_ARGS === undefined, 'A server already initialized in this Node process');
    const payloadRoot = fs.realpathSync(launch.payloadRoot);
    const shellRoot = fs.realpathSync(path.dirname(fileURLToPath(import.meta.url)));
    assert.equal(shellRoot, path.join(payloadRoot, 'shell'), 'Launch the adapter bundled with this payload');
    const manifestPath = path.join(payloadRoot, 'payload-manifest.json');
    assert(fs.statSync(manifestPath).size <= 32 * 1024 * 1024, 'Manifest is too large');
    const manifestBytes = fs.readFileSync(manifestPath);
    assert.equal(digest(manifestBytes), launch.manifestSha256, 'Manifest does not match the trusted launch descriptor');
    const manifest = JSON.parse(manifestBytes);
    // Android must also verify before executing this JS, at payload installation.
    // This full diagnostic check favors integrity over startup performance.
    await verifyTree(payloadRoot, manifest);
    const proposedState = futureRealPath(launch.stateRoot);
    assert(!under(payloadRoot, proposedState) && !under(proposedState, payloadRoot), 'State and payload must be separate directories');
    process.umask(0o077);
    const stateRoot = managedDirectory(launch.stateRoot);
    const dataRoot = managedDirectory(path.join(stateRoot, 'data'));
    const readyPath = path.join(stateRoot, 'ready.json');
    const lockPath = path.join(stateRoot, 'bootstrap.lock');
    const lockFd = fs.openSync(lockPath, 'wx', 0o600);
    const lockStat = fs.fstatSync(lockFd);
    fs.writeFileSync(lockFd, JSON.stringify({ schemaVersion: 1, pid: process.pid }));
    fs.fsyncSync(lockFd);
    process.on('exit', () => {
        try {
            const current = fs.lstatSync(lockPath);
            if (current.ino === lockStat.ino && current.dev === lockStat.dev) {
                fs.unlinkSync(lockPath);
                try { fs.unlinkSync(readyPath); } catch { /* May not have reached readiness. */ }
            }
        } catch { /* Do not delete a lock owned by another process. */ }
        try { fs.closeSync(lockFd); } catch { /* Process is exiting. */ }
    });
    checkPrivateFile(readyPath);
    if (fs.existsSync(readyPath)) fs.unlinkSync(readyPath);
    const identity = transportIdentity(stateRoot, launch.port);
    const serverRoot = path.join(payloadRoot, 'server');
    // Clear inherited overrides before even the config-migration module loads.
    for (const key of Object.keys(process.env)) if (key.startsWith('SILLYTAVERN_')) delete process.env[key];
    process.env.NODE_ENV = 'production';
    process.env.GIT_CEILING_DIRECTORIES = payloadRoot;
    process.chdir(serverRoot);
    const require = createRequire(path.join(serverRoot, 'package.json'));
    const yaml = require('yaml');
    const defaults = yaml.parse(fs.readFileSync(path.join(serverRoot, 'default/config.yaml'), 'utf8'));
    const configPath = path.join(stateRoot, 'config.yaml');
    checkPrivateFile(configPath);
    let existing = {};
    if (fs.existsSync(configPath)) {
        try { existing = yaml.parse(fs.readFileSync(configPath, 'utf8')) ?? {}; }
        catch { throw new Error('Cannot parse private config.yaml; inspect it locally (contents withheld from logs)'); }
    }
    const policy = JSON.parse(fs.readFileSync(path.join(shellRoot, 'mobile-policy.json'), 'utf8'));
    // Legacy aliases (e.g. extras.disableAutoDownload) can override new keys
    // during ST's migration. Run the official migration BEFORE applying policy.
    // This helper does not set/cache the runtime config path.
    atomicPrivateWrite(configPath, yaml.stringify(mergeConfig(existing)));
    const { addMissingConfigValues } = await import(pathToFileURL(path.join(serverRoot, 'src/config-init.js')));
    addMissingConfigValues(configPath);
    const migrated = yaml.parse(fs.readFileSync(configPath, 'utf8'));
    const config = applyMobilePolicy(defaults, migrated, policy, identity, dataRoot);
    atomicPrivateWrite(configPath, yaml.stringify(config));
    // Prove another upstream migration cannot undo the enforced settings before
    // loading any server/router/plugin code. Never log private config diffs.
    addMissingConfigValues(configPath);
    const finalConfig = yaml.parse(fs.readFileSync(configPath, 'utf8'));
    assert(isDeepStrictEqual(finalConfig, applyMobilePolicy(defaults, finalConfig, policy, identity, dataRoot)),
        'Upstream migration changed the required mobile policy');
    process.argv = [process.execPath, path.join(serverRoot, 'server.js'),
        '--global', 'false', '--configPath', configPath, '--dataRoot', dataRoot,
        '--port', String(launch.port), '--listen', 'true', '--listenAddressIPv4', '127.0.0.1',
        '--enableIPv4', 'true', '--enableIPv6', 'false', '--basicAuthMode', 'true',
        '--whitelist', 'true', '--disableCsrf', 'false', '--corsProxy', 'false',
        '--ssl', 'false', '--browserLaunchEnabled', 'false'];
    const origin = `http://127.0.0.1:${launch.port}`;
    const { serverEvents, EVENT_NAMES } = await import(pathToFileURL(path.join(serverRoot, 'src/server-events.js')));
    // Startup diagnostics only, cleared on readiness; not a generation timeout.
    const startupTimer = setTimeout(() => {
        console.error('[shell] Server did not reach readiness within the diagnostic startup window');
        process.exit(1);
    }, 120_000);
    serverEvents.once(EVENT_NAMES.SERVER_STARTED, async ({ url }) => {
        try {
            assert.equal(new URL(url).origin, origin, 'Unexpected server origin');
            const args = globalThis.COMMAND_LINE_ARGS;
            assert(args.listen && args.basicAuthMode && args.whitelistMode && !args.disableCsrf);
            assert(args.enableIPv4 && !args.enableIPv6 && args.listenAddressIPv4 === '127.0.0.1');
            assert.equal(globalThis.DATA_ROOT, dataRoot);
            const authorization = `Basic ${Buffer.from(`${identity.username}:${identity.password}`).toString('base64')}`;
            const response = await fetch(`${origin}/version`, { headers: { authorization }, signal: AbortSignal.timeout(10_000) });
            assert.equal(response.status, 200);
            const version = await response.json();
            assert.equal(version.pkgVersion, manifest.upstream.version);
            atomicPrivateWrite(readyPath, JSON.stringify({
                schemaVersion: 1, ready: true, pid: process.pid, payloadId: manifest.payloadId,
                origin, nodeVersion: process.versions.node, sillytavernVersion: version.pkgVersion,
            }, null, 2));
            clearTimeout(startupTimer);
            console.log(`[shell] Ready at ${origin}; credentials remain in private state`);
        } catch (error) {
            console.error('[shell] Readiness verification failed:', error.message);
            process.exit(1);
        }
    });
    await import(pathToFileURL(path.join(serverRoot, 'server.js')));
}

main().catch(error => {
    console.error('[shell] Startup failed:', error.message);
    process.exit(1);
});

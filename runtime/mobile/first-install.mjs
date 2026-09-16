// SPDX-License-Identifier: AGPL-3.0-only
// APK-owned first-run installer. Never runs downloaded lifecycle scripts, git,
// npm CLI, or child Node processes (Android embeds libnode, not a node binary).
import assert from 'node:assert/strict';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';
import { atomicPrivateWrite, managedDirectory, checkPrivateFile, parseLaunch } from './policy.mjs';

const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const MAX_BYTES = 1024 * 1024 * 1024;
const MAX_FILE = 128 * 1024 * 1024;
const MAX_ENTRIES = 60000;
const HEX = /^[a-f0-9]{64}$/;
const UUID = /^\.install-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const GLOBAL = 'server/public/scripts/extensions/third-party';
const EXISTS = file => fs.existsSync(file);
function json(file, max = 32 * 1024 * 1024) {
    checkPrivateFile(file);
    assert(fs.statSync(file).size <= max, 'Metadata too large');
    return JSON.parse(fs.readFileSync(file, 'utf8'));
}
function safe(name) {
    assert(typeof name === 'string' && name.length && !/[\\:\x00-\x1f]/.test(name), 'Unsafe member name');
    assert(!name.startsWith('/') && name.split('/').every(p => p && p !== '.' && p !== '..' && p !== '.git'), 'Unsafe member path');
}
async function hashFile(file) {
    const hash = crypto.createHash('sha256');
    for await (const block of fs.createReadStream(file)) hash.update(block);
    return hash.digest('hex');
}
export async function treeInventory(root) {
    const files = {}, directories = [];
    let totalBytes = 0, count = 0;
    async function visit(dir, prefix = '') {
        for (const entry of await fsp.readdir(dir, { withFileTypes: true })) {
            const name = prefix + entry.name;
            if (name === 'payload-manifest.json') continue;
            safe(name);
            assert(++count <= MAX_ENTRIES, 'Too many installed entries');
            const full = path.join(dir, entry.name), stat = await fsp.lstat(full);
            assert(!stat.isSymbolicLink(), 'Installed symlink rejected');
            if (stat.isDirectory()) { directories.push(name); await visit(full, name + '/'); }
            else {
                assert(stat.isFile() && stat.size <= MAX_FILE, 'Unsafe or oversized installed member');
                totalBytes += stat.size;
                assert(totalBytes <= MAX_BYTES, 'Installation exceeds size limit');
                assert(!/\.(node|so(?:\.\d+)*|dll|exe|dylib)$/i.test(name) && !name.endsWith('/binding.gyp'), 'Native dependency needs an Android-specific build');
                files[name] = { size: stat.size, sha256: await hashFile(full) };
            }
        }
    }
    await visit(root);
    return { files: Object.fromEntries(Object.entries(files).sort()), directories: directories.sort(), totalBytes };
}
export async function verifyInstallation(root, expectedSha) {
    assert(HEX.test(expectedSha), 'Invalid installed manifest digest');
    assert(!fs.lstatSync(root).isSymbolicLink(), 'Installed root cannot be a symlink');
    const file = path.join(root, 'payload-manifest.json');
    const manifest = json(file);
    assert.equal(await hashFile(file), expectedSha, 'Installed manifest changed');
    const actual = await treeInventory(root);
    assert.deepEqual(actual.files, manifest.files, 'Installed content changed; repair the installation (user data is separate)');
    assert.deepEqual(actual.directories, manifest.directories, 'Installed directories changed');
    assert.equal(actual.totalBytes, manifest.totalBytes);
    return manifest;
}
async function permissions(root, writable) {
    const stat = await fsp.lstat(root);
    assert(!stat.isSymbolicLink() && (stat.isFile() || stat.isDirectory()), 'Unsafe permission target');
    if (stat.isDirectory()) {
        // Top-down when cleaning; bottom-up when sealing.
        if (writable) await fsp.chmod(root, 0o700);
        for (const name of await fsp.readdir(root)) await permissions(path.join(root, name), writable);
    }
    await fsp.chmod(root, stat.isDirectory() ? (writable ? 0o700 : 0o500) : (writable ? 0o600 : 0o400));
}
async function removeStage(root) {
    // Only a direct child with our exact UUID pattern; never follow symlinks.
    assert(UUID.test(path.basename(root)), 'Refusing non-staging cleanup');
    if (EXISTS(root)) { await permissions(root, true); await fsp.rm(root, { recursive: true }); }
}
async function response(url) {
    const allowed = new Set(['api.github.com', 'codeload.github.com']);
    for (let i = 0; i < 5; i++) {
        const parsed = new URL(url);
        assert(parsed.protocol === 'https:' && allowed.has(parsed.hostname) && !parsed.username && !parsed.password, 'Untrusted download origin');
        const res = await fetch(parsed, { headers: { 'User-Agent': 'ST-Android-First-Install', Accept: 'application/vnd.github+json' },
            redirect: 'manual', signal: AbortSignal.timeout(120_000) });
        if ([301, 302, 303, 307, 308].includes(res.status)) {
            const next = res.headers.get('location'); await res.body?.cancel();
            assert(next, 'Missing redirect location'); url = new URL(next, parsed).href; continue;
        }
        if (!res.ok) { await res.body?.cancel(); throw new Error(`GitHub download failed (HTTP ${res.status}); check network/rate limit and retry`); }
        return res;
    }
    throw new Error('Too many download redirects');
}
async function download(url, file, limit, progress) {
    const res = await response(url);
    // fetch transparently decompresses HTTP content-encoding; its wire length
    // is not the decoded stream length. The decoded byte cap still applies.
    const expected = res.headers.get('content-encoding') ? 0 : Number(res.headers.get('content-length') || 0);
    assert(expected <= limit, 'Download exceeds size bound');
    const output = fs.openSync(file, 'wx', 0o600);
    let size = 0, last = 0;
    const hash = crypto.createHash('sha256');
    try {
        for await (const block of res.body) {
            size += block.length; assert(size <= limit, 'Download exceeds size bound');
            fs.writeFileSync(output, block); hash.update(block);
            if (size - last >= 1024 * 1024) { progress(size, expected); last = size; }
        }
        assert(!expected || expected === size, 'Truncated download'); fs.fsyncSync(output);
    } finally { fs.closeSync(output); }
    progress(size, expected);
    return hash.digest('hex');
}
function repository(value) {
    assert(/^https:\/\/github\.com\/[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(value), 'Invalid repository');
    return value.slice('https://github.com/'.length);
}
async function resolve(spec, work) {
    const repo = repository(spec.repository);
    const file = path.join(work, crypto.randomUUID() + '.json');
    await download(`https://api.github.com/repos/${repo}/commits/${encodeURIComponent(spec.ref)}`, file, 8 * 1024 * 1024, () => {});
    const commit = json(file).sha;
    assert(/^[a-f0-9]{40}$/.test(commit), 'Invalid GitHub commit');
    return { ...spec, commit };
}
export async function extractSource(tar, archive, destination) {
    fs.mkdirSync(destination, { recursive: true, mode: 0o700 });
    let count = 0, total = 0, prefix;
    const seen = new Set();
    let failure;
    await tar.x({ file: archive, cwd: destination, strip: 1, strict: true,
        preservePaths: false, noMtime: true, noChmod: true,
        filter(name, entry) {
            if (failure) return false;
            try {
                const clean = name.replace(/\/$/, ''); safe(clean);
                const parts = clean.split('/');
                prefix ??= parts[0]; assert.equal(parts[0], prefix, 'Mixed archive roots');
                assert(['File', 'Directory'].includes(entry.type), 'Archive link/special member rejected');
                assert(!seen.has(clean), 'Duplicate archive member'); seen.add(clean);
                assert(++count <= MAX_ENTRIES && entry.size <= MAX_FILE, 'Archive limit exceeded');
                total += entry.size; assert(total <= MAX_BYTES, 'Expanded archive too large');
                return parts.length > 1;
            } catch (error) {
                // Throwing inside tar's event callback can escape its promise.
                // Reject every subsequent entry, then reject the awaited operation.
                failure = error;
                return false;
            }
        },
    });
    if (failure) throw failure;
}
async function fetchSource(spec, work, dest, tar, progress) {
    const file = path.join(work, crypto.randomUUID() + '.tgz');
    const archiveSha256 = await download(`https://codeload.github.com/${repository(spec.repository)}/tar.gz/${spec.commit}`, file, MAX_FILE, progress);
    await extractSource(tar, file, dest);
    return { ...spec, archiveSha256 };
}
export async function installDependencies(server, npmRoot, cache) {
    const require = createRequire(path.join(npmRoot, 'package.json'));
    const Arborist = require('@npmcli/arborist');
    const validate = require('./lib/utils/validate-lockfile.js');
    const lock = json(path.join(server, 'package-lock.json'));
    assert(lock.lockfileVersion >= 2 && lock.packages, 'A modern upstream package lock is required');
    for (const [name, pkg] of Object.entries(lock.packages)) {
        if (!name) continue;
        safe(name);
        assert(!pkg.link, 'Linked dependencies are not supported');
        if (pkg.resolved) {
            const url = new URL(pkg.resolved);
            assert(url.protocol === 'https:' && url.hostname === 'registry.npmjs.org' && !url.username && !url.password, 'Dependency must use the npm HTTPS registry');
            assert(/^sha(256|384|512)-[A-Za-z0-9+/=]+$/.test(pkg.integrity), 'Dependency has no strong integrity digest');
        }
        if (!pkg.dev) assert(!pkg.os && !pkg.cpu && !pkg.libc, 'Platform-selective dependency needs Android compatibility review');
    }
    // Same lock validation as npm ci, without invoking a subprocess or loading
    // .npmrc. Explicit options prevent downloaded postinstall/native-build code.
    const options = { path: server, cache, registry: 'https://registry.npmjs.org/',
        packageLock: true, save: false, omit: ['dev'], ignoreScripts: true,
        binLinks: false, audit: false, fund: false, engineStrict: true,
        fetchRetries: 2, fetchTimeout: 120_000, maxSockets: 8 };
    const virtual = new Arborist(options); await virtual.loadVirtual();
    const arb = new Arborist(options); await arb.buildIdealTree();
    assert.deepEqual(validate(virtual.virtualTree.inventory, arb.idealTree.inventory), [], 'Upstream package.json and lock are inconsistent');
    await arb.reify(options);
}
function verifyExtension(folder, spec) {
    const manifest = json(path.join(folder, 'manifest.json'), 65536);
    for (const name of [manifest.js, manifest.css, ...Object.values(manifest.i18n || {}), spec.licenseFile].filter(Boolean)) {
        safe(name); assert(fs.statSync(path.join(folder, name)).isFile(), `Missing plugin asset: ${spec.name}/${name}`);
    }
    assert(manifest.js, `Plugin ${spec.name} has no shipped JS entry point`);
    return { ...spec, version: manifest.version, displayName: manifest.display_name };
}

async function reclaimVersions(base, currentId) {
    const keep = new Set([currentId]);
    const previous = path.join(base, 'previous.json');
    try { const id = json(previous, 65536).payloadId; if (HEX.test(id)) keep.add(id); } catch { /* Optional rollback record. */ }
    for (const name of await fsp.readdir(base)) {
        if (!HEX.test(name) || keep.has(name)) continue;
        const directory = path.join(base, name);
        try {
            const stat = await fsp.lstat(directory);
            if (!stat.isDirectory() || stat.isSymbolicLink()) continue;
            if (json(path.join(directory, 'payload-manifest.json')).payloadId !== name) continue;
            await permissions(directory, true);
            await fsp.rm(directory, { recursive: true });
        } catch { /* Optional cleanup cannot invalidate a committed installation. */ }
    }
}

/** Caller holds the native OS lock throughout installation AND server lifetime. */
export async function prepareOnlineLaunch(input, { progress = () => {} } = {}) {
    const launch = parseLaunch(input);
    const installerRoot = launch.payloadRoot, shell = path.join(installerRoot, 'shell');
    const config = json(path.join(shell, 'first-install.json'), 65536);
    const base = managedDirectory(path.join(path.dirname(launch.stateRoot), 'st-installations'));
    const pointerPath = path.join(base, 'current.json');
    const reinstallPath = path.join(launch.stateRoot, 'reinstall-request.json');
    const reinstall = EXISTS(reinstallPath);
    if (reinstall) assert.equal(json(reinstallPath, 4096).schemaVersion, 1, 'Invalid reinstall request');
    const progressFile = path.join(launch.stateRoot, 'install-progress.json');
    const report = (phase, detail, done = 0, total = 0) => {
        atomicPrivateWrite(progressFile, JSON.stringify({ schemaVersion: 1, phase, detail, done, total }));
        progress({ phase, detail, done, total });
    };
    // After process death, the native kernel lock makes these abandoned stages
    // unambiguously ours. Never clean state, the active tree or unrelated names.
    for (const name of await fsp.readdir(base)) if (UUID.test(name)) await removeStage(path.join(base, name));
    let pointer, manifest;
    if (EXISTS(pointerPath) && !reinstall) {
        pointer = json(pointerPath, 65536);
        assert(HEX.test(pointer.payloadId), 'Invalid active installation pointer');
        report('verifying', '校验已安装的 ST 与全局插件');
        manifest = await verifyInstallation(path.join(base, pointer.payloadId), pointer.manifestSha256);
        assert.equal(pointer.installerSha256, launch.manifestSha256, '安装器已更新，请使用“重新下载本体与内置插件”；用户数据保持不变');
    } else {
        const stage = path.join(base, '.install-' + crypto.randomUUID());
        await fsp.mkdir(stage, { mode: 0o700 });
        const tree = path.join(stage, 'tree'), work = path.join(stage, 'downloads');
        await fsp.mkdir(tree); await fsp.mkdir(work);
        const require = createRequire(path.join(shell, 'npm/package.json')), tar = require('tar');
        try {
            report('downloading', '解析 ST Staging 与三个插件的最新提交');
            // Resolve all moving refs FIRST. Every subsequent download uses the
            // resolved immutable commit, which is recorded for diagnosis/licensing.
            const resolved = [];
            for (const spec of [config.server, ...config.extensions]) resolved.push(await resolve(spec, work));
            const server = path.join(tree, 'server');
            report('downloading', '下载 SillyTavern Staging');
            const upstream = await fetchSource(resolved[0], work, server, tar, (n, all) => report('downloading', '下载 SillyTavern Staging', n, all));
            const source = await treeInventory(tree);
            report('dependencies', '安装 ST 锁定的生产依赖（首次启动可能较久）');
            await installDependencies(server, path.join(shell, 'npm'), path.join(base, 'npm-cache'));
            for (const [name, expected] of Object.entries(source.files)) assert.equal(await hashFile(path.join(tree, name)), expected.sha256, 'Dependency installation modified upstream source');
            const extensions = [];
            for (const spec of resolved.slice(1)) {
                safe(spec.name);
                const dest = path.join(tree, GLOBAL, spec.name);
                assert(!EXISTS(dest), 'Bundled extension would overwrite upstream files');
                report('downloading', `下载内置全局插件：${spec.name}`);
                const receipt = await fetchSource(spec, work, dest, tar, (n, all) => report('downloading', `下载 ${spec.name}`, n, all));
                extensions.push(verifyExtension(dest, receipt));
            }
            const targetShell = path.join(tree, 'shell'); fs.mkdirSync(targetShell);
            for (const name of ['bootstrap.mjs', 'policy.mjs', 'mobile-policy.json']) fs.copyFileSync(path.join(shell, name), path.join(targetShell, name));
            fs.mkdirSync(path.join(server, 'backups'), { recursive: true });
            const version = json(path.join(server, 'package.json')).version;
            report('verifying', '校验安装内容，生成提交及文件哈希记录');
            const content = { schemaVersion: 1, upstream: { ...upstream, version }, extensions,
                installerSha256: launch.manifestSha256, ...(await treeInventory(tree)) };
            const payloadId = sha(JSON.stringify(content));
            manifest = { ...content, payloadId };
            const manifestBytes = JSON.stringify(manifest);
            atomicPrivateWrite(path.join(tree, 'payload-manifest.json'), manifestBytes);
            pointer = { schemaVersion: 1, payloadId, manifestSha256: sha(manifestBytes), installerSha256: launch.manifestSha256 };
            await permissions(tree, false); // ordinary upstream global writes fail, not an integrity attestation
            const destination = path.join(base, payloadId);
            if (EXISTS(destination)) {
                try { await verifyInstallation(destination, pointer.manifestSha256); }
                catch (error) {
                    if (!reinstall) throw error;
                    // Explicit repair can replace a damaged copy of the SAME
                    // upstream commit. Preserve it for diagnosis, never overwrite.
                    await fsp.rename(destination, path.join(base, '.damaged-' + crypto.randomUUID()));
                }
            }
            if (!EXISTS(destination)) {
                // Moving a directory between parents updates '..' and requires
                // owner write access on its root. Children stay read-only.
                await fsp.chmod(tree, 0o700);
                await fsp.rename(tree, destination);
                await fsp.chmod(destination, 0o500);
            }
            // Keep the previous pointer/tree for recovery. Do NOT touch user state.
            if (EXISTS(pointerPath)) {
                assert(!fs.lstatSync(pointerPath).isSymbolicLink(), 'Pointer file must not be a symbolic link');
                try {
                    const previous = json(pointerPath, 65536);
                    if (previous && typeof previous === 'object' && HEX.test(previous.payloadId)) {
                        if (previous.payloadId !== pointer.payloadId) {
                            atomicPrivateWrite(path.join(base, 'previous.json'), JSON.stringify(previous));
                        }
                    } else if (reinstall) {
                        const damaged = path.join(base, '.damaged-pointer-' + crypto.randomUUID());
                        try { await fsp.rename(pointerPath, damaged); } catch (_) { }
                    }
                } catch (error) {
                    if (reinstall) {
                        const damaged = path.join(base, '.damaged-pointer-' + crypto.randomUUID());
                        try { await fsp.rename(pointerPath, damaged); } catch (_) { }
                    } else {
                        throw error;
                    }
                }
            }
            // Commit last. A failed/aborted install never becomes current.
            atomicPrivateWrite(pointerPath, JSON.stringify(pointer));
            if (reinstall) fs.unlinkSync(reinstallPath);
            await reclaimVersions(base, pointer.payloadId);
        } finally { await removeStage(stage); }
    }
    const result = { ...launch, payloadRoot: path.join(base, pointer.payloadId), manifestSha256: pointer.manifestSha256 };
    const launchPath = path.join(launch.stateRoot, 'installed-launch.json');
    atomicPrivateWrite(launchPath, JSON.stringify(result));
    atomicPrivateWrite(path.join(launch.stateRoot, 'installation-info.json'), JSON.stringify({
        schemaVersion: 1, ...pointer, upstream: manifest.upstream, extensions: manifest.extensions }));
    // Attest in-process verification to avoid an immediate redundant hash pass in bootstrap
    globalThis.__ST_VERIFIED_TREE__ = { payloadRoot: path.join(base, pointer.payloadId), manifestSha256: pointer.manifestSha256, payloadId: pointer.payloadId };
    report('starting', '启动本地 ST（普通重启不检查或下载更新）');
    return { launch: result, launchPath, payloadId: pointer.payloadId };
}

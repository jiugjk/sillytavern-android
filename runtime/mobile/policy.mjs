// SPDX-License-Identifier: AGPL-3.0-only
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const forbiddenKeys = new Set(['__proto__', 'prototype', 'constructor']);
export function mergeConfig(...objects) {
    const copyValue = (value, depth) => {
        assert(depth < 64, 'Config nesting is too deep');
        if (Array.isArray(value)) return value.map(item => copyValue(item, depth + 1));
        if (value && typeof value === 'object') return merge({}, value, depth + 1);
        assert(value === null || ['string', 'boolean', 'number'].includes(typeof value), 'Unsupported config value');
        assert(typeof value !== 'number' || Number.isFinite(value), 'Config numbers must be finite');
        return value;
    };
    const merge = (target, source, depth = 0) => {
        assert(depth < 64, 'Config nesting is too deep');
        assert(source && typeof source === 'object' && !Array.isArray(source), 'Config must be an object');
        assert([null, Object.prototype].includes(Object.getPrototypeOf(source)), 'Config must contain plain objects');
        for (const [key, value] of Object.entries(source)) {
            assert(!forbiddenKeys.has(key), 'Unsafe config property');
            if (value && typeof value === 'object' && !Array.isArray(value)) {
                const previous = target[key];
                target[key] = merge(previous && typeof previous === 'object' && !Array.isArray(previous) ? previous : {}, value, depth + 1);
            } else {
                target[key] = copyValue(value, depth + 1);
            }
        }
        return target;
    };
    return objects.reduce((result, object) => merge(result, object), {});
}

export function parseLaunch(input) {
    assert(input && typeof input === 'object' && !Array.isArray(input), 'Launch descriptor must be an object');
    const keys = ['schemaVersion', 'payloadRoot', 'stateRoot', 'manifestSha256', 'port'];
    assert.deepEqual(Object.keys(input).sort(), keys.sort(), 'Unexpected or missing launch properties');
    assert.equal(input.schemaVersion, 1);
    for (const key of ['payloadRoot', 'stateRoot']) {
        assert(typeof input[key] === 'string' && path.isAbsolute(input[key]), `${key} must be absolute`);
    }
    assert(/^[a-f0-9]{64}$/.test(input.manifestSha256), 'Expected a trusted manifest SHA256');
    assert(Number.isInteger(input.port) && input.port >= 1024 && input.port <= 65535, 'Port must be an integer between 1024 and 65535');
    return { ...input };
}

export function managedDirectory(directory) {
    if (!fs.existsSync(directory)) fs.mkdirSync(directory, { recursive: true, mode: 0o700 });
    const stat = fs.lstatSync(directory);
    assert(stat.isDirectory() && !stat.isSymbolicLink(), 'Managed directory cannot be a symlink');
    return fs.realpathSync(directory);
}

export function checkPrivateFile(file) {
    try {
        const stat = fs.lstatSync(file);
        assert(stat.isFile() && !stat.isSymbolicLink(), 'Managed file cannot be a symlink or special file');
    } catch (error) { if (error.code !== 'ENOENT') throw error; }
}

export function atomicPrivateWrite(file, text) {
    checkPrivateFile(file);
    const temporary = `${file}.${crypto.randomBytes(12).toString('hex')}.tmp`;
    let fd;
    try {
        fd = fs.openSync(temporary, 'wx', 0o600);
        fs.writeFileSync(fd, text);
        fs.fsyncSync(fd);
        fs.closeSync(fd);
        fd = undefined;
        fs.renameSync(temporary, file);
    } finally {
        if (fd !== undefined) fs.closeSync(fd);
        try { fs.unlinkSync(temporary); } catch (error) { if (error.code !== 'ENOENT') throw error; }
    }
}

export function transportIdentity(stateRoot, port) {
    const file = path.join(stateRoot, 'transport.json');
    checkPrivateFile(file);
    if (!fs.existsSync(file)) {
        const identity = { schemaVersion: 1, port, username: 'st-shell', password: crypto.randomBytes(32).toString('hex') };
        atomicPrivateWrite(file, JSON.stringify(identity, null, 2));
    }
    let identity;
    try { identity = JSON.parse(fs.readFileSync(file, 'utf8')); }
    catch { throw new Error('Cannot read private transport identity; refusing to regenerate credentials'); }
    assert.equal(identity.schemaVersion, 1, 'Unknown transport identity schema');
    assert.equal(identity.port, port, 'Persistent origin mismatch; do not silently switch ports');
    assert.equal(identity.username, 'st-shell', 'Invalid transport username');
    assert(/^[a-f0-9]{64}$/.test(identity.password), 'Invalid transport credential');
    fs.chmodSync(file, 0o600);
    return identity;
}

export function applyMobilePolicy(defaults, existing, policy, identity, dataRoot) {
    assert.equal(policy.schemaVersion, 1);
    const config = mergeConfig(defaults, existing, policy.config);
    config.port = identity.port;
    config.dataRoot = dataRoot;
    config.basicAuthUser = { username: identity.username, password: identity.password };
    return config;
}

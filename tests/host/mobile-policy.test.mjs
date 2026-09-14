// SPDX-License-Identifier: AGPL-3.0-only
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { mergeConfig, parseLaunch, transportIdentity, atomicPrivateWrite, applyMobilePolicy } from '../../runtime/mobile/policy.mjs';

const policy = JSON.parse(fs.readFileSync(new URL('../../config/mobile-policy.json', import.meta.url)));
const identity = { port: 18766, username: 'st-shell', password: 'a'.repeat(64) };

test('mobile security overrides unsafe config without resetting unrelated upstream preferences', () => {
    const existing = { listen: false, basicAuthMode: false, whitelist: ['*'],
        disableCsrfProtection: true, extensions: { models: { autoDownload: true }, enabled: true },
        requestProxy: { enabled: true, url: 'socks5://my-proxy.invalid:1080' },
        browserLaunch: { enabled: true, hostname: 'desktop.invalid', port: 8123 }, customFutureSetting: ['keep'] };
    const result = applyMobilePolicy({}, existing, policy, identity, '/private/data');
    assert.equal(result.listen, true);
    assert.equal(result.basicAuthMode, true);
    assert.equal(result.disableCsrfProtection, false);
    assert.deepEqual(result.whitelist, ['127.0.0.1', '::1']);
    assert.equal(result.extensions.models.autoDownload, false);
    assert.equal(result.extensions.enabled, true);
    assert.equal(result.browserLaunch.enabled, false);
    assert.equal(result.browserLaunch.hostname, 'auto');
    assert.equal(result.browserLaunch.port, -1);
    assert.equal(result.requestProxy.url, existing.requestProxy.url);
    assert.deepEqual(result.customFutureSetting, ['keep']);
    assert.equal(result.dataRoot, '/private/data');
    assert.equal(result.allowKeysExposure, true);
    assert.equal(result.performance.lazyLoadCharacters, true);
    assert.equal(result.performance.useDiskCache, false);
    assert.equal(result.backups.chat.enabled, false);
    assert.equal(existing.disableCsrfProtection, true, 'merge must not mutate caller objects');
});

test('policy does not introduce a generation timeout or choose an API provider', () => {
    assert.equal(policy.config.generationTimeout, undefined);
    assert.equal(policy.config.requestTimeout, undefined);
    assert.equal(policy.config.enableDownloadableTokenizers, undefined, 'retain upstream tokenizer-download policy');
    assert.equal(policy.config.enableUserAccounts, undefined, 'retain upstream account preference');
});

test('prototype pollution, cycles and nonfinite nested config values are rejected', () => {
    assert.throws(() => mergeConfig(JSON.parse('{"__proto__":{"polluted":true}}')));
    assert.throws(() => mergeConfig({ array: [JSON.parse('{"constructor":{}}')] }));
    assert.throws(() => mergeConfig({ array: [Infinity] }));
    const cycle = {}; cycle.nested = cycle;
    assert.throws(() => mergeConfig(cycle), /too deep/);
    assert.equal({}.polluted, undefined);
});

test('launch descriptor only accepts absolute roots, a trusted digest and an explicit stable port', () => {
    const valid = { schemaVersion: 1, payloadRoot: '/tmp/payload', stateRoot: '/tmp/state', manifestSha256: 'a'.repeat(64), port: 8000 };
    assert.deepEqual(parseLaunch(valid), valid);
    for (const bad of [{ ...valid, port: 0 }, { ...valid, port: '8000' }, { ...valid, port: 65536 },
        { ...valid, stateRoot: './data' }, { ...valid, manifestSha256: 'fake' }, { ...valid, listen: true }]) {
        assert.throws(() => parseLaunch(bad));
    }
});

test('transport credentials persist, remain private, and a port change/corruption fails closed', () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'st-transport-test-'));
    try {
        const first = transportIdentity(directory, 18766);
        const second = transportIdentity(directory, 18766);
        assert.deepEqual(first, second);
        assert.match(first.password, /^[a-f0-9]{64}$/);
        assert.equal(fs.statSync(path.join(directory, 'transport.json')).mode & 0o777, 0o600);
        assert.throws(() => transportIdentity(directory, 18767), /origin mismatch/);
        fs.writeFileSync(path.join(directory, 'transport.json'), '{bad');
        assert.throws(() => transportIdentity(directory, 18766), /refusing to regenerate/);
        assert.equal(fs.readFileSync(path.join(directory, 'transport.json'), 'utf8'), '{bad');
    } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

test('private writes refuse a symlink rather than overwriting its target', () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'st-private-test-'));
    try {
        const target = path.join(directory, 'target');
        const link = path.join(directory, 'config.yaml');
        fs.writeFileSync(target, 'keep');
        fs.symlinkSync(target, link);
        assert.throws(() => atomicPrivateWrite(link, 'bad'), /symlink/);
        assert.equal(fs.readFileSync(target, 'utf8'), 'keep');
    } finally { fs.rmSync(directory, { recursive: true, force: true }); }
});

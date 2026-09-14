// SPDX-License-Identifier: AGPL-3.0-only
// Injected by the outer WebView shell, only into its current main-frame ST origin.
// Observes public APIs/DOM; never wraps fetch, Generate, saveChat or stopGeneration.
(function (config) {
    'use strict';
    if (window.top !== window) return false;
    const previous = window.__stAndroidActivityObserver;
    if (previous?.token === config.token) return true;
    previous?.dispose();
    let sequence = 0, streamEvents = 0, groupBusy = false, disposed = false;
    let source = null, mutation = null;
    const listeners = [];
    function send(reason, forceDetached = false) {
        if (disposed && !forceDetached) return;
        let context = null;
        try { context = window.SillyTavern?.getContext?.(); } catch { /* App still initializing. */ }
        const processor = context?.streamingProcessor;
        const value = {
            schemaVersion: 1, token: config.token, sequence: ++sequence,
            ready: !forceDetached && !!source,
            bodyBusy: !forceDetached && document.body?.dataset?.generating === 'true',
            // Keep processor ownership through the awaited saveChat operation;
            // finalization clears the processor after that operation returns.
            streamBusy: !forceDetached && !!processor && processor.isStopped !== true,
            groupBusy: !forceDetached && groupBusy,
            streamEvents, reason, visibility: document.visibilityState || 'unknown',
        };
        try { window.stShellActivity.postMessage(JSON.stringify(value)); } catch { /* Never disrupt ST. */ }
    }
    function connect() {
        if (disposed) return;
        if (!source) {
            let context;
            try { context = window.SillyTavern?.getContext?.(); } catch { return; }
            if (context?.eventSource?.on && context?.eventTypes) {
                source = context.eventSource;
                const types = context.eventTypes;
                function on(name, listener) {
                    if (typeof name !== 'string') return;
                    source.on(name, listener); listeners.push([name, listener]);
                }
                on(types.GENERATION_STARTED, () => { send('event'); }); // attempts/dry-runs do not create a busy lease
                on(types.GENERATION_ENDED, () => { send('event'); });
                on(types.GENERATION_STOPPED, () => { send('event'); });
                on(types.GROUP_WRAPPER_STARTED, () => { groupBusy = true; send('event'); });
                on(types.GROUP_WRAPPER_FINISHED, () => { groupBusy = false; send('event'); });
                on(types.STREAM_TOKEN_RECEIVED, () => { streamEvents = Math.min(Number.MAX_SAFE_INTEGER, streamEvents + 1); });
            }
        }
        if (!mutation && document.body) {
            mutation = new MutationObserver(() => { send('dom'); });
            mutation.observe(document.body, { attributes: true, attributeFilter: ['data-generating'] });
        }
        send(source ? 'sample' : 'install');
    }
    const onVisibility = () => { send('visibility'); };
    const onHide = () => { send('pagehide', true); dispose(); };
    function dispose() {
        if (disposed) return;
        disposed = true;
        clearInterval(timer);
        mutation?.disconnect();
        for (const [name, listener] of listeners) source?.removeListener?.(name, listener);
        document.removeEventListener('visibilitychange', onVisibility);
        window.removeEventListener('pagehide', onHide);
    }
    const timer = setInterval(connect, 1000);
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('pagehide', onHide);
    window.__stAndroidActivityObserver = { token: config.token, dispose };
    connect();
    return true;
})(__ST_ANDROID_CONFIG__);

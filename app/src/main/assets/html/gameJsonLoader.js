/**
 * GitHub-only JSON loader for web games (no bundled APK / ../data fallbacks).
 */
(function (global) {
    const GITHUB_ASSETS = 'https://talq2me.github.io/BaerenEd-Android-App/app/src/main/assets';
    const GITHUB_DATA = GITHUB_ASSETS + '/data';

    async function loadDataJson(fileName) {
        if (global.Android && typeof global.Android.loadJsonFile === 'function') {
            const raw = global.Android.loadJsonFile(fileName);
            if (raw == null || String(raw).trim() === '') {
                throw new Error('Failed to load ' + fileName);
            }
            return JSON.parse(raw);
        }
        const r = await fetch(`${GITHUB_DATA}/${fileName}?nocache=${Date.now()}`);
        if (!r.ok) {
            throw new Error('Failed to load ' + fileName);
        }
        return r.json();
    }

    async function loadAssetJson(assetPath) {
        if (global.Android && typeof global.Android.loadAssetJson === 'function') {
            const raw = global.Android.loadAssetJson(assetPath);
            if (raw == null || String(raw).trim() === '') {
                throw new Error('Failed to load ' + assetPath);
            }
            return JSON.parse(raw);
        }
        const r = await fetch(`${GITHUB_ASSETS}/${assetPath}?nocache=${Date.now()}`);
        if (!r.ok) {
            throw new Error('Failed to load ' + assetPath);
        }
        return r.json();
    }

    function failGameLoad(message, containerId) {
        console.error(message);
        const el = containerId ? document.getElementById(containerId) : null;
        const target = el || document.querySelector('.game-container') || document.body;
        if (target) {
            const p = document.createElement('p');
            p.style.cssText = 'color:red;font-weight:bold;padding:1em;text-align:center;';
            p.textContent = message;
            target.prepend(p);
        }
        if (global.Android && typeof global.Android.gameCompleted === 'function') {
            global.Android.gameCompleted(0, 0);
        }
    }

    global.GameJsonLoader = {
        GITHUB_ASSETS,
        GITHUB_DATA,
        loadDataJson,
        loadAssetJson,
        failGameLoad
    };
})(typeof window !== 'undefined' ? window : globalThis);

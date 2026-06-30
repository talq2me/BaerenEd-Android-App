/**
 * Shared spelling pool: same session words all day until all required tasks are done.
 * URL: ?file=spellingDragFrAM.json&pool=5&poolKey=frSpellingDrag  (summer)
 * URL: ?file=frenchWordsBM.json&pool=all&poolKey=frSpellingDrag   (school week)
 * pool=all — entire word list once per session (no weekly count updates).
 * pool=N — N consecutive words from the rotating index.
 * Index advances via af_maybe_advance_spelling_pools (not on gameCompleted).
 */
(function (global) {
    const GITHUB_DATA = 'https://talq2me.github.io/BaerenEd-Android-App/app/src/main/assets/data';

    function getConfig() {
        const p = new URLSearchParams(global.location.search);
        const file = p.get('file') || 'spellingDragFrAM.json';
        const poolParam = (p.get('pool') || '').trim().toLowerCase();
        const poolAll = poolParam === 'all';
        const poolRaw = poolAll ? 0 : parseInt(p.get('pool') || '0', 10);
        const poolSize = poolAll ? 0 : (Number.isFinite(poolRaw) && poolRaw > 0 ? poolRaw : 0);
        let poolKey = (p.get('poolKey') || '').trim();
        if (!poolKey && (poolAll || poolSize > 0)) {
            const lower = file.toLowerCase();
            if (lower.includes('en') || lower.includes('english')) {
                poolKey = 'engSpellingDrag';
            } else {
                poolKey = 'frSpellingDrag';
            }
        }
        return {
            file,
            poolSize,
            poolAll,
            poolActive: poolAll || poolSize > 0,
            poolKey
        };
    }

    function parseWords(raw) {
        const data = typeof raw === 'string' ? JSON.parse(raw) : raw;
        if (data && Array.isArray(data.words)) {
            return { words: data.words, lang: data.lang || null };
        }
        if (Array.isArray(data) && data.length && data[0] && data[0].word) {
            return { words: data, lang: null };
        }
        if (Array.isArray(data)) {
            return {
                words: data.map((w) => (typeof w === 'string' ? { word: w } : w)),
                lang: null
            };
        }
        return { words: [], lang: null };
    }

    async function loadJson(fileName) {
        if (global.Android && typeof global.Android.loadJsonFile === 'function') {
            const raw = global.Android.loadJsonFile(fileName);
            if (raw == null || String(raw).trim() === '') {
                throw new Error('Failed to load ' + fileName);
            }
            const data = typeof raw === 'string' ? JSON.parse(raw) : raw;
            const parsed = parseWords(data);
            if (!parsed.words.length) {
                throw new Error('Empty word list: ' + fileName);
            }
            return data;
        }
        const r = await fetch(`${GITHUB_DATA}/${fileName}?nocache=${Date.now()}`);
        if (!r.ok) {
            throw new Error('Failed to load ' + fileName);
        }
        const data = await r.json();
        const parsed = parseWords(data);
        if (!parsed.words.length) {
            throw new Error('Empty word list: ' + fileName);
        }
        return data;
    }

    function loadProgressIndex() {
        if (global.Android && typeof global.Android.loadProgress === 'function') {
            try {
                const idx = global.Android.loadProgress();
                if (Number.isFinite(idx) && idx >= 0) {
                    return idx;
                }
            } catch (e) { /* 0 */ }
        }
        return 0;
    }

    function buildSession(allWords, startIndex, poolSize, poolAll) {
        const len = allWords.length;
        if (!len) {
            return [];
        }
        const count = poolAll ? len : poolSize;
        if (count <= 0) {
            return [];
        }
        const session = [];
        for (let i = 0; i < count; i++) {
            session.push(allWords[(startIndex + i) % len]);
        }
        return session;
    }

    function toPlayWord(entry) {
        if (!entry) {
            return { word: '', sentence: '' };
        }
        return {
            word: entry.word || '',
            sentence: entry.sentence || '',
            syllables: entry.syllables
        };
    }

    function detectTtsLang(cfg, parsedLang) {
        const langParam = (new URLSearchParams(global.location.search).get('lang') || '').toLowerCase().trim();
        if (langParam === 'fr' || langParam === 'french') {
            return 'fr-FR';
        }
        if (langParam === 'en' || langParam === 'english') {
            return 'en-US';
        }
        if (cfg.poolKey && cfg.poolKey.toLowerCase().includes('eng')) {
            return 'en-US';
        }
        if (cfg.poolKey && cfg.poolKey.toLowerCase().includes('fr')) {
            return 'fr-FR';
        }
        if (parsedLang) {
            return String(parsedLang).toLowerCase().startsWith('en') ? 'en-US' : 'fr-FR';
        }
        const lower = cfg.file.toLowerCase();
        if (lower.includes('french') || lower.includes('fram') || lower.includes('frbm')) {
            return 'fr-FR';
        }
        if (lower.includes('english') || lower.includes('enam') || lower.includes('enbm')) {
            return 'en-US';
        }
        if (lower.includes('spellingdragfr') || lower.includes('dragfr')) {
            return 'fr-FR';
        }
        if (lower.includes('spellingdragen') || lower.includes('dragen')) {
            return 'en-US';
        }
        return 'en-US';
    }

    /**
     * @returns {{ cfg, allWords, sessionWords, startIndex, ttsLanguage }}
     */
    async function prepareSession() {
        const cfg = getConfig();
        const raw = await loadJson(cfg.file);
        const parsed = parseWords(raw);
        const allWords = parsed.words;
        let sessionWords = allWords;
        let startIndex = 0;
        if (cfg.poolActive) {
            startIndex = loadProgressIndex();
            sessionWords = buildSession(allWords, startIndex, cfg.poolSize, cfg.poolAll);
        }
        const ttsLanguage = detectTtsLang(cfg, parsed.lang);
        return { cfg, allWords, sessionWords, startIndex, ttsLanguage };
    }

    global.SpellingPool = {
        getConfig,
        parseWords,
        loadJson,
        loadProgressIndex,
        buildSession,
        toPlayWord,
        detectTtsLang,
        prepareSession,
        GITHUB_DATA
    };
})(typeof window !== 'undefined' ? window : globalThis);

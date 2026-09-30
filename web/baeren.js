/* Browser stand-in for the Android app: parent config, speech, and Supabase RPCs. */
(function (global) {
  const ASSETS = "https://talq2me.github.io/BaerenEd-Android-App/app/src/main/assets";
  const DEFAULT_RATE = 0.85;

  function cfg() {
    return {
      url: (localStorage.getItem("supabaseUrl") || "").replace(/\/$/, ""),
      key: localStorage.getItem("supabaseKey") || "",
      profile: localStorage.getItem("baerenProfile") || "AM",
      pin: localStorage.getItem("baerenPin") || ""
    };
  }

  function hasDb() {
    const c = cfg();
    return Boolean(c.url && c.key);
  }

  function pinOk() {
    return sessionStorage.getItem("baerenPinOk") === "1";
  }

  function checkPin(entered) {
    const expected = cfg().pin;
    if (!expected || entered === expected) {
      sessionStorage.setItem("baerenPinOk", "1");
      return true;
    }
    return false;
  }

  function saveSetup(url, key, pin, profile) {
    localStorage.setItem("supabaseUrl", url.replace(/\/$/, ""));
    localStorage.setItem("supabaseKey", key);
    localStorage.setItem("baerenPin", pin);
    if (profile) localStorage.setItem("baerenProfile", profile);
    sessionStorage.setItem("baerenPinOk", "1");
  }

  function setProfile(profile) {
    localStorage.setItem("baerenProfile", profile);
  }

  const resetReady = {};

  function ensureDailyReset() {
    const profile = cfg().profile;
    if (!resetReady[profile]) {
      resetReady[profile] = rpc("af_daily_reset", { p_profile: profile }).catch(function (err) {
        delete resetReady[profile];
        throw err;
      });
    }
    return resetReady[profile];
  }

  async function rpc(name, body) {
    const c = cfg();
    if (!c.url || !c.key) throw new Error("Supabase is not configured in this browser.");
    const res = await fetch(`${c.url}/rest/v1/rpc/${name}`, {
      method: "POST",
      headers: {
        apikey: c.key,
        Authorization: `Bearer ${c.key}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify(body || {})
    });
    const text = await res.text();
    if (!res.ok) throw new Error(text || res.statusText);
    if (!text) return null;
    try { return JSON.parse(text); } catch (e) { return text; }
  }

  async function loadJson(fileName) {
    const name = String(fileName || "").replace(/^.*\//, "");
    const res = await fetch(`${ASSETS}/data/${name}?nocache=${Date.now()}`);
    if (!res.ok) throw new Error("Could not load " + name);
    return res.text();
  }

  async function loadGameIndex(gameKey) {
    await ensureDailyReset();
    const data = await rpc("af_get_user_data", { p_profile: cfg().profile });
    const indices = (data && data.game_indices) || {};
    const n = parseInt(indices[gameKey], 10);
    return Number.isFinite(n) && n > 0 ? n : 0;
  }

  async function saveGameIndex(gameKey, index) {
    if (!gameKey || index < 0) return;
    await ensureDailyReset();
    await rpc("af_update_game_index", {
      p_profile: cfg().profile,
      p_game_key: gameKey,
      p_index: index
    });
  }

  async function completeTask(task) {
    await ensureDailyReset();
    const stars = await rpc("af_update_task_completion", {
      p_profile: cfg().profile,
      p_task_title: task.title,
      p_section_id: task.section || "optional",
      p_stars: task.stars == null ? null : task.stars,
      p_correct: task.correct == null ? null : task.correct,
      p_incorrect: task.incorrect == null ? null : task.incorrect,
      p_questions_answered: task.questionsAnswered == null ? null : task.questionsAnswered
    });
    return stars;
  }

  function voiceList() {
    const synth = global.speechSynthesis;
    const existing = synth.getVoices();
    if (existing.length) return Promise.resolve(existing);
    return new Promise(function (resolve) {
      let settled = false;
      const finish = function () {
        if (settled) return;
        settled = true;
        synth.removeEventListener("voiceschanged", finish);
        resolve(synth.getVoices());
      };
      synth.addEventListener("voiceschanged", finish);
      setTimeout(finish, 500);
    });
  }

  function matchVoice(voices, lang) {
    const want = String(lang || "").toLowerCase().replace("_", "-");
    const prefix = want.slice(0, 2);
    if (!prefix) return null;
    return voices.find(function (v) { return String(v.lang || "").toLowerCase().replace("_", "-") === want; })
      || voices.find(function (v) { return String(v.lang || "").toLowerCase().replace("_", "-").startsWith(prefix); })
      || null;
  }

  function speak(text, lang, rate, onEnd) {
    if (!text) { if (onEnd) onEnd(); return; }
    let done = false;
    const finish = () => {
      if (done) return;
      done = true;
      if (onEnd) onEnd();
    };
    if (!global.speechSynthesis) { finish(); return; }
    voiceList().then(function (voices) {
      if (done) return;
      const u = new SpeechSynthesisUtterance(text);
      u.lang = lang || "en-US";
      const voice = matchVoice(voices, u.lang);
      if (voice) u.voice = voice;
      u.rate = rate == null ? DEFAULT_RATE : rate;
      const backupMs = Math.min(20000, 800 + text.length * (u.rate < 0.5 ? 180 : 70));
      const timer = setTimeout(finish, backupMs);
      u.onend = () => { clearTimeout(timer); finish(); };
      u.onerror = () => { clearTimeout(timer); finish(); };
      global.speechSynthesis.cancel();
      global.speechSynthesis.speak(u);
    }).catch(finish);
  }

  function assetUrl(path) {
    if (!path) return "";
    if (/^https?:/i.test(path)) return path;
    return `${ASSETS}/${String(path).replace(/^\//, "")}`;
  }

  global.Baeren = {
    ASSETS,
    DEFAULT_RATE,
    cfg,
    hasDb,
    pinOk,
    checkPin,
    saveSetup,
    setProfile,
    rpc,
    ensureDailyReset,
    loadJson,
    loadGameIndex,
    saveGameIndex,
    completeTask,
    speak,
    assetUrl
  };
})(window);

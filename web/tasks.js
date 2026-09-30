/* Task buttons shared by the required map and the practice map. */
(function (global) {
  const QUIZ = {
    jkmath: 1, gr1MathStrategies: 1, gr3algebra: 1, gr3fractions: 1, gr3math: 1,
    gr3mixedproblems: 1, gr3wordproblems: 1, canadianMoneyEasy: 1, canadianMoneyHard: 1,
    conjugation: 1, conjugation_limparfait: 1, translation: 1, duologicalGame: 1, frenchStories: 1
  };
  const LATER = { printing: 1, tappableText: 1, storyRead: 1 };
  const SKIP = { boukili: 1, googleReadAlong: 1 };

  function kind(task) {
    if (SKIP[task.launch] || task.chromePage || task.playlistId) return "skip";
    if (task.videoSequence) {
      if (task.section === "required") return "later";
      return "skip";
    }
    if (task.webGame && task.url) return "html";
    if (QUIZ[task.launch]) return "quiz";
    if (task.launch === "spellingOCR") return "spell";
    if (LATER[task.launch]) return "later";
    return "skip";
  }

  function openTask(task, section) {
    const q = new URLSearchParams({
      title: task.title || task.launch || "Game",
      section: section,
      stars: task.stars == null ? "" : String(task.stars),
      launch: task.launch || ""
    });
    const how = kind(task);
    if (how === "html") {
      let src = task.url;
      if (task.totalQuestions) {
        src += (src.indexOf("?") >= 0 ? "&" : "?") + "totalQuestions=" + task.totalQuestions;
      }
      q.set("src", src);
      location.href = "play.html?" + q.toString();
    } else if (how === "quiz") {
      q.set("file", task.launch + ".json");
      if (task.totalQuestions) q.set("questions", String(task.totalQuestions));
      location.href = "quiz.html?" + q.toString();
    } else if (how === "spell") {
      const raw = task.url || "";
      const file = raw.indexOf("file=") >= 0
        ? raw.slice(raw.indexOf("file=") + 5).split("&")[0]
        : raw.replace(/^.*\//, "");
      q.set("file", file);
      if (task.totalQuestions) q.set("questions", String(task.totalQuestions));
      location.href = "spell.html?" + q.toString();
    }
  }

  function completionMap(rows, skipChecklist) {
    const map = {};
    (rows || []).forEach(function (row) {
      if (skipChecklist && row.is_checklist) return;
      const name = row.task_name || "";
      if (!name) return;
      const status = String(row.completion_status || "").toLowerCase();
      map[name] = status === "complete" || status === "done";
    });
    return map;
  }

  function renderSection(container, tasks, doneMap) {
    container.innerHTML = "";
    const visible = (tasks || []).filter(function (task) { return kind(task) !== "skip"; });
    if (!visible.length) {
      container.textContent = "No games for this profile today.";
      return;
    }
    const box = document.createElement("div");
    box.className = "tasks";
    visible.forEach(function (task) {
      const title = task.title || task.launch;
      const finished = doneMap[title] === true;
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "task" + (kind(task) === "later" ? " later" : "") + (finished ? " done" : "");
      btn.textContent = title;
      if (finished) {
        const note = document.createElement("small");
        note.textContent = "Done";
        btn.appendChild(note);
      } else if (kind(task) === "later") {
        const note = document.createElement("small");
        note.textContent = "Not converted yet";
        btn.appendChild(note);
      } else {
        btn.addEventListener("click", function () { openTask(task, task.section || "optional"); });
      }
      box.appendChild(btn);
    });
    container.appendChild(box);
  }

  function parsePokemonFile(filename) {
    if (!String(filename).toLowerCase().endsWith(".png")) return null;
    let base = filename.slice(0, -4);
    const shiny = base.endsWith("-s");
    if (shiny) base = base.slice(0, -2);
    const parts = base.split("-");
    if (parts.length < 2) return null;
    const prefix = parseInt(parts[0], 10);
    const pokenum = parseInt(parts[1], 10);
    if (!Number.isFinite(prefix) || !Number.isFinite(pokenum)) return null;
    return { prefix: prefix, pokenum: pokenum, shiny: shiny, filename: filename };
  }

  async function loadPokemonManifest() {
    const res = await fetch(Baeren.assetUrl("images/pokeSprites/sprites/pokemon/pokedex_manifest.json"));
    if (!res.ok) throw new Error("Could not load the pokedex.");
    const files = await res.json();
    return files.map(parsePokemonFile).filter(Boolean);
  }

  function pickAtPrefix(list, prefix) {
    const matches = list.filter(function (poke) { return poke.prefix === prefix; });
    return matches.find(function (poke) { return !poke.shiny; }) || matches[0] || null;
  }

  global.Tasks = {
    kind: kind,
    openTask: openTask,
    completionMap: completionMap,
    renderSection: renderSection,
    loadPokemonManifest: loadPokemonManifest,
    pickAtPrefix: pickAtPrefix
  };
})(window);

/* Battle sequence: all required tasks done, then attacks, then unlock one Pokemon. */
(function (global) {
  let token = 0;
  let running = false;

  function wait(ms) {
    return new Promise(function (resolve) { setTimeout(resolve, ms); });
  }

  function rand(min, max) {
    return min + Math.floor(Math.random() * (max - min + 1));
  }

  function hpColor(pct) {
    if (pct >= 76) return "#2ecc71";
    if (pct >= 51) return "#ffd700";
    return "#e74c3c";
  }

  function setBar(id, pct) {
    const fill = document.getElementById(id);
    if (!fill) return;
    fill.style.width = Math.max(0, Math.min(100, pct)) + "%";
    fill.style.background = hpColor(pct);
  }

  function showMessage(text) {
    const el = document.getElementById("battleMsg");
    if (el) el.textContent = text || "";
  }

  function sprite(who) {
    const card = document.querySelector('.fighter[data-who="' + who + '"] img');
    return card;
  }

  async function move(img, transform, ms) {
    if (!img) { await wait(ms); return; }
    img.style.transition = "transform " + ms + "ms, opacity " + ms + "ms, filter " + ms + "ms";
    img.style.transform = transform;
    await wait(ms);
  }

  async function flash(img) {
    if (!img) { await wait(1000); return; }
    const steps = [0.3, 1, 0.3, 1];
    for (let i = 0; i < steps.length; i++) {
      img.style.transition = "opacity 250ms";
      img.style.opacity = String(steps[i]);
      await wait(250);
    }
  }

  async function defeat(img) {
    const shakes = ["translateX(-20px)", "translateX(20px)", "translateX(-20px)", "translateX(20px)", "none"];
    for (let i = 0; i < shakes.length; i++) {
      await move(img, shakes[i], 300);
    }
    if (img) {
      img.style.transition = "transform 2s, opacity 2s, filter 2s";
      img.style.transform = "translateY(100px) rotate(180deg)";
      img.style.opacity = "0.3";
      img.style.filter = "grayscale(1)";
    }
    await wait(2000);
  }

  function resetSprite(img) {
    if (!img) return;
    img.style.transition = "none";
    img.style.transform = "none";
    img.style.opacity = "1";
    img.style.filter = "none";
  }

  async function attackRound(state, round, alive) {
    showMessage("You attack!");
    const player = sprite("player");
    const boss = sprite("boss");
    await move(player, "translateX(50px) scale(1.2)", 1000);
    if (!alive()) return "stop";
    move(player, "none", 1000);
    const bossDamage = 12 + rand(0, 8);
    state.bossHp = Math.max(0, state.bossHp - bossDamage);
    state.bossPower = Math.max(0, state.bossPower - bossDamage);
    setBar("bossHp", state.bossHp);
    setBar("bossPower", state.bossPower);
    await wait(1000);
    if (!alive()) return "stop";
    await flash(boss);
    if (!alive()) return "stop";
    if (state.bossHp <= 0) return "down";
    await wait(2000);
    if (!alive()) return "stop";
    showMessage("Boss attacks!");
    await move(boss, "translateX(-50px) scale(1.2)", 1000);
    if (!alive()) return "stop";
    move(boss, "none", 1000);
    const playerDamage = Math.max(1, bossDamage - rand(1, 3));
    state.playerHp = Math.max(0, state.playerHp - playerDamage);
    state.playerPower = Math.max(0, state.playerPower - playerDamage);
    setBar("playerHp", state.playerHp);
    setBar("playerPower", state.playerPower);
    await wait(1000);
    if (!alive()) return "stop";
    await flash(player);
    if (round >= 5) return "final";
    await wait(1500);
    return "next";
  }

  async function finalRound(state, alive) {
    showMessage("You attack!");
    const player = sprite("player");
    const boss = sprite("boss");
    await move(player, "translateX(50px) scale(1.2)", 1000);
    if (!alive()) return;
    move(player, "none", 1000);
    const bossDamage = 12 + rand(0, 8);
    state.bossHp = Math.max(0, state.bossHp - bossDamage);
    state.bossPower = Math.max(0, state.bossPower - bossDamage);
    setBar("bossHp", state.bossHp);
    setBar("bossPower", state.bossPower);
    await wait(1000);
    if (!alive()) return;
    await flash(boss);
  }

  function showVictory(filename, name) {
    const overlay = document.getElementById("victory");
    const img = document.getElementById("victoryImg");
    const label = document.getElementById("victoryName");
    if (img && filename) img.src = Baeren.assetUrl("images/pokeSprites/sprites/pokemon/" + filename);
    if (label) label.textContent = "You caught " + name + "! Added to your Pokedex!";
    if (overlay) overlay.hidden = false;
  }

  async function start() {
    if (running) return;
    const info = global.hubBattle || {};
    const btn = document.getElementById("battleBtn");
    if (!info.total || info.done < info.total) {
      showMessage("Finish the Required games to battle!");
      return;
    }
    running = true;
    const mine = ++token;
    const alive = function () { return mine === token; };
    if (btn) {
      btn.disabled = true;
      btn.textContent = "Battling...";
    }
    try {
      await Baeren.rpc("af_update_berries_banked", {
        p_profile: Baeren.cfg().profile,
        p_berries_earned: 0,
        p_banked_mins: null
      });
    } catch (err) {
      running = false;
      if (btn) {
        btn.disabled = false;
        btn.textContent = "Battle";
      }
      showMessage(err.message || String(err));
      return;
    }
    const counts = document.getElementById("counts");
    if (counts) counts.textContent = counts.textContent.replace(/\d+\s*\u{1F34D}/, "0 \u{1F34D}");

    try {
    const state = { playerHp: 100, bossHp: 100, playerPower: 100, bossPower: 100 };
    setBar("playerHp", 100);
    setBar("bossHp", 100);
    setBar("playerPower", 100);
    setBar("bossPower", 100);
    resetSprite(sprite("player"));
    resetSprite(sprite("boss"));
    showMessage("Battle begins!");
    await wait(1500);
    if (!alive()) return;

    let outcome = "next";
    for (let round = 1; round <= 5 && outcome === "next"; round++) {
      outcome = await attackRound(state, round, alive);
      if (!alive()) return;
    }
    if (outcome === "stop") return;
    if (outcome !== "down") {
      await finalRound(state, alive);
      if (!alive()) return;
    }
    showMessage((info.bossName || "Boss") + " was defeated!");
    await wait(2000);
    if (!alive()) return;
    await defeat(sprite("boss"));
    if (!alive()) return;
    showMessage("Victory!");
    setBar("playerPower", 0);
    await Baeren.rpc("af_web_finish_battle", {
      p_profile: Baeren.cfg().profile,
      p_practice_done: Number(info.practiceDone || 0)
    });
    info.done = 0;
    info.total = Number(info.practiceFill || 16);
    await wait(2000);
    if (!alive()) return;
    showMessage("");
    const next = Number(info.unlocked || 0) + 1;
    await Baeren.rpc("af_update_pokemon_unlocked", {
      p_profile: Baeren.cfg().profile,
      p_pokemon_unlocked: next
    });
    if (!alive()) return;
    info.unlocked = next;
    if (btn) btn.textContent = "Battle";
    showVictory(info.bossFile, info.bossName || "Pokemon");
    } finally {
      if (mine === token) running = false;
    }
  }

  function cancel() {
    token++;
    running = false;
    showMessage("");
    const btn = document.getElementById("battleBtn");
    if (btn) btn.textContent = "Battle";
  }

  global.Battle = { start: start, cancel: cancel };
})(window);

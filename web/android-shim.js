/* Defines window.Android for existing HTML games opened from web/play.html. */
(function () {
  const task = window.BAEREN_TASK || {};
  let progress = Number(window.__baerenProgress) || 0;

  function notifyFinished() {
    if (typeof window.onTTSFinished === "function") window.onTTSFinished();
  }

  function taskTitle() {
    return task.title || task.launch || "Game";
  }

  function videoFile(dataUrl) {
    const comma = dataUrl.indexOf(",");
    if (comma < 0) throw new Error("Invalid video data");
    const meta = dataUrl.slice("data:".length, comma);
    const contentType = (meta.split(";")[0] || "video/webm").trim() || "video/webm";
    const ext = contentType.indexOf("mp4") >= 0 ? "mp4" : (contentType.indexOf("quicktime") >= 0 ? "mov" : "webm");
    const bin = atob(dataUrl.slice(comma + 1));
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return { contentType: contentType, ext: ext, bytes: bytes };
  }

  function uploadChoreVideo(profile, taskKey, dataUrl) {
    const c = Baeren.cfg();
    if (!c.url || !c.key) return Promise.reject(new Error("Supabase is not configured in this browser."));
    const file = videoFile(dataUrl);
    const safeProfile = String(profile || "").replace(/[^A-Za-z0-9_-]/g, "_");
    const safeTask = String(taskKey || "").replace(/[^A-Za-z0-9_.-]/g, "_");
    const objectPath = safeProfile + "/" + safeTask + "." + file.ext;
    return fetch(c.url + "/storage/v1/object/chore-videos/" + objectPath, {
      method: "PUT",
      headers: {
        apikey: c.key,
        Authorization: "Bearer " + c.key,
        "Content-Type": file.contentType,
        "x-upsert": "true"
      },
      body: file.bytes
    }).then(function (res) {
      if (res.ok) return "storage:chore-videos/" + objectPath;
      return res.text().then(function (raw) {
        throw new Error("Storage upload failed: " + res.status + " " + raw);
      });
    });
  }

  window.Android = {
    __baerenShim: true,
    readText: function (text, lang, rate) {
      const parsed = parseFloat(rate);
      const useRate = Number.isFinite(parsed) ? parsed : Baeren.DEFAULT_RATE;
      const locale = String(lang || "").toLowerCase().startsWith("fr") ? "fr-FR" : (lang || "en-US");
      Baeren.speak(text, locale, useRate, notifyFinished);
    },
    gameCompleted: function (correct, incorrect, finalIndex) {
      const idx = finalIndex == null ? -1 : Number(finalIndex);
      const jobs = [];
      if (Baeren.hasDb()) {
        jobs.push(Baeren.completeTask({
          title: taskTitle(),
          section: task.section || "optional",
          stars: task.stars == null || task.stars === "" ? null : Number(task.stars),
          correct: Number(correct) || 0,
          incorrect: Number(incorrect) || 0,
          questionsAnswered: (Number(correct) || 0) + (Number(incorrect) || 0)
        }));
        if (!task.freeze && task.launch && idx >= 0) jobs.push(Baeren.saveGameIndex(task.launch, idx));
      }
      Promise.all(jobs).then(function () {
        if (window.parent && window.parent !== window) {
          window.parent.postMessage({ type: "baerenDone", title: taskTitle() }, "*");
        }
      }).catch(function (err) {
        alert(err.message || String(err));
      });
    },
    saveProgress: function (index) {
      progress = Number(index) || 0;
      if (task.launch) {
        Baeren.saveGameIndex(task.launch, progress).catch(function (err) {
          console.error(err);
        });
      }
    },
    loadProgress: function () {
      return progress;
    },
    loadJsonFile: function (fileName) {
      try {
        const xhr = new XMLHttpRequest();
        xhr.open("GET", Baeren.ASSETS + "/data/" + String(fileName).replace(/^.*\//, "") + "?nocache=" + Date.now(), false);
        xhr.send();
        return xhr.status >= 200 && xhr.status < 300 ? xhr.responseText : "";
      } catch (e) {
        return "";
      }
    },
    getConfigJson: function () { return "{}"; },
    getCurrentProfile: function () { return Baeren.cfg().profile; },
    getIconConfig: function () { return '{"starsIcon":"🍍","coinsIcon":"🪙"}'; },
    closeGame: function () {
      if (window.parent && window.parent !== window) {
        window.parent.postMessage({ type: "baerenClose" }, "*");
      }
    },
    openCamera: function (successName, errorName) {
      const ok = window[successName];
      const fail = window[errorName];
      const report = function (msg) {
        if (typeof fail === "function") fail(msg);
      };
      if (document.getElementById("baerenCam")) return;
      if (!navigator.mediaDevices || typeof navigator.mediaDevices.getUserMedia !== "function") {
        report("Camera is not available in this browser.");
        return;
      }
      navigator.mediaDevices.getUserMedia({
        audio: false,
        video: { facingMode: { ideal: "environment" } }
      }).then(function (stream) {
        const wrap = document.createElement("div");
        wrap.id = "baerenCam";
        wrap.style.cssText = "position:fixed;inset:0;z-index:99999;background:rgba(0,0,0,.75);display:flex;flex-direction:column;align-items:center;justify-content:center;gap:16px;padding:16px;";
        const video = document.createElement("video");
        video.autoplay = true;
        video.playsInline = true;
        video.muted = true;
        video.srcObject = stream;
        video.style.cssText = "max-width:92vw;max-height:70vh;background:#000;border-radius:12px;";
        const row = document.createElement("div");
        row.style.cssText = "display:flex;gap:12px;";
        function button(label) {
          const btn = document.createElement("button");
          btn.type = "button";
          btn.textContent = label;
          btn.style.cssText = "font-size:20px;padding:12px 20px;border:0;border-radius:10px;background:#fff;";
          return btn;
        }
        const snap = button("Take photo");
        const cancel = button("Cancel");
        function close() {
          stream.getTracks().forEach(function (track) { track.stop(); });
          if (wrap.parentNode) wrap.parentNode.removeChild(wrap);
        }
        snap.addEventListener("click", function () {
          const canvas = document.createElement("canvas");
          canvas.width = video.videoWidth || 640;
          canvas.height = video.videoHeight || 480;
          canvas.getContext("2d").drawImage(video, 0, 0, canvas.width, canvas.height);
          const dataUrl = canvas.toDataURL("image/jpeg", 0.9);
          close();
          if (typeof ok === "function") ok(dataUrl);
        });
        cancel.addEventListener("click", function () {
          close();
          report("Camera cancelled");
        });
        row.appendChild(snap);
        row.appendChild(cancel);
        wrap.appendChild(video);
        wrap.appendChild(row);
        document.body.appendChild(wrap);
      }).catch(function (err) {
        report((err && (err.name || err.message)) ? (err.name + ": " + err.message) : "Could not access the camera");
      });
    },
    uploadImageToSupabase: function (jsonData, successName, errorName) {
      const ok = window[successName];
      const fail = window[errorName];
      const report = function (err) {
        if (typeof fail === "function") fail(err && err.message ? err.message : String(err || "Upload failed"));
      };
      let data;
      try {
        data = JSON.parse(jsonData);
      } catch (e) {
        report(e);
        return;
      }
      const image = String(data.image || "");
      const isVideo = image.indexOf("data:video") === 0;
      const stored = isVideo ? "" : (image.indexOf("data:audio") === 0
        ? image
        : (image.indexOf("data:") === 0 && image.indexOf(",") >= 0
          ? image.slice(image.indexOf(",") + 1)
          : image));
      const c = Baeren.cfg();
      Baeren.rpc("af_cleanup_old_chore_videos", { p_days: 7 }).then(function (cleaned) {
        const paths = cleaned && cleaned.storage_paths;
        if (!c.url || !c.key || !paths || !paths.length) return;
        return Promise.all(paths.map(function (name) {
          if (!name) return Promise.resolve();
          return fetch(c.url + "/storage/v1/object/chore-videos/" + encodeURI(name), {
            method: "DELETE",
            headers: { apikey: c.key, Authorization: "Bearer " + c.key }
          }).catch(function (err) { console.warn(err); });
        }));
      }).catch(function (err) {
        console.warn(err);
      }).then(function () {
        if (!isVideo) return stored;
        return uploadChoreVideo(data.profile, data.task, image);
      }).then(function (payload) {
        return Baeren.rpc("af_upsert_image_upload", {
          p_profile: data.profile,
          p_task: data.task,
          p_image: payload
        });
      }).then(function () {
        if (typeof ok === "function") ok();
      }).catch(report);
    }
  };
})();

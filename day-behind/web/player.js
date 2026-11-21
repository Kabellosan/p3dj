// P3 DJ player: plays the DR show, and hands every music gap to Spotify.
const REDIRECT = location.origin + "/";
const SCOPES = "streaming user-read-email user-read-private user-library-read playlist-read-private user-modify-playback-state user-read-playback-state";
const FADE_MS = 1500;
const $ = (id) => document.getElementById(id);
const store = {
  get: (k) => { try { return localStorage.getItem(k); } catch { return null; } },
  set: (k, v) => { try { localStorage.setItem(k, v); } catch {} },
  del: (k) => { try { localStorage.removeItem(k); } catch {} },
};

// ---------- Spotify auth (PKCE, no server secret needed) ----------
const b64url = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
async function login() {
  const clientId = $("clientId").value.trim();
  if (!clientId) return alertBox("Paste your Spotify Client ID first.");
  store.set("clientId", clientId);
  const verifier = b64url(crypto.getRandomValues(new Uint8Array(48)));
  store.set("verifier", verifier);
  const challenge = b64url(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)));
  const q = new URLSearchParams({ client_id: clientId, response_type: "code", redirect_uri: REDIRECT,
    scope: SCOPES, code_challenge_method: "S256", code_challenge: challenge });
  location.href = "https://accounts.spotify.com/authorize?" + q;
}
async function tokenRequest(params) {
  const r = await fetch("https://accounts.spotify.com/api/token", { method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ client_id: store.get("clientId"), ...params }) });
  if (!r.ok) throw new Error("Spotify login failed: " + (await r.text()));
  const t = await r.json();
  store.set("access", t.access_token);
  if (t.refresh_token) store.set("refresh", t.refresh_token);
  store.set("expires", String(Date.now() + (t.expires_in - 120) * 1000));
}
async function token() {
  if (Date.now() > Number(store.get("expires") || 0) && store.get("refresh")) {
    await tokenRequest({ grant_type: "refresh_token", refresh_token: store.get("refresh") });
  }
  return store.get("access");
}
async function api(path, opts = {}) {
  const r = await fetch("https://api.spotify.com/v1" + path, { ...opts,
    headers: { Authorization: "Bearer " + (await token()), "Content-Type": "application/json", ...(opts.headers || {}) } });
  if (r.status === 204) return null;
  if (!r.ok) throw new Error(`Spotify ${path}: ${r.status} ${await r.text()}`);
  return r.headers.get("content-type")?.includes("json") ? r.json() : null;
}
function alertBox(msg) { $("deviceStatus").textContent = msg; console.warn(msg); }

// ---------- Music pool ----------
async function loadPool(link) {
  const uris = [];
  const m = link.match(/playlist[/:]([A-Za-z0-9]+)/);
  let next = m ? `/playlists/${m[1]}/tracks?limit=100&fields=next,items(track(uri,is_local))` : "/me/tracks?limit=50";
  while (next && uris.length < 1000) {
    const page = await api(next.replace("https://api.spotify.com/v1", ""));
    for (const it of page.items) if (it.track && !it.track.is_local) uris.push(it.track.uri);
    next = page.next;
  }
  for (let i = uris.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [uris[i], uris[j]] = [uris[j], uris[i]]; }
  return uris;
}

// ---------- The show ----------
const show = $("show");
let sp = null, deviceId = null, episode = null, pool = [], poolIdx = 0;
let mode = "show", gapIdx = 0, songUri = null, songStarted = 0, busy = false;

const fmt = (s) => `${Math.floor(s / 3600)}:${String(Math.floor(s / 60) % 60).padStart(2, "0")}:${String(Math.floor(s % 60)).padStart(2, "0")}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function fade(set, from, to, ms = FADE_MS) {
  const steps = 15;
  for (let i = 1; i <= steps; i++) { set(from + (to - from) * (i / steps)); await sleep(ms / steps); }
}

function nextGapIndex(t) { const i = episode.gaps.findIndex((g) => g.end > t); return i < 0 ? episode.gaps.length : i; }

async function startSong() {
  if (busy) return; busy = true;
  mode = "song";
  await fade((v) => (show.volume = v), show.volume, 0);
  show.pause();
  songUri = pool[poolIdx++ % pool.length];
  await sp.setVolume(0);
  await api(`/me/player/play?device_id=${deviceId}`, { method: "PUT", body: JSON.stringify({ uris: [songUri] }) });
  songStarted = Date.now();
  fade((v) => sp.setVolume(v), 0, 1);
  $("mode").textContent = "Your music";
  busy = false;
}

async function endSong() {
  if (busy || mode !== "song") return; busy = true;
  await fade((v) => sp.setVolume(v), 1, 0, 1000);
  await sp.pause();
  const g = episode.gaps[gapIdx];
  if (g) show.currentTime = g.end;
  gapIdx++;
  mode = "show";
  show.volume = 0;
  await show.play();
  await fade((v) => (show.volume = v), 0, 1, 800);
  $("mode").textContent = "On air";
  busy = false;
}

function tick() {
  if (!episode) return;
  const t = show.currentTime;
  const g = episode.gaps[gapIdx];
  if (mode === "show" && !show.paused && g && t >= g.start - FADE_MS / 1000) startSong();
  $("pos").style.left = (100 * t / episode.duration) + "%";
  const left = episode.gaps.length - gapIdx;
  $("time").textContent = `${fmt(t)} / ${fmt(episode.duration)} · ${left} song breaks left`;
}
setInterval(tick, 250);

async function watchSpotify(state) {
  if (!state || mode !== "song" || busy || Date.now() - songStarted < 3000) return;
  const cur = state.track_window.current_track;
  $("now").textContent = cur ? `${cur.name} · ${cur.artists.map((a) => a.name).join(", ")}` : "—";
  const finished = state.paused && state.position === 0;
  const nearEnd = cur && state.duration && state.position > state.duration - 1200;
  if (finished || nearEnd || (cur && cur.uri !== songUri && !cur.linked_from?.uri)) endSong();
}
setInterval(async () => { if (sp && mode === "song") watchSpotify(await sp.getCurrentState()); }, 1000);

function drawBar() {
  const bar = $("bar");
  bar.querySelectorAll(".gap").forEach((n) => n.remove());
  for (const g of episode.gaps) {
    const d = document.createElement("div");
    d.className = "gap";
    d.style.left = (100 * g.start / episode.duration) + "%";
    d.style.width = (100 * (g.end - g.start) / episode.duration) + "%";
    bar.appendChild(d);
  }
}

async function seekTo(t) {
  if (mode === "song") { await fade((v) => sp.setVolume(v), 1, 0, 300); await sp.pause(); mode = "show"; }
  gapIdx = nextGapIndex(t);
  const g = episode.gaps[gapIdx];
  show.currentTime = g && t > g.start ? g.start : t;
  show.volume = 1; show.play();
  $("mode").textContent = "On air"; $("now").textContent = "—";
}

// ---------- Wiring ----------
async function init() {
  const params = new URLSearchParams(location.search);
  if (params.get("code")) {
    await tokenRequest({ grant_type: "authorization_code", code: params.get("code"),
      redirect_uri: REDIRECT, code_verifier: store.get("verifier") });
    history.replaceState(null, "", "/");
  }
  $("clientId").value = store.get("clientId") || "";
  $("playlist").value = store.get("playlist") || "";
  $("loginBtn").onclick = login;
  if (!store.get("access")) return;

  $("login").classList.add("hidden");
  $("setup").classList.remove("hidden");
  const eps = await (await fetch("/api/episodes")).json();
  if (!eps.length) alertBox("No episodes yet: run `python -m p3dj daily <series URL>` first.");
  $("episode").innerHTML = eps.map((e, i) => `<option value="${i}">${e.id} (${e.gaps.length} songs)</option>`).join("");

  window.onSpotifyWebPlaybackSDKReady = () => {
    sp = new Spotify.Player({ name: "P3 DJ", getOAuthToken: async (cb) => cb(await token()), volume: 1 });
    sp.addListener("ready", ({ device_id }) => { deviceId = device_id; alertBox("Player ready."); });
    sp.addListener("not_ready", () => alertBox("Spotify player went offline."));
    sp.addListener("authentication_error", () => { store.del("access"); store.del("refresh"); alertBox("Spotify login expired. Reload to connect again."); });
    sp.addListener("account_error", () => alertBox("Spotify says this account can't stream here (Premium is needed)."));
    sp.addListener("player_state_changed", watchSpotify);
    sp.connect();
  };
  if (window.Spotify) window.onSpotifyWebPlaybackSDKReady();

  $("startBtn").onclick = async () => {
    if (!deviceId) return alertBox("Still connecting to Spotify, try again in a moment.");
    sp.activateElement?.();
    store.set("playlist", $("playlist").value.trim());
    alertBox("Loading your music…");
    pool = await loadPool($("playlist").value.trim());
    if (!pool.length) return alertBox("That playlist has no playable songs.");
    episode = eps[Number($("episode").value)];
    show.src = episode.audio;
    const saved = Number(store.get("pos:" + episode.id) || 0);
    $("setup").classList.add("hidden");
    $("player").classList.remove("hidden");
    drawBar();
    await seekTo(saved);
  };
  setInterval(() => { if (episode && mode === "show") store.set("pos:" + episode.id, String(show.currentTime)); }, 5000);

  $("playPause").onclick = async () => {
    if (mode === "song") { await sp.togglePlay(); }
    else if (show.paused) show.play(); else show.pause();
    const paused = mode === "song" ? (await sp.getCurrentState())?.paused : show.paused;
    $("playPause").textContent = paused ? "Play" : "Pause";
  };
  $("skip").onclick = () => (mode === "song" ? endSong() : null);
  $("back").onclick = () => seekTo(Math.max(0, (mode === "song" ? episode.gaps[gapIdx].start : show.currentTime) - 15));
  $("bar").onclick = (e) => { const r = e.currentTarget.getBoundingClientRect(); seekTo(episode.duration * (e.clientX - r.left) / r.width); };
}
init().catch((e) => alertBox(e.message));

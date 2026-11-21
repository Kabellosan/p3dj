// P3 DJ: listen to P3 live, skip a song to Spotify, come back when the hosts do.
const CFG = window.P3DJ_CONFIG;
const REDIRECT = location.origin + location.pathname;
const SCOPES = "user-library-read playlist-read-private user-modify-playback-state user-read-playback-state";
const $ = (id) => document.getElementById(id);
const store = {
  get: (k) => { try { return localStorage.getItem(k); } catch { return null; } },
  set: (k, v) => { try { localStorage.setItem(k, v); } catch {} },
  del: (k) => { try { localStorage.removeItem(k); } catch {} },
};
function log(msg) {
  const line = new Date().toLocaleTimeString() + "  " + msg;
  console.log(line);
  const el = $("log"); el.textContent = (line + "\n" + el.textContent).slice(0, 3000);
}
const status = (m) => ($("status").textContent = m);

// ---------- Spotify login (PKCE: no server, no secret) ----------
const b64url = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
async function login() {
  const clientId = $("clientId").value.trim();
  if (!clientId) return alert("Paste your Spotify Client ID first.");
  store.set("clientId", clientId);
  store.set("playlist", $("playlist").value.trim());
  const verifier = b64url(crypto.getRandomValues(new Uint8Array(48)));
  store.set("verifier", verifier);
  const challenge = b64url(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)));
  location.href = "https://accounts.spotify.com/authorize?" + new URLSearchParams({
    client_id: clientId, response_type: "code", redirect_uri: REDIRECT, scope: SCOPES,
    code_challenge_method: "S256", code_challenge: challenge });
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
    headers: { Authorization: "Bearer " + (await token()), "Content-Type": "application/json" } });
  if (r.status === 204 || r.status === 202) return null;
  const body = await r.text();
  if (!r.ok) throw new Error(`Spotify ${r.status}: ${body.slice(0, 160)}`);
  try { return JSON.parse(body); } catch { return null; }
}

// ---------- Your music ----------
let pool = [], poolIdx = 0;
async function loadPool() {
  const link = store.get("playlist") || "";
  const m = link.match(/playlist[/:]([A-Za-z0-9]+)/);
  let next = m ? `/playlists/${m[1]}/tracks?limit=100&fields=next,items(track(uri,is_local))` : "/me/tracks?limit=50";
  const uris = [];
  while (next && uris.length < 1000) {
    const page = await api(next.replace("https://api.spotify.com/v1", ""));
    for (const it of page.items) if (it.track && !it.track.is_local) uris.push(it.track.uri);
    next = page.next;
  }
  for (let i = uris.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [uris[i], uris[j]] = [uris[j], uris[i]]; }
  pool = uris;
  log(`Loaded ${pool.length} songs from ${m ? "your playlist" : "your Liked Songs"}.`);
}
function nextSongs(n = 10) {
  const out = [];
  for (let i = 0; i < Math.min(n, pool.length); i++) out.push(pool[poolIdx++ % pool.length]);
  return out;
}
async function phoneDevice() {
  const { devices } = await api("/me/player/devices");
  return devices.find((d) => d.is_active && !d.is_restricted)
      || devices.find((d) => d.type === "Smartphone" && !d.is_restricted)
      || devices.find((d) => !d.is_restricted);
}

// ---------- What P3 is playing ----------
let np = { song: false, title: "" };
async function pollNowPlaying() {
  try {
    const r = await fetch(CFG.nowPlayingUrl, { cache: "no-store" });
    const d = await r.json();
    if (d.error) throw new Error(d.error);
    const changed = d.title !== np.title;
    np = d;
    if (changed) onNowPlayingChanged();
  } catch (e) {
    status("Can't see what P3 is playing right now.");
    console.warn(e);
  }
}

// ---------- The radio ----------
const p3 = $("p3");
let mode = "off";          // off | p3 | spotify
let skippedTitle = null;
let pollTimer = null;

function setPoll(ms) { clearInterval(pollTimer); pollTimer = setInterval(pollNowPlaying, ms); pollNowPlaying(); }

function render() {
  const btn = $("skip");
  btn.classList.toggle("back", mode === "spotify");
  $("source").classList.toggle("spotify", mode === "spotify");
  if (mode === "off") { btn.disabled = false; btn.textContent = "Play P3"; $("source").textContent = "P3 live"; }
  else if (mode === "p3") {
    $("source").textContent = "P3 live";
    $("now").textContent = np.song ? `${np.artist} · ${np.track || ""}` : "Hosts talking";
    btn.disabled = !np.song;
    btn.textContent = "Skip song";
  } else {
    $("source").textContent = "Your Spotify";
    $("now").textContent = `Skipping: ${skippedTitle.replace(/^\/\s*/, "")}`;
    btn.disabled = false; btn.textContent = "Back to P3 now";
  }
  if ("mediaSession" in navigator) {
    navigator.mediaSession.metadata = new MediaMetadata({
      title: mode === "p3" && np.song ? `${np.artist} - ${np.track}` : "P3 live",
      artist: mode === "p3" && np.song ? "Press next to skip to your music" : "P3 DJ",
      artwork: [{ src: "icon.svg", sizes: "512x512", type: "image/svg+xml" }],
    });
  }
}

async function playP3() {
  p3.src = CFG.streamUrl + "?t=" + Date.now();   // fresh connection = live edge, no backlog
  try { await p3.play(); mode = "p3"; setPoll(5000); status(""); }
  catch (e) { mode = "off"; status("Tap Play P3 to start."); log("Couldn't start P3 automatically: " + e.message); }
  render();
}

async function skip() {
  if (mode !== "p3" || !np.song) return;
  if (!pool.length) await loadPool();
  skippedTitle = np.title;
  mode = "spotify"; render();
  try {
    const dev = await phoneDevice();
    if (!dev) throw new Error("Spotify isn't open on any device. Open the Spotify app once, then try again.");
    p3.pause();
    await api(`/me/player/play?device_id=${dev.id}`, { method: "PUT", body: JSON.stringify({ uris: nextSongs() }) });
    log(`Skipped "${skippedTitle.replace(/^\/\s*/, "")}" → Spotify on ${dev.name}`);
    setPoll(3000);
  } catch (e) {
    log(e.message); status(e.message);
    mode = "p3"; if (p3.paused) p3.play().catch(() => {}); render();
  }
}

async function backToP3(reason) {
  if (mode !== "spotify") return;
  log(reason);
  try { await api("/me/player/pause", { method: "PUT" }); } catch (e) { log("Couldn't pause Spotify: " + e.message); }
  skippedTitle = null;
  await playP3();
}

function onNowPlayingChanged() {
  if (mode === "spotify" && np.title !== skippedTitle) {
    if (np.song && $("chain").checked) {
      skippedTitle = np.title; log(`P3 started another song, still skipping: ${np.title}`);
    } else {
      backToP3(np.song ? "P3's song ended (new song started), back to P3." : "Hosts are back, back to P3.");
    }
  }
  render();
}

// ---------- Wiring ----------
async function init() {
  const q = new URLSearchParams(location.search);
  if (q.get("code")) {
    await tokenRequest({ grant_type: "authorization_code", code: q.get("code"),
      redirect_uri: REDIRECT, code_verifier: store.get("verifier") });
    history.replaceState(null, "", REDIRECT);
  }
  $("clientId").value = store.get("clientId") || "";
  $("playlist").value = store.get("playlist") || "";
  $("chain").checked = store.get("chain") === "1";
  $("chain").onchange = () => store.set("chain", $("chain").checked ? "1" : "0");
  $("connect").onclick = login;
  $("settings").onclick = () => { $("setup").classList.toggle("hidden"); };
  if (!store.get("access")) return;

  $("setup").classList.add("hidden");
  $("radio").classList.remove("hidden");
  render();
  loadPool().catch((e) => log(e.message));
  pollNowPlaying();

  $("skip").onclick = () => {
    if (mode === "off") playP3();
    else if (mode === "p3") skip();
    else backToP3("You went back to P3.");
  };
  p3.addEventListener("pause", () => { if (mode === "p3" && !p3.ended) log("P3 paused."); });
  if ("mediaSession" in navigator) {
    navigator.mediaSession.setActionHandler("nexttrack", () => skip());
    navigator.mediaSession.setActionHandler("play", () => (mode === "spotify" ? backToP3("Back to P3 from the media controls.") : playP3()));
    navigator.mediaSession.setActionHandler("pause", () => p3.pause());
  }
}
init().catch((e) => { log(e.message); status(e.message); });

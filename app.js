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
function isDJ() {
  const v = (store.get("playlist") || "").trim().toLowerCase();
  return v === "dj" || v.includes("37i9dqzf1eykqdzj48dyyq");
}
let pool = [], poolIdx = 0;
async function loadPool() {
  const link = store.get("playlist") || "";
  const m = isDJ() ? null : link.match(/playlist[/:]([A-Za-z0-9]+)/);
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
// P3's now-playing text announces a song ("/ Artist - Title") when it starts, but it can switch
// to the show description or go blank while the song is still playing. So the text only tells
// us when a song STARTS. When it ends comes from the song's real length on Spotify.
const OUTRO_S = 4;              // hosts often start talking over the last seconds
const UNKNOWN_SONG_S = 210;     // skip length when we can't tell what's playing
let song = null;                // { title, artist, track, start, end, startKnown }
let lastTitle = null, firstPoll = true;
const durations = {};

async function songLength(artist, track) {
  const key = artist + "|" + track;
  if (key in durations) return durations[key];
  try {
    const q = track ? `track:${track} artist:${artist}` : artist;
    const r = await api(`/search?type=track&limit=5&q=${encodeURIComponent(q)}`);
    const items = r.tracks.items;
    const hit = items.find((t) => t.name.toLowerCase().startsWith((track || "").toLowerCase().slice(0, 12))) || items[0];
    durations[key] = hit ? hit.duration_ms / 1000 : null;
  } catch (e) { log("Couldn't look up song length: " + e.message); durations[key] = null; }
  return durations[key];
}

async function pollNowPlaying() {
  let d;
  try {
    const r = await fetch(CFG.nowPlayingUrl, { cache: "no-store" });
    d = await r.json();
    if (d.error) throw new Error(d.error);
  } catch (e) { status("Can't see what P3 is playing right now."); console.warn(e); return; }
  status("");
  const isNew = d.song && d.title !== lastTitle && (!song || song.title !== d.title);
  if (d.song) lastTitle = d.title;
  if (isNew) {
    const now = Date.now() / 1000;
    const startKnown = !firstPoll;   // seen the change happen, not opened mid-song
    const len = await songLength(d.artist, d.track);
    const start = startKnown ? now - 3 : now - 45;
    const prev = song;
    song = { title: d.title, artist: d.artist, track: d.track, start, startKnown,
             end: start + (len || UNKNOWN_SONG_S) - OUTRO_S, len };
    log(`P3 song: ${d.artist} - ${d.track || ""} (${len ? Math.round(len) + " s" : "length unknown"})`);
    onNewSong(prev);
  }
  // The show description coming back usually means the hosts are back (P3 often cuts songs short).
  // It has also been seen flipping early, so only trust it once most of the song has played.
  // A blank title tells us nothing and is ignored.
  if (!d.song && d.title && d.title.trim() && mode === "spotify" && skipping) {
    const played = Date.now() / 1000 - skipping.start;
    const enough = skipping.len ? played >= 0.6 * skipping.len : played >= 90;
    if (enough) backToP3("P3's now-playing says the show is back, back to P3.");
  }
  firstPoll = false;
  render();
}
const songPlaying = () => song && Date.now() / 1000 < song.end;

// ---------- The radio ----------
const p3 = $("p3");
let mode = "off";          // off | p3 | spotify
let skipping = null;       // the song being skipped
let returnAt = 0;
let pollTimer = null;

function setPoll(ms) { clearInterval(pollTimer); pollTimer = setInterval(pollNowPlaying, ms); pollNowPlaying(); }

function render() {
  const btn = $("skip");
  btn.classList.toggle("back", mode === "spotify");
  $("source").classList.toggle("spotify", mode === "spotify");
  if (mode === "off") { btn.disabled = false; btn.textContent = "Play P3"; $("source").textContent = "P3 live"; }
  else if (mode === "p3") {
    $("source").textContent = "P3 live";
    $("now").textContent = songPlaying() ? `${song.artist} · ${song.track || ""}` : "P3 live";
    btn.disabled = false;
    btn.textContent = "Skip song";
  } else {
    const left = Math.max(0, Math.round(returnAt - Date.now() / 1000));
    $("source").textContent = "Your Spotify";
    $("now").textContent = skipping ? `Skipping: ${skipping.artist} · ${skipping.track || ""}` : "Skipping";
    btn.disabled = false;
    btn.textContent = `Back to P3 (${Math.floor(left / 60)}:${String(left % 60).padStart(2, "0")})`;
  }
  if ("mediaSession" in navigator) {
    const s = mode === "p3" && songPlaying();
    navigator.mediaSession.metadata = new MediaMetadata({
      title: s ? `${song.artist} - ${song.track}` : "P3 live",
      artist: s ? "Press next to skip to your music" : "P3 DJ",
      artwork: [{ src: "icon.svg", sizes: "512x512", type: "image/svg+xml" }],
    });
  }
}
setInterval(() => {
  if (mode === "spotify" && Date.now() / 1000 >= returnAt) backToP3("P3's song should be over, back to P3.");
  if (mode !== "off") render();
}, 1000);

async function playP3() {
  p3.src = CFG.streamUrl + "?t=" + Date.now();   // fresh connection = live edge, no backlog
  try { await p3.play(); mode = "p3"; setPoll(5000); }
  catch (e) { mode = "off"; status("Tap Play P3 to start."); log("Couldn't start P3 automatically: " + e.message); }
  render();
}

async function skip() {
  if (mode !== "p3") return;
  if (!pool.length) await loadPool();
  const now = Date.now() / 1000;
  skipping = songPlaying() ? song : null;
  returnAt = skipping ? skipping.end : now + UNKNOWN_SONG_S;
  if (skipping && !skipping.startKnown) log("Opened mid-song, so the return time is a guess.");
  if (!skipping) log("Can't tell which song is on, so skipping for 3:30. Tap Back to P3 any time.");
  mode = "spotify"; render();
  try {
    const dev = await phoneDevice();
    if (!dev) throw new Error("Spotify isn't open on any device. Open the Spotify app once, then try again.");
    p3.pause();
    await startMusic(dev.id);
    log(`Skipped → Spotify on ${dev.name}, back in ${Math.round(returnAt - now)} s`);
    setPoll(5000);
  } catch (e) {
    log(e.message); status(e.message);
    mode = "p3"; if (p3.paused) p3.play().catch(() => {}); render();
  }
}

// "dj" in the playlist box = Spotify's DJ. Spotify doesn't officially support starting it from
// other apps, so if it refuses we fall back to your Liked Songs.
const DJ_ID = "37i9dQZF1EYkqdzj48dyYq";
const DJ_URI = "spotify:playlist:" + DJ_ID;
async function startMusic(deviceId) {
  const url = `/me/player/play?device_id=${deviceId}`;
  if (isDJ()) {
    try { await api(url, { method: "PUT", body: JSON.stringify({ context_uri: DJ_URI }) }); log("Started Spotify's DJ."); return; }
    catch (e) { log("Spotify wouldn't start the DJ (" + e.message + "), playing your Liked Songs instead."); }
  }
  await api(url, { method: "PUT", body: JSON.stringify({ uris: nextSongs() }) });
}

async function backToP3(reason) {
  if (mode !== "spotify") return;
  mode = "returning";
  log(reason);
  try { await api("/me/player/pause", { method: "PUT" }); } catch (e) { log("Couldn't pause Spotify: " + e.message); }
  skipping = null;
  await playP3();
}

function onNewSong(prev) {
  // A different song announced while skipping means P3 went straight into the next song.
  if (mode !== "spotify" || !skipping || song.title === skipping.title) return;
  if ($("chain").checked) {
    skipping = song; returnAt = song.end;
    log(`P3 went straight into another song, still skipping: ${song.artist} - ${song.track || ""}`);
  } else {
    backToP3("P3 started a new song, back to P3.");
  }
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

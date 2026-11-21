// Returns what P3 is playing right now, read from the public stream's ICY metadata.
// Songs look like "/ Artist - Title"; talk segments carry the show description.
// Browsers can't read this themselves: the stream's CORS rules block the Icy-MetaData header.
const STREAM = "https://live-icy.dr.dk/A/A05H.mp3";
const cors = { "Access-Control-Allow-Origin": "*", "Access-Control-Allow-Headers": "*", "Cache-Control": "no-store" };

async function readTitle(): Promise<string> {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 8000);
  try {
    const r = await fetch(STREAM, { headers: { "Icy-MetaData": "1", "User-Agent": "p3dj" }, signal: ctrl.signal });
    const metaint = Number(r.headers.get("icy-metaint"));
    if (!metaint || !r.body) throw new Error("no icy metadata");
    const reader = r.body.getReader();
    let buf = new Uint8Array(0);
    const need = async (n: number) => {
      while (buf.length < n) {
        const { value, done } = await reader.read();
        if (done) throw new Error("stream ended");
        const b = new Uint8Array(buf.length + value.length);
        b.set(buf); b.set(value, buf.length); buf = b;
      }
    };
    await need(metaint + 1);
    const len = buf[metaint] * 16;
    await need(metaint + 1 + len);
    const raw = new TextDecoder().decode(buf.slice(metaint + 1, metaint + 1 + len)).replace(/\0+$/, "");
    reader.cancel().catch(() => {});
    const m = raw.match(/StreamTitle='([\s\S]*?)';/);
    return m ? m[1] : raw;
  } finally {
    clearTimeout(timer);
    ctrl.abort();
  }
}

export function parse(title: string) {
  const t = title.trim();
  const song = t.startsWith("/");
  let artist: string | null = null, track: string | null = null;
  if (song) {
    const s = t.replace(/^\/\s*/, "");
    const i = s.indexOf(" - ");
    artist = i >= 0 ? s.slice(0, i) : s;
    track = i >= 0 ? s.slice(i + 3) : null;
  }
  return { song, artist, track, title: song ? t : t.slice(0, 200) };
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { headers: cors });
  try {
    return Response.json({ ...parse(await readTitle()), at: Date.now() }, { headers: cors });
  } catch (e) {
    return Response.json({ error: String(e) }, { status: 502, headers: cors });
  }
});

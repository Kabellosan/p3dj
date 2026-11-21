# P3 DJ

Listen to DR P3 live, and when a song is bad, skip it: P3 goes quiet, your own Spotify music plays,
and when P3's song ends (the hosts are back) Spotify pauses and you're back on live P3.
Your song gets cut off when the hosts return. That's on purpose: it's radio.

Open it on your Android phone in Chrome: **https://kabellosan.github.io/p3dj/**
(Chrome menu → "Add to Home screen" makes it feel like an app.)

## One-time setup (about 2 minutes)

1. Go to https://developer.spotify.com/dashboard, log in, and click **Create app**.
   - App name/description: anything, e.g. "P3 DJ".
   - Redirect URI: `https://kabellosan.github.io/p3dj/` (exactly, with the trailing slash).
   - APIs used: tick **Web API**.
2. Open the app's **Settings** and copy the **Client ID**.
3. Open P3 DJ on your phone, paste the Client ID, optionally paste a playlist link
   (empty = your Liked Songs, or type `dj` for Spotify's DJ), and tap **Connect Spotify**.
4. Open the Spotify app on your phone once so it shows up as a Spotify device. Skips play on your phone by default; Settings → "Play my music on" lets you pick another device.

## Using it

- Tap **Play P3**. While a song is on, tap **Skip song**, or press "next track" on your car,
  headphones or the lock screen.
- **Back to P3 now** returns early. "Keep skipping if P3 plays two songs in a row" does what it says.

## How it works

- Audio: P3's public live stream, played in Chrome.
- "Is a song playing?": P3's stream carries now-playing text. Songs look like `/ Artist - Title`;
  talk carries the show description. Browsers can't read that text directly, so a tiny function
  (`supabase/functions/p3-nowplaying`) reads it and returns JSON. It's configured in `config.js`.
- Spotify: the app tells your phone's Spotify app what to play using the Spotify Web API (Premium needed).

For personal listening only.

## Day-behind version

`day-behind/` holds an earlier version that downloads yesterday's show, finds the songs with
speech/music detection, and fills them with Spotify in a desktop browser. See `python -m p3dj --help`
inside that folder.

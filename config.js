// Where the app finds out what P3 is playing (the small function in supabase/functions/p3-nowplaying).
window.P3DJ_CONFIG = {
  nowPlayingUrl: "https://rvfwmmctqndfqvbuuoyi.supabase.co/functions/v1/p3-nowplaying",
  // Shared Spotify app, so friends only need to tap "Connect Spotify". Not a secret.
  // Each friend must be added under User Management in the Spotify dashboard (see README).
  spotifyClientId: "ee30266f5e654b2a8f222e47efe0cff9",
  streamUrl: "https://live-icy.dr.dk/A/A05H.mp3",
};

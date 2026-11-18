# StreamHub — BUILD CONTRACT (v1.0)

Fresh Kotlin/Compose app, CloudStream-style UX, own code (NOT a CloudStream fork).
Package `com.mazharansari.streamhub`, v1.0/code 1, minSdk 24, targetSdk/compileSdk 35.
Original reference (READ ONLY, never edit): `~/workspace/cinemora-app/CINEMORA-Android`.
Project: `~/workspace/streamhub-app`. API base URL default:
`https://strategy-terry-masters-king.trycloudflare.com` (configurable in Settings).

## API (his Flask API, live on Oracle :8000 via the tunnel above)
- `GET /api/sources` -> `[{"id","name","language"}]` (15 sources).
- Lists `GET /api/trending|popular|latest|movies?lang=all|hindi|english&source=all|<id>&page=1&category=...&type=all|movie|series`
  -> `{"results":[{id,title,poster,year,type, audio:[sub|dub], source}],"errors":[...]}`.
  `id` = source's own detail id (often a full URL — ALWAYS URL-encode as `?id=`).
- `GET /api/search?q=&lang=&source=&page=1&type=` -> same `{"results","errors"}` shape. `q` required.
- `GET /api/anime?source=<id>&id=<item_id>` (alias `/api/title`) -> details dict DIRECTLY:
  `{id,title,poster,banner,plot,genres[],year,status,score,type,audio[],seasons:[{number,name,episodes:[{number,title,data}]}]}`.
  `episode.data` is OPAQUE — pass verbatim (URL-encoded) to `/api/streams`.
- `GET /api/streams?source=<id>&data=<episode_data>` ->
  `{"streams":[{name,url,quality(int|null),type:"m3u8"|"video"|"embed",headers:{Referer,User-Agent},proxy:bool}],"subtitles":[{label,url}]}`.
- `GET /api/proxy?url=<encoded>&ua=&referer=` — Range-capable passthrough, rewrites HLS
  playlists to proxied segment URLs.
- **PROXY RULE (critical):** `proxy:true` streams must be played via
  `{base}/api/proxy?url=<url-encoded>` — NEVER play the raw URL directly (hotlink/IP-bound hosts 403).
  Send the stream's `headers` too when playing direct (`proxy:false`) streams.
- **EMBED RULE:** `type:"embed"` cannot play in ExoPlayer — FILTER IT OUT entirely (never show it as a quality/server option).
- **LANGUAGE:** Hindi dub is the DEFAULT preferred audio. Audio switch = re-search the same title
  with `lang=hindi|english` (`/api/search?q=<title>&lang=...`), load details, match episode number.
  There is no in-stream multi-audio track switching.
- **EXCLUDED SOURCES (never query, never list, never display):** `pkdramas` (NO Pakistani content —
  user order), `animedekho` (brand must never appear anywhere), `rareanimes` (returns 0 items, markup drift).
  Verified-working sources to offer as extensions: animeworld, anisuge, nineanime, anikoto, reanime,
  xanime, vegamovies, multimovies, indstream, ckdub, dramacool, moviebox.
  MovieBox works live (e.g. "The Avengers [Hindi]" audio ["dub"], 720p/480p MP4 via proxy) but its
  catalogue is mostly Hollywood — Hindi dub exists only on SOME titles; never claim otherwise.

## Extensions (core feature)
- An Extension = one API source (id, name, language, category). The Extensions screen lists them
  from `/api/sources` minus EXCLUDED, each with Install/Installed toggle.
- "Install" = enable that source locally (persisted). Only ENABLED sources are queried for
  home/search/details. Default: all verified sources enabled on first run.
- 18+ extensions live in a SEPARATE section on the Extensions screen, hidden/locked by default.
  Only `hentaihaven` for now (LIVE in the API since v1.5.0, verified 2026-11-18:
  search + streams + proxy all work, English subtitles, no Hindi dub).
  The app must still never show adult items anywhere except the unlocked
  adult section.

## 18+ gate (AdultGate)
- Settings has "Adult Content (18+)" -> enter key -> unlocks the adult section (persist per device).
- Key check = SHA-256(input) equals a stored hash. The hash comes from BuildConfig field
  `ADULT_KEY_HASH` (empty string = locked for everyone). **NEVER ship a default/test key.**
  The user will provide the key next; it is NOT invented here.
- Adult content NEVER appears in home/search/trending/recommendations — only inside the unlocked
  adult section. Wrong key = stays locked, no hint about the content.

## Player (Media3 ExoPlayer 1.4.1)
- Tap video surface = SHOW controls only (never toggles pause). Pause/resume ONLY via the
  pause/play button (center + control bar).
- Center controls: -10s button, play/pause button, +10s button. Double-tap left/right = -10s/+10s
  with animated feedback (circular arrow + "10" rotate/pulse overlay). Play/pause button animates
  (scale/fade morph). Controls auto-hide after 3s of inactivity; tap reveals.
- Fullscreen = real toggle. Quality menu = real variants only (from /api/streams, best first).
  Language/audio menu = actually switches audio (see LANGUAGE above). Speed menu. Subtitles when
  returned (on/off + language). Skip Intro / Auto Next default ON. Prev/Next episode. Mark watched.
- Portrait layout must fit fully: icon-only buttons, separate rows for seek bar vs controls,
  NOTHING clipped or pushed off-screen (390px wide is the test width).
- No native Android popups anywhere: all menus are custom Compose dropdowns/modals.

## Downloads (in-app only, Media3 DownloadService + SimpleCache)
- Per-episode/movie download with progress (%, bytes), Pause/Resume, Delete mid-download
  (removes the partial file COMPLETELY — verify 0 segments remain), completed list with
  banner/poster, offline playback (must work with network cut).
- Download requests MUST use the proxied URL (`/api/proxy?...`) for `proxy:true` streams
  (bare DownloadManager ignores per-source headers and will fail).
- Website-style "download the app" prompt does NOT apply here — this IS the app.

## Global rules
- NO demo/sample content: every list starts empty until the API returns real items. Only rails
  with real items render. Every card shows a real poster or a branded title-card fallback
  (gradient + title text) — NEVER a blank card.
- 100% English UI text. No "Anime Dekho" text anywhere. No "Pakistani" content anywhere.
- Header sits below the status bar with a clear gap on every screen (WindowInsets).
- Professional icons only (Material icons / clean SVGs), no cartoonish art.
- Branding: "Developed by Mazhar Ansari" + GitHub link https://github.com/mazharmnzoor4227-beep
  in Settings/About + footer where sensible.
- Fast: home fast, first frame <5s. No `Thread.sleep` hacks. No fake success — report real results.

## File ownership (do NOT write outside your area)
- Agent A (API+extensions): `data/api/*`, `data/extensions/*`, `data/AdultGate.kt`,
  `data/SettingsStore.kt`. May read `core/Http.kt` (OkHttp helper) but prefer a clean client.
- Agent B (player): `ui/player/*` (PlayerScreen.kt, PlayerViewModel.kt, SkipIntro.kt if needed).
  May read originals in `~/workspace/cinemora-app/.../player/` for reference.
- Agent C (downloads): `download/*` (rewrite StreamHubDownloadService.kt, DownloadHelper.kt,
  DownloadCache.kt), `ui/screens/DownloadsScreen.kt`.
- Main agent (me): MainActivity, StreamHubApp nav, Application, theme/components,
  Home/Search/Details/Extensions/Settings screens, manifest, gradle, CI, README, push, verify.
- Shared models live in Agent A's `data/api/Models.kt` — B and C import from there.
  If B/C need a model that does not exist yet, define it locally in your own file and note it
  in your report (do NOT create files in A's area).

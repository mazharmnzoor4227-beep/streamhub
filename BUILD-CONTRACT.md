# StreamHub — BUILD CONTRACT (v1.0)

Fresh Kotlin/Compose app, CloudStream-style UX, own code (NOT a CloudStream fork).
Package `com.streamhub.app`, v1.0/code 1, minSdk 24, targetSdk/compileSdk 35.
Original reference (READ ONLY, never edit): `~/workspace/cinemora-app/CINEMORA-Android`.
Project: `~/workspace/streamhub-app`. API base URL (build-time constant only,
hidden inside the app — NO in-app setting): `https://strategy-terry-masters-king.trycloudflare.com`.

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
- 18+ extensions live ONLY in the hidden page (Settings → Lock → password).
  Only `hentaihaven` for now (LIVE in the API since v1.5.0, verified 2026-11-18:
  search + streams + proxy all work, English subtitles, no Hindi dub).
  The app must still never show adult items anywhere except the hidden
  page. There is NO adult tab, NO adult section in Extensions, NO adult
  pill on Home.

## 18+ gate (AdultGate) — the hidden folder
- Settings shows ONE row, "Lock". Tapping it asks for the password EVERY
  time; the correct password opens a separate hidden page with ALL adult
  content (browse list across installed adult extensions + search + the
  adult extensions to install/manage). Nothing persists unlocked.
- Key check = SHA-256(input) equals a stored hash. The hash comes from BuildConfig field
  `ADULT_KEY_HASH` (empty string = locked for everyone). **NEVER ship a default/test key.**
  The user will provide the key next; it is NOT invented here.
- Adult content NEVER appears in home/search/trending/recommendations — only inside the hidden
  page. Wrong password = nothing opens, no hint about the content.

## Player (Media3 ExoPlayer 1.4.1)
- Tap video surface = TOGGLES the controls (first tap shows, second hides) — never toggles
  pause. Pause/resume ONLY via the pause/play button (center overlay ONLY — the bottom bar
  has no transport buttons). Hold the video (400ms) = 2x speed while held, with a "2x"
  indicator; release restores the previous speed.
- Aspect / frame chip in the top bar cycles Fit (real aspect ratio) → Fill (cropped
  fullscreen) → Zoom (cropped + 1.3x surface scale); the chip shows the active mode.
- Center controls: -10s button, play/pause button, +10s button. Double-tap left/right = -10s/+10s
  with animated feedback (circular arrow + "10" rotate/pulse overlay) — exactly ONE animation
  per skip (the tapped control only). Play/pause button animates
  (scale/fade morph). Controls auto-hide after 3s of inactivity; tap reveals.
- Fullscreen = real toggle. Quality menu = real variants only (from /api/streams, best first).
  Language/audio menu = actually switches audio (see LANGUAGE above). Speed menu. Subtitles when
  returned (on/off + language). Skip Intro / Auto Next default ON. Prev/Next episode. Mark watched.
- Portrait layout must fit fully: icon-only buttons, separate rows for seek bar vs controls,
  NOTHING clipped or pushed off-screen (390px wide is the test width).
- No native Android popups anywhere: all menus are custom Compose dropdowns/modals.
- Settings popups (Volume/Speed/Subtitles/Quality/Audio) open ANCHORED TO THE TAPPED ICON
  (DropdownMenu inside the icon's own Box) — never at a fixed bottom-left corner.
- Load failure is NEVER a silent black 00:00 screen: loading spinner while loading, clear
  error + Retry + "Try another server" on failure (Player.Listener + 25s watchdog).
- Position saves every ~10s while playing + on exit (not only on exit) — a killed app
  keeps its progress. History is recorded on play start + every save.

## Downloads (in-app only, Media3 DownloadService + SimpleCache)
- Per-episode/movie download with progress (%, bytes), Pause/Resume, Delete mid-download
  (removes the partial file COMPLETELY — verify 0 segments remain), completed list with
  banner/poster, offline playback (must work with network cut).
- % is ALWAYS visible while downloading: real total when known, else the HLS size
  estimate (duration × bandwidth / 8, stored at enqueue, computed live once for older
  downloads) shown as "% · X / ~Y". Pause/Resume/Delete are big LABELED buttons on
  every row in every state; Delete removes ALL language variants of the episode.
- Downloads are MULTI-LANGUAGE: Hindi first (always included) + English, one download
  per language (lang-suffixed ids). The Downloads list groups variants per episode
  with language badges; the offline Audio menu switches between downloaded languages.
  Legacy v1.0.1 downloads (no lang suffix) count as the Hindi variant and are reused,
  never re-downloaded.
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

## v1.0.2 addendum (2026-11-18)
- Package stays `com.streamhub.app`, versionName 1.0.2, versionCode 3. ADULT_KEY_HASH
  in app/build.gradle.kts is preserved byte-for-byte.
- API is HIDDEN: no API base URL field anywhere in Settings; the app uses only the
  build-time constant. No URL/hostname/endpoint appears in any UI text; errors are generic.
- Browse / Discover (BrowseScreen): Type (Anime/Movies/Series — semantic ?type= on the
  API, source-based), Country (USA, Germany, Korea, Japan, China, India — ?country=,
  items carry a "country" field), Year (from/to — ?year_from=/?year_to=). Pages 1–3
  merged: Germany returns 0 on pages 1–2 of latest and only appears from page 3.
  Categories Anime/Movies now use ?type= too (the old client-side type filter wrongly
  included K-dramas, which arrive labelled type "anime").
- Seasons: prominent "Seasons" header + scrollable chips with count + range
  ("Season 5 • 24 episodes • Ep 89–112") from actual first/last episode numbers.
  Episodes show a wide 112x64dp poster thumbnail (never a number box, never red).
- My List (WatchlistStore, local): "Add to My List" on Details, Home rail + full grid.
- Continue Watching: Home cards jump STRAIGHT into the player at the saved position;
  Details shows a "Continue watching — Episode N • Watched X of Y" prompt with Resume.
- History (HistoryStore, local): everything watched, newest first, Home rail + full
  screen with "watched X ago" + progress + Clear History (two-tap confirm). Tapping
  resumes exactly where left off.
- Scroll restore: Home/Search/See All/Category/Browse/My List/History keep their scroll
  position on back from Details/Player (hoisted state at the app root).
- Slim sticky headers everywhere (Search ~170dp, See All ~80dp, Home hero 300dp).
- API v1.6 (anime-api/app.py): semantic ?type=anime|series|movie, ?country= (aliases:
  america/usa→United States, korea→South Korea, ...), ?year_from=/?year_to= on list +
  search routes; country tagged per item (MovieBox countryName; anime sources Japan;
  kdrama/cdrama South Korea/China). Live-verified: Anime 158, Series 175, Movie 144,
  Japan 158, Korea 69, China 30, USA 8, Germany 2+ (page 3), India 6, year 2002–2005: 4.

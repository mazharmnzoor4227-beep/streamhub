# StreamHub — CloudStream-style streaming app (Android)

Fresh Kotlin/Compose app, own code (NOT a CloudStream fork). Anime, Hindi-dub
movies, Hollywood, Bollywood, K-drama and C-drama — all streaming inside the
app through inbuilt extensions served by the StreamHub API.

- Package: `com.mazharansari.streamhub` — v1.0 (code 1)
- Min SDK 24 · Target/Compile SDK 35 · Kotlin 2.0.21 · Media3 ExoPlayer 1.4.1
- No demo/sample content: every list is real API data; empty until it loads.

## What it is

CloudStream's UX, simplified the way the owner asked: the **Extensions**
screen lives in the bottom navigation (not buried in Settings). Open it,
tap Install on any extension, and it starts working — no hunting, no
side-loading. Extensions are source adapters served by the API
(`anime-api`): AnimeWorld, AniSuge, 9anime, Vegamovies, Multimovies,
IndStream, MovieBox, DramaCool, and more. Only verified-working sources
appear; nothing fake.

**18+ is separate.** Adult extensions (HentaiHaven) live in a locked
section, hidden by default, unlocked only by entering a key in Settings.
The key is never shipped in the app — the owner sets it. Adult content
never appears in home, search, trending, or recommendations.

## Screens

Home (rails) · Search · Details (seasons/episodes) · Player (ExoPlayer,
custom controls: tap shows controls, ±10s skip with animation, quality /
language / speed / subtitles, Skip Intro, Auto Next) · Downloads (in-app
only, pause/resume/delete, offline playback) · Extensions · Settings
(including API base URL and the 18+ key gate).

## API

Default base URL: `https://strategy-terry-masters-king.trycloudflare.com`
(the live `anime-api`, changeable in Settings). Streams marked `proxy:true`
are played through `/api/proxy`; `embed`-type streams are never shown.

## Build

GitHub Actions (`.github/workflows/build-apk.yml`) builds the release APK
on every push to `main` (unsigned artifact `streamhub-apk`). The APK is
signed locally with the debug keystore after download.

Developed by Mazhar Ansari — https://github.com/mazharmnzoor4227-beep

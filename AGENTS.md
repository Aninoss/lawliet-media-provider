# AGENTS.md

Single-module Gradle app (Java 15, Jersey + Grizzly). Media/video proxy for the Lawliet Discord bot. No tests, linter, or CI exist in the repo.

## Commands
- Build: `./gradlew build` (wrapper is Gradle 7.1; needs a JDK that supports it, ideally JDK 15)
- Run: `./gradlew run` with env vars below set. Entry point is `core.Main`.
- Docker image is built with Jib (`./gradlew jib` / `jibDockerBuild`), not a Dockerfile.

## Required env vars (read with bare `System.getenv`, so a missing one causes an NPE/NumberFormatException)
- `PORT` and `PRODUCTION` (`"true"`/other) are needed at startup (`Main`, `Program`).
- `URL_ROOT` is used by `/player` for non-e621/realbooru sources.
- `AUTH` is the shared secret for `/pixiv_download`.
- `SHARD_BLOCKING`, `MAX_SHARDS` and `SHARDS` (comma-separated ints). If `SHARD_BLOCKING=true`, only files whose `abs(Objects.hash(dir, file)) % MAX_SHARDS` is in `SHARDS` are served (403 otherwise).

## Architecture
- All HTTP endpoints are in `core/RestService.java`.
  - `/request` is called by an nginx `auth_request`-style subrequest and reads the original path from the `X-Original-URI` header. It validates the path, then downloads the file through `VideoDownloader`. It returns the file size as the body, or 403 on any invalid or unsupported path.
  - `/media/rule34/...` redirects to the origin.
  - `/player...` renders an HTML page with OpenGraph video tags.
  - `/pixiv_download/...` is AUTH-protected.
- `VideoDownloader` caches files on disk under the hardcoded `/cdn/media/<domain>/<dir>/<file>` (pixiv: `/cdn/pixiv/`). It locks per URL via `LockManager`. There is no cache cleaner (Redis and the cache cleaner were removed in the latest commits).
- The `/cdn` paths are absolute and hardcoded, so local runs need that dir to be writable or the downloads will fail. Download errors are only logged, and the size comes back as -1.
- Adding a new source means:
  1. Add a branch in `request()` that checks the expected path-segment count.
  2. Add regex patterns and a `checkRequestUri*` method that calls `isResponsible`.
  3. Add a URL builder.
  4. Optionally add a `/player` branch for the thumbnail URL.
- `/request` source quirks: the rule34 subdomain comes from `?s=<sub>` and the realbooru post id from `?id=<id>`, both appended to the last path segment (default subdomains: rule34 `api-cdn-mp4`, danbooru `cdn`). Realbooru files starting with `thumbnail_` are fetched from `/thumbnails/`. Realbooru sends a post-page `Referer`.
- Cache gotchas: a non-empty file on disk counts as cached, so a partial download is never retried (0-byte files are redownloaded by `VideoDownloader`; `/pixiv_download` still only checks `exists()`). `VIDEO_CACHE_MAX_AGE` in `VideoDownloader` is unused. Only rule34 validates the file name by regex. Danbooru and realbooru validate only the dir, subdomain and post id.
- `/player` throws if the `w` or `h` query param is missing (`s` is optional). `/pixiv_download` is not shard-checked and does not validate `id`.
- Input is validated with the strict regexes at the top of `RestService`, because values are interpolated straight into upstream URLs. Keep new patterns that strict.

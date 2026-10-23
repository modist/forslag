# Forslag

An Android TV app that shows what is included in your own streaming subscriptions right now: a row of
movies and a row of series, most popular first, mixed from each service in turn. Focus a title to see its
year, rating, plot and which of your services has it; press OK to open it. Netflix, Disney+ and DRTV open the
title itself where there is a link to it; the other services open at their home screen.

It declares a module card for the [Clean Your TV](https://github.com/Gathin23/cleanyourtv) launcher
(label **Forslag**, caption *Film og serier*), so it can sit in the dock. It works as an ordinary app on
any Android TV as well.

The data comes from [TMDb](https://www.themoviedb.org/), whose streaming availability comes from
JustWatch. This product uses the TMDB API but is not endorsed or certified by TMDB. DRTV's titles come from DR's
own catalogue, and the Netflix and Disney+ ids that open a title directly come from
[Wikidata](https://www.wikidata.org/) (CC0).

## Set up

1. Make a free TMDb account and ask for an API key: <https://www.themoviedb.org/settings/api>. Either
   the v3 "API Key" or the v4 "API Read Access Token" works.
2. Put it in `local.properties` in this folder (git ignores that file, so the key stays out of the repo):

   ```properties
   sdk.dir=C:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
   tmdb.key=<your key>
   ```

   Or set `TMDB_KEY` in the environment when you build.
3. Pick your services in [`app/src/main/res/values/config.xml`](app/src/main/res/values/config.xml):
   one line per subscription, `TMDb provider name|app package`. Several names, separated by commas, cover a
   service that was renamed (`HBO Max,Max`; TMDb calls TV 2 Play just `TV 2`). A number is read as a TMDb provider id. A service TMDb doesn't
   know is named in the status line at the bottom of the screen, so you can correct it. Region (`DK`) and
   language (`da-DK`) are set in the same file.

## Build and install

JDK 17 and Android SDK 34, as for the launcher:

```bash
./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease
```

With the Clean Your TV tools:

```bash
TV_HOST=<tv ip> tools/venv/bin/python -I tools/tv.py --push app/build/outputs/apk/release/app-release.apk /data/local/tmp/forslag.apk
TV_HOST=<tv ip> tools/venv/bin/python -I tools/tv.py 'pm install -r /data/local/tmp/forslag.apk; rm -f /data/local/tmp/forslag.apk'
```

Press Home. The launcher asks once whether to show the card; answer Show card.

The release build is signed with the debug key, like the launcher. The TMDb key is compiled into the APK,
so keep the APK to yourself.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Fetches lists from `api.themoviedb.org` and posters from `image.tmdb.org`; each request sends only the region, language, provider id and your key. DRTV's two lists and their posters from `production.dr-massive.com` and `prod95-static.dr-massive.com`, without login. One query to `query.wikidata.org` with the TMDb ids of the titles on Netflix and Disney+. |
| `<queries>` for launcher activities | To find and open the streaming app a title is on. Nothing is read from other apps. |

No background service, no telemetry, no ads, no account sign-in. TMDb answers and posters are cached in
the app's cache folder for six hours (`cache_hours` in `config.xml`). Opening the app again within that
time sends nothing. When TMDb can't be reached, the last answers are shown.

## Limits

- TMDb's subscription filter also lets through titles a service only rents or sells (on Viaplay, 18 of the top
  20 "subscription" movies were rentals), so each title's availability is checked before it is shown. The first
  load sends about 150 small requests and takes 15 to 30 seconds; after that the cache answers.
- Titles with fewer than 50 TMDb votes are left out (`min_votes`), which keeps out titles popular elsewhere but
  unknown here.
- DRTV's API is DR's own but undocumented and unofficial; it can change without notice. If it fails, the rows
  simply have no DRTV titles. The lists are `drtv_movies` and `drtv_series` in `config.xml`.
- OK opens the title itself on Netflix and Disney+ only when Wikidata has the service's id for it, which is most
  well-known titles and few new ones. HBO Max's ids on Wikidata are in an old format the app no longer opens,
  and Viaplay, TV 2 Play and YouSee have none, so those open at their home screen. Netflix and Disney+ ask for a
  profile first when they were not already running.
- TMDb's availability data is JustWatch's and can lag a day or two behind what a service actually has.
- Plots are in Danish where TMDb has one, and empty otherwise.

## Tested on

Philips 2021/22 UHD Android TV (PH1M_EA_9970A), Android 11, with the Clean Your TV launcher: rows, posters,
D-pad, OK into HBO Max, and the module card.

# Forslag

An Android TV app that shows what is included in your own streaming subscriptions right now: a row of
movies and a row of series, most popular first, mixed from each service in turn. Each row has genre tabs
above it (All, Drama, Comedy, Crime, Action, Sci-fi & fantasy, Documentary, Entertainment for series, Family &
kids, Animation): rest the focus on a tab, or press OK on it, and the row shows that genre. A genre is fetched
the first time it is chosen and kept like the rest. Focus a title to see its
year, rating, plot and which of your services has it; press OK to open it. Netflix, Disney+, DRTV and Viaplay
open the title itself where there is a link to it; the other services open at their home screen.

It declares a module card for the [Clean Your TV](https://github.com/Gathin23/cleanyourtv) launcher
(label **Forslag**, caption *Film og serier*), so it can sit in the dock. It works as an ordinary app on
any Android TV as well.

The data comes from [TMDb](https://www.themoviedb.org/), whose streaming availability comes from
JustWatch. This product uses the TMDB API but is not endorsed or certified by TMDB. DRTV's and Viaplay's titles come from
their own catalogues, and the Netflix and Disney+ ids that open a title directly come from
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
| `INTERNET` | Fetches lists from `api.themoviedb.org` and posters from `image.tmdb.org`; each request sends only the region, language, provider id and your key. DRTV's two lists and their posters from `production.dr-massive.com` and `prod95-static.dr-massive.com`, and Viaplay's most popular and genre pages from `content.viaplay.dk` with posters from `i-viaplay-com.akamaized.net`, without login. One query to `query.wikidata.org` with the TMDb ids of the titles on Netflix and Disney+. |
| `<queries>` for launcher activities | To find and open the streaming app a title is on. Nothing is read from other apps. |

No background service, no telemetry, no ads, no account sign-in. TMDb answers and posters are cached in
the app's cache folder for six hours (`cache_hours` in `config.xml`). Opening the app again within that
time sends nothing. When TMDb can't be reached, the last answers are shown.

## Limits

- Viaplay's titles come from its own catalogue: up to three pages of its most popular films or series (or of its
  genre page for a tab), only titles included in the subscription now, not rentals. This API is undocumented
  and unofficial too.
- TMDb's subscription filter also lets through titles a service only rents or sells (on Viaplay, 18 of the top
  20 "subscription" movies were rentals), so each title's availability is checked before it is shown. The first
  load sends about 150 small requests and took about 50 seconds on the Philips; after that the cache answers.
- Titles with fewer than 50 TMDb votes are left out (`min_votes`), which keeps out titles popular elsewhere but
  unknown here.
- DRTV's lists are not by genre, so a genre tab keeps the DR titles DR tags with it (categories for series,
  keywords for films); in the narrower genres there are few.
- DRTV's API is DR's own but undocumented and unofficial; it can change without notice. If it fails, the rows
  simply have no DRTV titles. The lists are `drtv_movies` and `drtv_series` in `config.xml`.
- OK opens the title itself on Netflix and Disney+ only when Wikidata has the service's id for it, which is most
  well-known titles and few new ones. HBO Max's ids on Wikidata are in an old format the app no longer opens,
  and TV 2 Play and YouSee have none, so those open at their home screen. Viaplay titles open through Viaplay's own
paths. Netflix and Disney+ ask for a
  profile first when they were not already running.
- TMDb's availability data is JustWatch's and can lag a day or two behind what a service actually has.
- Plots are in Danish where TMDb has one, and empty otherwise.

## Tested on

Philips 2021/22 UHD Android TV (PH1M_EA_9970A), Android 11, with the Clean Your TV launcher: rows, posters,
D-pad, genre tabs (Comedy movies, Crime series), the module card, OK into HBO Max (home screen), and OK straight
to the title on DRTV (Moedregruppen), on Viaplay (The Amateur) and on Netflix while it was already running
(Breaking Bad). Disney+ opened
the title from a hand-sent link of the same form.

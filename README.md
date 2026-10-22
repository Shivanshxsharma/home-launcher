<h1 align="center">Home Launcher</h1>

<p align="center">
  <b>A cinematic home screen for Android TV.</b><br>
  Apple TV's calm, Google TV's immersion and PS5-style browsing, built to stay smooth on a 2 GB Xiaomi Mi Box 4K.
</p>

<p align="center">
  <img alt="Android TV 9+" src="https://img.shields.io/badge/Android%20TV-9%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin, plain Views" src="https://img.shields.io/badge/Kotlin-plain%20Views-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Data from TMDB" src="https://img.shields.io/badge/Data-TMDB-01B4E4?logo=themoviedatabase&logoColor=white">
  <img alt="60 fps on a Mi Box 4K" src="https://img.shields.io/badge/Mi%20Box%204K-60%20fps-F5F3EE">
</p>

<p align="center">
  <a href="https://github.com/Shivanshxsharma/home-launcher/releases/latest"><b>Download Home Launcher or Maximal</b></a> · Android TV 9 or newer
</p>

<p align="center">
  <img src="docs/screenshots/home.jpg" alt="The home screen: a full-bleed banner for Carrie with its title logo, Play, Add to List and Trailer buttons, and the favorite apps row underneath" width="100%">
</p>

## What it does

- **A banner worth looking at.** Full-bleed art, the show's own title logo, and three buttons: **Play**, **Add to List** and **Trailer**. Left and right walk through the buttons, then on to the next show.
- **Shows and Movies, PS5-style.** Pick a genre (Indian, Drama, Crime, Action and more), and the focused title fills the screen above a single row of posters.
- **Set up from your phone.** Scan a QR code on the TV and paste your free TMDB key on your phone; nothing is typed with the remote.
- **Your library.** Sign in to TMDB the same way, by scanning a code. Your watchlists and favorites become My Shows, Watchlist and Favorites.
- **Search with your voice.** An on-screen keyboard, plus a mic key that uses the box's own Google speech service. The show you meant comes first.
- **Trailers in place.** Trailers play full-screen inside the launcher, and Back drops you right where you were.
- **Hold OK on anything.** On a show you can add it to a list, favorite it, play its trailer or open its app. On an app you can add it to Home, move it or remove it.
- **Only the apps you want.** The Home row shows your favorite apps, in your order, and the Apps tab has everything else.
- **Scores at a glance.** TMDB's user score (★ 8.4) sits next to the year and genres on the banner and on every title you focus.
- **India first.** Availability comes from TMDB's India listings for Netflix, Prime Video, JioHotstar, SonyLIV, ZEE5 and Apple TV.

## A closer look

### Home

The banner leads with your own shows and fills up with what's trending on your apps. Below it are **Up Next** (new episodes from your shows), **My Shows** and **Favorites**. Suggestion rows always stay underneath, however much you save.

<img src="docs/screenshots/home-rows.jpg" alt="Home rows: New This Week episodes with progress bars, and Popular Indian Shows posters" width="100%">

<img src="docs/screenshots/home-trailer.jpg" alt="The banner for Cyberpunk: Edgerunners with the Trailer button focused" width="100%">

### Shows and Movies

Browsing works like the PS5 home screen. Up from the posters is the genre strip, and the backdrop changes with the focused title.

<img src="docs/screenshots/shows.jpg" alt="Shows tab on the Indian genre: CID large over its art with a row of Indian series posters" width="100%">

<img src="docs/screenshots/movies.jpg" alt="Movies tab on the Romance genre: Drawn Together large over its art with a row of film posters" width="100%">

### Library, menu, Search and Apps

<table>
  <tr>
    <td width="50%"><img src="docs/screenshots/library.jpg" alt="Library tab: My Shows, Watchlist, Favorite Shows and Favorite Films, with Sacred Games in focus"></td>
    <td width="50%"><img src="docs/screenshots/menu.jpg" alt="The hold-OK menu over Sacred Games: Remove from My Shows, Add to Favorites, Open Netflix"></td>
  </tr>
  <tr>
    <td align="center"><b>Library</b>: everything you've saved on TMDB</td>
    <td align="center"><b>Hold OK</b>: lists, favorites, trailer, open the app</td>
  </tr>
  <tr>
    <td width="50%"><img src="docs/screenshots/search.jpg" alt="Search tab: keyboard with Space, Delete, Clear and a mic key, next to a grid of trending posters"></td>
    <td width="50%"><img src="docs/screenshots/apps.jpg" alt="Apps tab: a grid of every app on the box"></td>
  </tr>
  <tr>
    <td align="center"><b>Search</b>: type or speak; trending until you do</td>
    <td align="center"><b>Apps</b>: hold OK on one to put it on Home</td>
  </tr>
</table>

## Maximal, a second launcher

Maximal is a separate app in the same repository, for when you want something calmer. One title fills the screen: a big wide title at the bottom left, its storyline at the bottom right, and a round Play button in the middle. A glass sidebar on the left holds Home, Shows, Movies, Apps and Search, and a rail of posters runs down the right.

<img src="docs/screenshots/maximal.jpg" alt="Maximal on Home: The Apothecary Diaries full-screen, with the sidebar on the left, the poster rail on the right, and ★ 8.7 · 2023 · 1 season · Netflix under the title" width="100%">

<img src="docs/screenshots/maximal-movies.jpg" alt="Maximal on Movies: Tenzing full-screen with ★ 8.4 · 2026 · 2h 6 mins · Apple TV under the title, and trending films on the rail" width="100%">

- **Three columns on the D-pad.** Left and right move between the sidebar, the title and the rail; up and down move within one.
- **Same data as Home Launcher.** It shares Home Launcher's TMDB code, so it has the same trending titles, genres, search, trailers and watchlist. Connect TMDB from **Profile** by scanning the code with your phone.
- **Built for the same box.** Shading is baked into the art, panels use pre-blurred glass, and nothing redraws while you're idle. On the Mi Box 4K, moving through the rail renders at a median of 5.7 ms a frame.

To try it, download `Maximal-0.2.0.apk` from the [latest release](https://github.com/Shivanshxsharma/home-launcher/releases/latest) and install it the same way as Home Launcher (see [Install it](#install-it)). It works next to Home Launcher or on its own. With both installed, Android asks which one to use the next time you press Home.

## On the remote

| Button | What it does |
| --- | --- |
| **D-pad** | Moves focus. On the banner, left and right go across the buttons, then to the previous or next show. |
| **OK** | Opens the app, show or tab in focus. |
| **Hold OK** or **Menu** | Opens the options for that show or app. |
| **Hold OK on the Library tab** | TMDB options: use a different key, or sign out. |
| **Back** | Climbs up: from a row to the banner, from the banner to the tab bar, from another tab to Home. |
| **Home** | Takes you back to the top of Home. |
| **Apps** | Opens the Apps tab (after you turn on the Apps button service; see below). |

## Smooth on a 2 GB box

The Mi Box 4K has a Mali-450 GPU and 2 GB of RAM, so smoothness was designed in from the start rather than patched afterwards. On the box, frames take about 13 ms at the median (60 fps needs under 16.7 ms), including while new posters and data stream in.

- **Plain Android Views and RecyclerView**, with springs from `androidx.dynamicanimation` for focus, lift, tilt and scrolling.
- **No live blur.** The frosted glass is a tiny pre-blurred copy of the art, stretched to full screen.
- **Shading baked into the art.** On Shows and Movies, the gradients behind the words are drawn into the backdrop when it's decoded, which saves about 7 ms of GPU time per frame.
- **Backdrops decoded at 1280×720 off the main thread**, and never more than two held in memory at once.
- **TMDB requests on low-priority background threads**, cached on disk. The first frame draws from the cache, and on Shows and Movies a title's full details load only once focus rests on it.

## Install it

1. **Download** `HomeLauncher-0.5.0.apk` from the [latest release](https://github.com/Shivanshxsharma/home-launcher/releases/latest). For Maximal, use `Maximal-0.2.0.apk` instead, or install both.
2. **Install it on the TV.** Turn on network debugging on the TV (Settings → Device Preferences → About → press Build seven times, then Developer options → USB debugging), then from a computer on the same Wi-Fi:
   ```bash
   adb connect <tv-ip>:5555
   adb install HomeLauncher-0.5.0.apk
   ```
   A file manager or a "send files to TV" app works too.
3. **Connect TMDB.** Open Home Launcher and it starts on the **Connect TMDB** page. Scan the code with a phone on the same Wi-Fi, follow the link on your phone to get a free TMDB API key, and paste it in. The TV checks the key and connects. The key is kept only on the TV. In Maximal, open **Profile** at the bottom of the sidebar instead.
4. **Optional: sign in** on the Library tab (in Maximal, Profile), the same way, to bring in your TMDB watchlist and favorites.
5. **Make it the Home screen.** Android TV's built-in launcher always takes priority over installed ones, so switch it off:
   ```bash
   adb shell pm disable-user --user 0 com.google.android.tvlauncher
   ```
   To go back, run `adb shell pm enable com.google.android.tvlauncher`, or on the TV go to **Settings → Apps → See all apps → Show system apps → Android TV Home → Enable**.
6. **Optional: the remote's Apps button.** Android TV only sends this button to its own launcher. To have it open this launcher's Apps tab instead, go to **Settings → Device Preferences → Accessibility** and turn on **Home Launcher: Apps button**. That service listens for that one button and reads nothing on screen.

The APKs are signed with a development key. If you built and installed your own copy before, uninstall it first. Version 0.5.0 installs over 0.4.0 and keeps your key and sign-in.

### Build it yourself

You need JDK 17 or newer and the Android SDK (platform 37).

```bash
./gradlew assembleRelease -PpublicBuild
```

That builds both launchers: Home Launcher at `app/build/outputs/apk/release/` and Maximal at `maximal/build/outputs/apk/release/`. Each APK has no key inside; you connect one from your phone as above. For your own builds you can bake a key in instead: put `tmdb.apiKey=YOUR_KEY` in `local.properties` (git ignores that file) and leave out `-PpublicBuild`. Don't share an APK built that way, because anyone can read the key out of it.

## Under the hood

```
app/src/main/java/com/shivansh/mibox/
├── HomeActivity.kt      focus, keys and pages
├── TrailerActivity.kt   full-screen trailers (YouTube's embedded player)
├── AppsKeyService.kt    the remote's Apps button
├── data/                installed apps, favorites, demo content
│   └── tmdb/            TMDB client, key, phone setup page, catalog, search, lists, sign-in
└── ui/                  banner, focus cards, PS5-style pages, keyboard, menu, glass, backdrops

maximal/src/main/java/com/shivansh/mibox/maximal/
├── MaximalActivity.kt   sidebar, title and rail, and the keys between them
└── ui/                  sidebar, poster rail, title block, profile, glass backdrops
```

Maximal compiles Home Launcher's `data/` package at build time (see `maximal/build.gradle.kts`), so both launchers read TMDB the same way.

- **Kotlin**, `minSdk 28` (Android 9), no Compose and no image-loading library.
- **TMDB v3** for titles, art, logos, trailers, India watch providers and your lists, with **ZXing** for the sign-in QR code.
- Your TMDB key and session stay on the TV. The setup page the TV serves answers only at a random link, and only while the Connect page is open. There are no analytics.

## Status

This is a personal project, tested on one device, a Xiaomi Mi Box 4K running Android TV 9. Other Android TV boxes on Android 9 or newer should work, but haven't been tried.

Show and film artwork, logos and trailers belong to their owners and are shown through TMDB. Apple TV, Google TV, PS5 and the streaming services named here are trademarks of their owners; this project isn't affiliated with any of them.

Data and images from [TMDB](https://www.themoviedb.org/). This product uses the TMDB API but is not endorsed or certified by TMDB.

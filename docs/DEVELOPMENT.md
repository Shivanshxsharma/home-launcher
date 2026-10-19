# Mi Box launcher

A home screen for the Xiaomi Mi Box 4K (Android TV 9, Mali-450, 2 GB RAM): Google TV's
immersion (full-bleed art, muted autoplay previews, ambient mode) with Apple TV's restraint and
motion (big app icons, lift-and-tilt focus, keylines, springs). Phase 1 runs on bundled demo
shows and clips; phase 2 brings in TMDB.

The look follows the design canvas (claude.ai artifact `H4zkCV9CxDRoLKMYAUZDhQ`, boards
`Main` and `Show`); the mockup art and clips are in `design/`.

## What's on screen

- **Hero**: the user's shows one at a time over a full-bleed backdrop. Left/right changes slide,
  and it moves on by itself every 9.4 s while focused. Backdrops crossfade and slowly zoom.
- **Previews**: after focus rests (1.5 s on the hero, 1.2 s on a row) the show's muted clip
  fades in behind the UI. On the hero, 2.8 quiet seconds later the UI steps back into ambient
  mode; any button brings it back.
- **App icons**: every installed app (LEANBACK_LAUNCHER first, then LAUNCHER) as a 5:3 tvOS-style
  icon from its TV banner, plus Settings and All apps. The name appears under the focused icon,
  and the top shelf above shows what that app has for you.
- **Up Next, My Shows, Favorites**: demo shows from `app/src/main/assets/demo`, words under the
  cards rather than over them.
- **Focus**: the card springs up, leans toward the way focus travelled and settles, a glare
  drifts across it, a band of light sweeps over once, and its keyline lights. OK dips it; hitting
  the end of a row nudges it.
- **D-pad**: focus walks right, then the row slides; each row remembers its column. Back goes
  to the hero; Home on the home screen does the same.
- **Tab bar**: Up (or Back) from the hero moves into it. A cream pill slides between tabs, and
  resting on a tab for a moment switches the page; OK or Down steps into it. **Apps** is a full
  grid of every app (the All apps icon opens it too). Search, Shows, Movies and Library are
  placeholders until TMDB arrives.

## Build and run

Needs JDK 17+ (Android Studio's `jbr` works) and the Android SDK with platform 37 and
build-tools 36.0.0; `local.properties` points at the SDK.

```bash
./gradlew assembleRelease
```

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

```bash
adb shell am start -n com.shivansh.mibox/.HomeActivity
```

The release build is minified and signed with the debug key, which is fine for sideloading.
Installing it does not make it the Home app; that is a separate, deliberate step on the box
(disable the stock launcher over ADB, keep network ADB on to undo it).

## Layout of the code

| Where | What |
| --- | --- |
| `HomeActivity` | Wires the screen; owns D-pad, Back and Home handling |
| `ui/Navigator` | Row/column focus, column memory, row sliding |
| `ui/TabBar` | The glass tab bar and its sliding focus pill |
| `ui/Backdrop` | Two crossfading backdrop slots, decode queue, fake glass |
| `ui/FocusCard` | A card and its focus motion (spring lift, tilt, glare, sweep, keyline, caption) |
| `ui/Previews` | Muted preview clips on a SurfaceView behind the UI |
| `ui/Hero`, `ui/Shelf` | The hero banner and the apps row's top shelf |
| `ui/rows/Adapters` | The four rows |
| `ui/Images`, `ui/AppArt` | Card art and app banners, decoded off the main thread |
| `data/Demo` | Phase 1 content; phase 2 swaps in TMDB |
| `data/InstalledApps` | The app list, cached in prefs and refreshed via LauncherApps |

`activity_home.xml` uses 1dp = 2 mockup px (the box runs at 960x540dp); code converts mockup
px with `Ui.px`.

## Performance rules for the Mali-450

- Animate transforms (scale, translation, the brief tilt) and alpha. The only redraws while
  animating are the focused card's glare and sweep (one small view) and the slide dots; text
  that changes per slide is the only layout.
- Backdrops decode at 1280x720 RGB_565 on a background thread; at most two full-screen bitmaps
  exist (the one on screen and the one fading in), and a new decode waits for the fade.
- No live blur: each backdrop gets a 240x135 pre-blurred copy, stretched for the glass layers.
  Glows and shadows are pre-blurred alpha masks, tinted per show.
- Translucent plain views use `forceHasOverlappingRendering(false)`, or HWUI gives each one an
  offscreen layer every frame.
- Cards draw their own rounded shapes (`RoundedImage`, rounded backgrounds) with scrims baked
  into the bitmap; the glare and sweep are shaders moved inside the rounded shape, so nothing is
  clipped. The 3D tilt only runs for the half second after focus lands.
- Previews play on a SurfaceView, which the box composites in hardware under the window; the art
  fades away to reveal it and its zoom pauses. With a preview playing on a show row the UI draws
  no frames at all; the hero's slide dots tick at 10 fps rather than 60.
- On the emulator (whose GL translation charges heavily per draw) the hero issues a frame in
  ~15 ms; real numbers need the Mi Box.
- `BackdropSlot.DRIFT` turns off the slow zoom, which keeps the GPU drawing every frame.

## Phase 2 hooks

- `Previews` plays bundled clips; phase 2 feeds it TMDB trailers.
- `Demo` is the only content source; TMDB sign-in, watchlist and favorites replace it.
- Show cards open the provider's app for now (`openProvider`); deep links come later.

Fonts (Geist, Instrument Serif, Big Shoulders) are under the SIL Open Font License; see
`licenses/fonts`.

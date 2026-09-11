<h1 align="center">2009Scape Mobile - DaveRune's Fork</h1>

## A mobile app to play a more recent 2009Scape with bug fixes.

<p align="center">
  <img src="docs/screenshots/gameplay.jpg" alt="Falador, running on a tablet"/>
</p>
<p align="center">
  <img src="docs/screenshots/view-distance.jpg" alt="Extended view distance across Falador"/>
</p>
<p align="center">
  <img src="docs/screenshots/world-map.jpg" alt="The world map, working"/>
</p>

https://github.com/user-attachments/assets/8d1181a8-fec6-4163-9769-80c54d956de3

## What is this?

An unofficial fork of [2009scape/2009Scape-mobile](https://github.com/2009scape/2009Scape-mobile), which is itself an unofficial Android app for playing 2009Scape, built on PojavLauncher.

I built this for me. I wanted to play 2009Scape on my tablet, I found the existing app had been sitting untouched since 2024, and fixed the things that were stopping me enjoying it. It works well enough now that it seemed worth sharing.

I am not committing to maintaining it, supporting it, or taking requests. There is no roadmap and there are no promises. If it is useful to you, please use it and I hope you enjoy it too.

I used AI.

## Added

| | |
|---|---|
| Interface scale | The world renders at your device's full resolution while the interface is drawn larger, so nothing is soft and nothing is too small to tap. Slider in Settings. |
| View distance | Stock is 28 tiles, this defaults to 48 and goes to 103, the whole loaded map. Use `::vd #` in game, and `::vd fog` to turn the fog off. Both take effect straight away. |
| Up to date client | The client is rebuilt from source with two years of upstream desktop fixes merged in, including a sleep in the game loop, roof hiding and correct chat icons. |
| Drag with a finger | Scroll bars and inventory items can be dragged by finger. Before this only a pen could. |
| Camera controls | Pan sensitivity and invert Y, both in Control customization. |
| Camera smoothing | On by default and eased over 300ms. `::cs <ms>` changes it, 0 to 600, and `::cs 0` turns it off. |
| Pinch zoom | With its own sensitivity slider. |
| Keep running in the background | The game stays connected while you are in another app. Changable in Settings. |
| Optional system UI | Keeps the status bar and the navigation buttons on screen. Changable in Settings. |
| Nameplates | Craftify ships with the app. |
| Better keyboard integration | The on-screen keyboard covered game, now the game scales to fit. |

## Fixed

| | |
|---|---|
| Touch accuracy | Now accurate and not offset slightly. If you run in any kind of windowed mode and resize, it'll need a restart. |
| Stylus / s-pen | The pen moved the cursor but never clicked. Now supports left and right click. |
| World map | It draws properly now, and pans by finger. Use two fingers up and down to zoom, pinch is unreliable. |
| Music | It doesn't stop any more, unless it would normally in game. |
| Sound effects and ambient | They now both play and don't cut out. |
| Background audio | The game kept playing with the app minimised or the screen off, not any more. |
| Camera controls | Improved gesture recognition for pan and pinch to zoom, and the judder is gone. |
| Two finger scrolling | A new scroll started from wherever the last one ended and flew off in the wrong direction. Sensitivity and inversion are in Control customization. |
| Launcher buttons | The HD and SD hitboxes did not line up with the artwork on most screens. |
| Header bar | Now positioned correctly |
| Settings | Previously no way out of the screen, and the back button crashed on sub-pages. |
| Updating the app | An update could not replace the parts of the game the app installs on first run, which is why the 3.0 audio fix never reached anyone. It can now. |

## Known and not fixed

Battery use might still be high.

## Install

Grab the APK from [Releases](https://github.com/DaveRune/2009Scape-mobile/releases) and install it. Android will warn you about installing outside the Play Store, which is expected.

## Build

Needs the Android SDK with platform 33, build tools 33.0.2 and NDK 25.2.9519653, and a JDK 17.

```bash
./gradlew :app_pojavlauncher:assembleDebug
```

## Where everything comes from

| Part | Source |
|---|---|
| This app | Forked from [2009scape/2009Scape-mobile](https://github.com/2009scape/2009Scape-mobile), which is based on PojavLauncher |
| The game client | [DaveRune/rt4-client](https://github.com/DaveRune/rt4-client), forked from [downthecrop/rt4-client](https://gitlab.com/downthecrop/rt4-client) |
| The desktop client | [2009scape/rt4-client](https://gitlab.com/2009scape/rt4-client) |
| The server | 2009Scape, which I have nothing to do with |

Licensed GPL-3.0, same as the project it came from. See [LICENSE](LICENSE).

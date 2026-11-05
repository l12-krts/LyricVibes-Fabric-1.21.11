# LyricVibes
### A mod made by krts.dev

_PS. sorry if the code is not well documented i tried (readme ai gen cuz yk im to bored to write)_

Synced lyrics of whatever you're listening to, floating in your Minecraft world.

LyricVibes watches what Windows is playing (Spotify, any browser tab, local players), finds time-synced lyrics and lets each line spring into the world word by word. A small now playing chip with a live waveform sits at the top of the screen.

## Features

- **World lyrics**: four modes, all world-anchored so they move with the camera like real objects.
  - **Sky**: big, soft lines high up in the direction you're facing.
  - **Close**: a small line floating beside you that lazily follows with spring physics.
  - **Trail**: lines painted on the ground ahead of your path that you walk past.
  - **Orbit**: words slowly circle around you.
- **Word-by-word animation**: every word pops in on its own, glows in your accent color, then the line dissolves upward.
- **Now playing chip**: album art, title and artist, and a waveform driven by your real system audio. It grows when a song changes.
- **Media keys**: play/pause, next, previous, cycle overlay and open the menu. All customizable, with modifier combos.
- **Settings menu** built with PolyUI: Now Playing (transport controls), Lyrics, Overlay, Keybinds and About. Open it with Right Shift or the ♪ button on the pause screen.
- **Accent from album art**: the UI and the currently sung word take the cover's dominant color.
- **Unobtrusive**: optional render-through-blocks, plus opacity, size and distance sliders. Lyrics dim while paused and hide with F1.

## Default keys

| Action | Keys |
| --- | --- |
| Play / Pause | `Alt + P` |
| Next track | `Alt + .` |
| Previous track | `Alt + ,` |
| Cycle overlay | `Alt + L` |
| Open menu | `Right Shift` |

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) for 1.21.11.
2. Put these in `mods/`: **Fabric API**, **Fabric Language Kotlin** and `lyricvibes-<version>.jar`.
3. Play music, join a world, and the lyrics appear.

## Building

Requirements: JDK 21, Rust (stable, `x86_64-pc-windows-msvc`).

```bash
./gradlew build
```

The Gradle build also compiles the native bridge (`native/bridge`, Rust) and packs it into the jar. Output: `build/libs/lyricvibes-<version>.jar`.

```bash
./gradlew runClient
```

## How it works (short)

- `native/bridge` is a tiny Rust sidecar. It reads Windows' Global System Media Transport Controls (title, artist, art, timeline, controls) and captures system audio through WASAPI loopback to compute 16 FFT bands. It streams JSON lines over stdio.
- The mod launches the bridge, extrapolates playback position smoothly and fetches synced lyrics from [LRCLIB](https://lrclib.net). Results are cached in `config/lyricvibes/cache`.
- World text is drawn in `WorldRenderEvents.END_MAIN` with a bundled Poppins TTF font. The HUD and menu are PolyUI drawn through NanoVG right before the buffer swap.

## Credits and licenses

- LyricVibes: MIT (see `LICENSE`).
- [PolyUI](https://github.com/Polyfrost/polyui-jvm) by Polyfrost: LGPL-3.0. Bundled unmodified, plus a vendored copy of its NanoVG renderer (`src/main/kotlin/org/polyfrost/...`, LGPL-3.0, visibility tweaks only).
- [LWJGL](https://www.lwjgl.org/) NanoVG bindings: BSD-3.
- Poppins font: SIL Open Font License 1.1.
- Lyrics: [LRCLIB](https://lrclib.net) community database.

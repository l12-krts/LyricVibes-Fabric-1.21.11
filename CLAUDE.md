# LyricVibes: notes for Claude

Fabric 1.21.11 client mod (Kotlin + a few Java mixins) that shows synced lyrics of the currently playing song in the world, plus a PolyUI "now playing" chip and settings menu. Windows only.

## Rules from the user

- Comments: **very** sparse, 2-4 words each. No comment spam.
- Ask clarifying questions before big changes. Plan first, then write everything in one go.
- HANDOFF.md and LYRICS.md are gitignored local docs. Keep them updated.
- No Spotify `sp_dc` / TOTP-secret token flow (it bypasses Spotify auth). Don't add it.

## Toolchain quirks (this machine)

- Build with **JDK 21**. `gradle.properties` pins `org.gradle.java.home`. Loom **1.17.21** is the newest that runs on Java 21 (1.18+ needs Java 25).
- Gradle needs `-Djdk.net.unixdomain.tmpdir=<short dir>` via `GRADLE_OPTS`/`JAVA_TOOL_OPTIONS`, or it fails with "Unable to establish loopback connection" inside the sandbox.
- Never write `gradle.properties` with PowerShell `Set-Content -Encoding utf8`: the BOM makes Gradle ignore the first line (heap → OOM).
- `genSources` OOMs. Inspect MC classes with `javap` on `.gradle/loom-cache/minecraftMaven/.../minecraft-merged-*.jar` instead. Fabric API sources jars live in `.gradle/loom-cache/remapped_mods`.
- Yarn mappings (`1.21.11+build.6`). 1.21.11 is the last Yarn version; a port to 26.x means switching to Mojang mappings.

## Layout

- `native/bridge/`: Rust sidecar (GSMTC media session + WASAPI loopback FFT). JSON lines over stdio. Built by the Gradle task `buildBridge` and packed as `natives/lyricvibes-bridge.exe`. It's extracted to `config/lyricvibes/bin/` at runtime.
- `media/`: `MediaBridge` (process, parsing, commands), `NowPlaying`, `ArtColor` (cover accent).
- `lyrics/`: `LrcParser` (LRC + enhanced word tags, plain → pseudo-timed), `TrackQuery` (title cleanup), `LrcLibProvider`, `LyricsService` (cache, status), `PlaybackClock` (smoothed position).
- `world/WorldLyricsRenderer`: `WorldRenderEvents.END_MAIN`, modes SKY/CLOSE/TRAIL/ORBIT, per-word springs, font `lyricvibes:lyrics` (Poppins SemiBold TTF).
- `ui/`: `Overlay` (single PolyUI instance, NanoVG drawn right before `Window.swapBuffers` via `MinecraftClientMixin`, `GlState` save/restore), `Chip`, `MenuRoot` + `Widgets` (custom spring-driven PolyUI Drawables, positioned manually every frame), `MenuScreen` (MC Screen forwarding input).
- `anim/Spring`: iOS-style spring (response, dampingFraction), presets DEFAULT/SNAPPY/BOUNCY/SMOOTH/GENTLE.
- `input/Keybinds`: custom combo binds via `KeyboardMixin` (only when no screen is open).
- `org/polyfrost/.../NVGRenderer.kt`: vendored PolyUI NanoVG renderer (not published to Maven). LGPL; only visibility changed.

## PolyUI gotchas

- Components need a non-zero `at` and `size` at construction, or the flex layout repositions them. We set x/y/size every frame and force `clipped = false`.
- Children must be passed to the `Drawable` constructor (`addChild` on a childless component throws).
- `McWindow.supportsRenderPausing() = false`: we redraw every frame because MC overwrites the backbuffer.
- `NVGRenderer` is a singleton. Never call `polyUI.cleanup()` while the overlay lives.
- Text drawn with `NVG_ALIGN_TOP`. `Paint.text` centers vertically with `size * 0.62`.
- The flex layout resizes the master Group at setup. `Overlay` re-applies master size and `visibleSize` every frame, or input and drawing drift apart.
- `rayCheck` only descends into a child if the parent `isInside`. Always set `visibleSize` when placing components manually.
- Don't construct components with zero width or height (auto-size path breaks hit-testing).

## Testing in-game

Computer-use can target the dev client via the exe path `C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot\bin\java.exe`. Injected Right Shift arrives as plain Shift, so open the menu through the pause screen ♪ button (top-left).

## Commands

```bash
./gradlew build        # mod + bridge
./gradlew runClient    # dev client
./gradlew runClient -PquickPlay=New_World -Pdemo=true   # straight into world, fake track
cd native/bridge && cargo build --release
```

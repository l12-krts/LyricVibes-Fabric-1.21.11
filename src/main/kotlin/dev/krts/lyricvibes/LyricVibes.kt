package dev.krts.lyricvibes

import dev.krts.lyricvibes.config.LvConfig
import dev.krts.lyricvibes.lyrics.LyricsService
import dev.krts.lyricvibes.media.MediaBridge
import dev.krts.lyricvibes.spotify.SpotifyLink
import dev.krts.lyricvibes.ui.MenuScreen
import dev.krts.lyricvibes.world.WorldLyricsRenderer
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.screen.GameMenuScreen
import net.minecraft.client.gui.tooltip.Tooltip
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text
import org.slf4j.LoggerFactory

object LyricVibes : ClientModInitializer {
    const val ID = "lyricvibes"
    val LOGGER = LoggerFactory.getLogger("LyricVibes")!!
    val VERSION: String by lazy {
        FabricLoader.getInstance().getModContainer(ID).map { it.metadata.version.friendlyString }.orElse("dev")
    }

    lateinit var config: LvConfig
        private set

    override fun onInitializeClient() {
        config = LvConfig.load()
        MediaBridge.start()
        SpotifyLink.start()

        ClientTickEvents.END_CLIENT_TICK.register { LyricsService.onTrack(MediaBridge.nowPlaying) }
        WorldRenderEvents.END_MAIN.register(WorldLyricsRenderer::render)
        ClientLifecycleEvents.CLIENT_STOPPING.register {
            SpotifyLink.stop()
            MediaBridge.stop()
        }

        ScreenEvents.AFTER_INIT.register { client, screen, _, _ ->
            if (screen !is GameMenuScreen) return@register
            Screens.getButtons(screen).add(
                ButtonWidget.builder(Text.literal("♪")) { client.setScreen(MenuScreen(screen)) }
                    .dimensions(4, 4, 20, 20)
                    .tooltip(Tooltip.of(Text.translatable("lyricvibes.button.open")))
                    .build(),
            )
        }
        LOGGER.info("LyricVibes {} ready", VERSION)
    }
}

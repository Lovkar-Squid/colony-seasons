package me.lovkar.seasons.client;

import com.minecolonies.api.entity.ModEntities;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.client.render.RenderBipedCitizen;
import me.lovkar.seasons.ColonySeasons;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/**
 * Client-only hooks, registered by NeoForge on the physical client only (the {@code value = Dist.CLIENT} of the
 * annotation, and {@link #init} is called by the mod class only when {@code FMLEnvironment.dist.isClient()}), so
 * this class and the Minecraft client classes it names are never loaded on a dedicated server.
 */
@EventBusSubscriber(modid = ColonySeasons.MODID, value = Dist.CLIENT)
public final class SeasonsClient {
    private SeasonsClient() {
    }

    /**
     * The mod-bus side of the client: the clothes models, the clothes layer on MineColonies' citizen renderer, the
     * hat allow-list and the leaves' colour. The annotation above only reaches the game bus, and these are mod-bus events.
     */
    public static void init(final IEventBus modEventBus) {
        modEventBus.addListener(SeasonsClient::registerLayers);
        modEventBus.addListener(SeasonsClient::addLayers);
        modEventBus.addListener(SeasonsClient::registerReloadListeners);
        // S10d: the ornament and tinsel leaves draw the game's spruce leaves, tinted the evergreen colour
        LeafColors.init(modEventBus);
    }

    /** The state belongs to the world that sent it: forget it when the player leaves. */
    @SubscribeEvent
    public static void onLoggingOut(final ClientPlayerNetworkEvent.LoggingOut event) {
        ClientSeasonState.clear();
    }

    private static void registerLayers(final EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(ScarfModel.LAYER, ScarfModel::createLayer);
        event.registerLayerDefinition(WoolHatModel.LAYER, WoolHatModel::createLayer);
    }

    /** One clothes layer on each of MineColonies' two citizen renderers (citizens and visitors share the class). */
    private static void addLayers(final EntityRenderersEvent.AddLayers event) {
        addLayer(event, ModEntities.CITIZEN, "citizen");
        addLayer(event, ModEntities.VISITOR, "visitor");
    }

    private static void addLayer(final EntityRenderersEvent.AddLayers event,
                                 final EntityType<? extends AbstractEntityCitizen> type, final String what) {
        final EntityRenderer<?> renderer = type == null ? null : event.getRenderer(type);
        if (renderer instanceof RenderBipedCitizen biped) {
            biped.addLayer(new ClothesLayer(biped, event.getEntityModels()));
        } else {
            ColonySeasons.LOGGER.warn("Colony Seasons: MineColonies' {} renderer is not the citizen renderer this version knows ({}), no winter clothes on {}s",
                    what, renderer == null ? "none" : renderer.getClass().getName(), what);
        }
    }

    private static void registerReloadListeners(final RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) ClothesLayer::reloadHatModels);
    }
}

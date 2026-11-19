package me.lovkar.seasons.effects;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The effects on the game (design section 8, chunk S8): festival happiness, winter crop slowdown and winter food need.
 * Every one is off by default in the server config; with all three off nothing here changes anything (the pass finds the
 * switches off and returns, and no crop event is touched).
 */
public final class Effects {

    /** Ticks between two passes over the colonies (five seconds): cheap, and often enough to put a modifier back at once. */
    public static final int TICKS = 100;

    private static boolean registered;

    private Effects() {
    }

    /** Hooks the effects onto the game bus; called once from the mod constructor. */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        NeoForge.EVENT_BUS.addListener(Effects::onServerStarting);
        NeoForge.EVENT_BUS.addListener(Effects::onServerTick);
        NeoForge.EVENT_BUS.addListener(CropEffect::onGrow);
    }

    private static void onServerStarting(final ServerStartingEvent event) {
        FoodEffect.reset();
    }

    private static void onServerTick(final ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % TICKS == 0) {
            pass();
        }
    }

    /** One pass of the effects that work on the colonies' citizens (the probe may call it). */
    public static void pass() {
        HappinessEffect.applyAll();
        FoodEffect.applyAll();
    }
}

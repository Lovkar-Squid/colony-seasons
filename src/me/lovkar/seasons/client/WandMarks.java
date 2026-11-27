package me.lovkar.seasons.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.item.DecoratorWandItem;
import me.lovkar.seasons.network.StreetMarksPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;

/**
 * The wand's view of the marks (0.2.0, Q11), the client's half: the list the server last sent
 * ({@link StreetMarksPayload}) is drawn as line boxes round each mark's cells while the player holds the Decorator's wand,
 * one colour per type. Nothing is drawn otherwise; the list is forgotten when the player leaves the world. The mod has no
 * particles of its own (the owner's answer of 21 Nov 2026), so the marks are outlines.
 */
@EventBusSubscriber(modid = ColonySeasons.MODID, value = Dist.CLIENT)
public final class WandMarks {
    private static volatile List<StreetMarksPayload.Entry> marks = List.of();

    private WandMarks() {
    }

    /** The server's list for the colony the player stands in. */
    public static void accept(final StreetMarksPayload payload) {
        marks = payload.entries();
    }

    /** The marks held now (the client probe reads them). */
    public static List<StreetMarksPayload.Entry> marks() {
        return marks;
    }

    @SubscribeEvent
    public static void onLoggingOut(final ClientPlayerNetworkEvent.LoggingOut event) {
        marks = List.of();
    }

    @SubscribeEvent
    public static void onRender(final RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        final List<StreetMarksPayload.Entry> now = marks;
        final LocalPlayer player = Minecraft.getInstance().player;
        if (now.isEmpty() || player == null
                || !DecoratorWandItem.is(player.getMainHandItem()) && !DecoratorWandItem.is(player.getOffhandItem())) {
            return;
        }
        final Vec3 camera = event.getCamera().getPosition();
        final PoseStack pose = event.getPoseStack();
        final MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        final VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        for (final StreetMarksPayload.Entry entry : now) {
            final float[] c = colour(entry.type());
            LevelRenderer.renderLineBox(pose, lines, box(entry), c[0], c[1], c[2], 1.0F);
        }
        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }

    /** The box round a mark's cells: one cell, a post's two, a garland's line. */
    static AABB box(final StreetMarksPayload.Entry entry) {
        final BlockPos a = entry.pos();
        BlockPos b = entry.to() != null ? entry.to() : a;
        if ("lantern_post".equals(entry.type())) {
            b = a.above();
        }
        return new AABB(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1).inflate(0.004);
    }

    /** One colour per type: snowmen white and pale blue, the post gold, the garland green, the planter brown. */
    static float[] colour(final String type) {
        return switch (type) {
            case "snowman_a" -> new float[] {1.0F, 1.0F, 1.0F};
            case "snowman_b" -> new float[] {0.6F, 0.8F, 1.0F};
            case "lantern_post" -> new float[] {1.0F, 0.8F, 0.2F};
            case "garland" -> new float[] {0.2F, 0.85F, 0.3F};
            default -> new float[] {0.8F, 0.5F, 0.2F};
        };
    }
}

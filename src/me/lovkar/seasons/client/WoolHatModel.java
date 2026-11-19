package me.lovkar.seasons.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import me.lovkar.seasons.ColonySeasons;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;

/**
 * The wool hat: a snug knitted crown over the top of the head, a folded cuff and a pompom.
 *
 * <p>Drawn in the space of the citizen's {@code head} part, measured from a standard head box ({@code -4, -8, -4}
 * to {@code 4, 0, 4}); {@link ClothesLayer} moves it onto the real head of each model. The body of the hat covers
 * the head down to the brow (y -5.15), a little wider than MineColonies' hair layer (the head grown by 0.5); a
 * narrower top tier rounds it off and the cuff stands out round the bottom. It stays above eye-level goggles.
 * Texture 64x32 at one texel per pixel (tools/gen_clothes.py). tools/entity_preview.py reads the boxes below out
 * of this file, so keep one {@code addOrReplaceChild} per part.</p>
 */
public final class WoolHatModel {
    public static final ModelLayerLocation LAYER =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(ColonySeasons.MODID, "wool_hat"), "main");
    public static final int TEX_WIDTH = 64;
    public static final int TEX_HEIGHT = 32;

    private final ModelPart hat;

    public WoolHatModel(final ModelPart root) {
        this.hat = root.getChild("hat");
    }

    public static LayerDefinition createLayer() {
        final MeshDefinition mesh = new MeshDefinition();
        final PartDefinition root = mesh.getRoot();
        final PartDefinition hat = root.addOrReplaceChild("hat", CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-4.0F, -8.4F, -4.0F, 8.0F, 3.0F, 8.0F, new CubeDeformation(0.6F, 0.25F, 0.6F)),
                PartPose.ZERO);
        hat.addOrReplaceChild("crown", CubeListBuilder.create()
                        .texOffs(0, 22).addBox(-4.0F, -9.4F, -4.0F, 8.0F, 1.0F, 8.0F, new CubeDeformation(-0.3F, 0.0F, -0.3F)),
                PartPose.ZERO);
        hat.addOrReplaceChild("cuff", CubeListBuilder.create()
                        .texOffs(0, 12).addBox(-4.0F, -6.9F, -4.0F, 8.0F, 2.0F, 8.0F, new CubeDeformation(0.85F, -0.15F, 0.85F)),
                PartPose.ZERO);
        hat.addOrReplaceChild("pompom", CubeListBuilder.create()
                        .texOffs(32, 0).addBox(-1.0F, -11.0F, -1.0F, 2.0F, 2.0F, 2.0F, new CubeDeformation(0.0F, 0.0F, 0.0F)),
                PartPose.ZERO);
        return LayerDefinition.create(mesh, TEX_WIDTH, TEX_HEIGHT);
    }

    /** Draws the hat at the current pose (the layer has already moved onto the head). */
    public void render(final PoseStack poseStack, final VertexConsumer buffer, final int light, final int overlay) {
        hat.render(poseStack, buffer, light, overlay);
    }
}

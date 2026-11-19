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
 * The scarf: a knitted wrap round the neck and one end hanging down the front, on the left of the chest.
 *
 * <p>Drawn in the space of the citizen's {@code body} part, measured from the top of a standard body box
 * ({@code -4, 0, -2} to {@code 4, 12, 2}); {@link ClothesLayer} moves it onto the real top of the body of each
 * model. The wrap is a little wider than the body (it lies on the shoulders, over apron straps and cape collars)
 * and starts just above the neck line, inside the head, so it never shows in front of the face. Female models
 * carry a {@code breast} box in front of the chest, so their end ({@code tail_chest}) hangs a pixel further out.</p>
 *
 * <p>Texture 64x32 at one texel per pixel (tools/gen_clothes.py paints it). tools/entity_preview.py reads the
 * boxes below out of this file for the preview renders, so keep one {@code addOrReplaceChild} per part with its
 * {@code texOffs(...).addBox(...)} chain.</p>
 */
public final class ScarfModel {
    public static final ModelLayerLocation LAYER =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(ColonySeasons.MODID, "scarf"), "main");
    public static final int TEX_WIDTH = 64;
    public static final int TEX_HEIGHT = 32;

    private final ModelPart scarf;
    private final ModelPart tail;
    private final ModelPart tailChest;

    public ScarfModel(final ModelPart root) {
        this.scarf = root.getChild("scarf");
        this.tail = scarf.getChild("tail");
        this.tailChest = scarf.getChild("tail_chest");
    }

    public static LayerDefinition createLayer() {
        final MeshDefinition mesh = new MeshDefinition();
        final PartDefinition root = mesh.getRoot();
        final PartDefinition scarf = root.addOrReplaceChild("scarf", CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-4.0F, 0.0F, -2.0F, 8.0F, 2.0F, 4.0F, new CubeDeformation(0.6F, 0.4F, 0.8F)),
                PartPose.ZERO);
        scarf.addOrReplaceChild("tail", CubeListBuilder.create()
                        .texOffs(0, 8).addBox(1.0F, 2.0F, -3.0F, 2.0F, 5.0F, 1.0F, new CubeDeformation(0.25F, 0.25F, 0.25F)),
                PartPose.ZERO);
        scarf.addOrReplaceChild("tail_chest", CubeListBuilder.create()
                        .texOffs(0, 8).addBox(1.0F, 2.0F, -4.0F, 2.0F, 5.0F, 1.0F, new CubeDeformation(0.25F, 0.25F, 0.25F)),
                PartPose.ZERO);
        return LayerDefinition.create(mesh, TEX_WIDTH, TEX_HEIGHT);
    }

    /**
     * Draws the scarf at the current pose (the layer has already moved to the top of the body).
     *
     * @param chest the model has a {@code breast} part: hang the end over it
     */
    public void render(final PoseStack poseStack, final VertexConsumer buffer, final int light, final int overlay,
                       final boolean chest) {
        tail.visible = !chest;
        tailChest.visible = chest;
        scarf.render(poseStack, buffer, light, overlay);
    }
}

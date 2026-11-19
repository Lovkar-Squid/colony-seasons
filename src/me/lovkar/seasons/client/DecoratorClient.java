package me.lovkar.seasons.client;

import com.minecolonies.api.client.render.modeltype.CitizenModel;
import com.minecolonies.api.client.render.modeltype.SimpleModelType;
import com.minecolonies.api.client.render.modeltype.registry.IModelTypeRegistry;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.colony.DecoratorSetup;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Client-only wiring of the Decorator's look: his model layer and his MineColonies model type. Only called on the
 * physical client ({@link DecoratorSetup#init} checks the dist), so a dedicated server never loads this class.
 *
 * <p>MineColonies resolves the textures itself from the model id:
 * {@code minecolonies:textures/entity/citizen/<style>/decorator<male|female>1<_a|_b|_d|_w>.png}, falling back to the
 * {@code default} style folder, which is the only one we ship bodies in (tools/gen_decorator_skin.py).</p>
 */
public final class DecoratorClient {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(DecoratorSetup.MODEL_ID, "main");

    private DecoratorClient() {
    }

    public static void init(final IEventBus modBus) {
        modBus.addListener(DecoratorClient::registerLayers);
        modBus.addListener(DecoratorClient::registerModelType);
    }

    private static void registerLayers(final EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(LAYER, DecoratorModel::createMesh);
    }

    /** MineColonies registers its own model types while the renderers are built; ours follows in AddLayers. */
    private static void registerModelType(final EntityRenderersEvent.AddLayers event) {
        IModelTypeRegistry.getInstance().register(new SimpleModelType(DecoratorSetup.MODEL_ID, 1,
                new DecoratorModel(event.getEntityModels().bakeLayer(LAYER)),
                new DecoratorModel(event.getEntityModels().bakeLayer(LAYER))));
        ColonySeasons.LOGGER.info("Decorator citizen model registered");
    }

    /**
     * The Decorator: a knitted cap, a linen shirt under an evergreen apron with a red trim, a belt pocket with
     * scissors and a coil of string. One mesh for both genders (the faces come from the texture); 128x64 texture,
     * layout in tools/gen_decorator_skin.py:
     * <pre>
     *   head (0,0) 8x8x8     cap = the vanilla hat layer (32,0) 8x8x8, inflated 0.5
     *   body (16,16) 8x12x4  right arm (40,16) 4x12x4  right leg (0,16) 4x12x4
     *   left arm (32,48)     left leg (16,48)          pocket (64,0) 3x3x1 on the apron's front
     * </pre>
     * The cap is the model's {@code hat} part on purpose: MineColonies hides that part when a helmet (or its own
     * December hat) is shown, so the two never sit inside each other.
     */
    public static class DecoratorModel extends CitizenModel<AbstractEntityCitizen> {

        public DecoratorModel(final ModelPart part) {
            super(part);
        }

        public static LayerDefinition createMesh() {
            final MeshDefinition mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F);
            final PartDefinition root = mesh.getRoot();
            final PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create()
                            .texOffs(16, 16).addBox(-4.0F, 0.0F, -2.0F, 8.0F, 12.0F, 4.0F, CubeDeformation.NONE),
                    PartPose.ZERO);
            // the belt pocket on the front of the apron, clear of both arms
            body.addOrReplaceChild("pocket", CubeListBuilder.create()
                            .texOffs(64, 0).addBox(-3.5F, 6.0F, -3.0F, 3.0F, 3.0F, 1.0F, CubeDeformation.NONE),
                    PartPose.ZERO);
            // the left limbs have their own faces (the 64x64 player layout), not mirrored copies of the right ones
            root.addOrReplaceChild("right_arm", CubeListBuilder.create()
                            .texOffs(40, 16).addBox(-3.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F, CubeDeformation.NONE),
                    PartPose.offset(-5.0F, 2.0F, 0.0F));
            root.addOrReplaceChild("left_arm", CubeListBuilder.create()
                            .texOffs(32, 48).addBox(-1.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F, CubeDeformation.NONE),
                    PartPose.offset(5.0F, 2.0F, 0.0F));
            root.addOrReplaceChild("right_leg", CubeListBuilder.create()
                            .texOffs(0, 16).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, CubeDeformation.NONE),
                    PartPose.offset(-1.9F, 12.0F, 0.0F));
            root.addOrReplaceChild("left_leg", CubeListBuilder.create()
                            .texOffs(16, 48).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F, CubeDeformation.NONE),
                    PartPose.offset(1.9F, 12.0F, 0.0F));
            return LayerDefinition.create(mesh, 128, 64);
        }
    }
}

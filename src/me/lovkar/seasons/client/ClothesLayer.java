package me.lovkar.seasons.client;

import com.minecolonies.api.client.render.modeltype.CitizenModel;
import com.minecolonies.api.colony.ICitizenDataView;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.mojang.blaze3d.vertex.PoseStack;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.SeasonsClientConfig;
import me.lovkar.seasons.network.SeasonStatePayload;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * The one extra render layer on MineColonies' citizen renderer (citizens and visitors): the winter scarf, the
 * festive scarf and the wool hat (design section 7). Client only; nothing is saved on the colonist and no item
 * goes into any slot. What to draw comes from {@link ClothesRules}; this class gathers its inputs and draws.
 *
 * <p>MineColonies swaps the renderer's model for every citizen ({@code setupMainModelFrom}), so the parent model
 * is read every frame and never kept. The scarf follows the model's {@code body} part and the hat its
 * {@code head} part, each moved onto the first box of that part, because a few models (the courier, leaning under
 * his backpack) build their body or head away from the usual place. Children get the scaling the citizen model
 * itself applies to a young model, so the clothes shrink with them instead of floating at adult height.</p>
 */
public final class ClothesLayer extends RenderLayer<AbstractEntityCitizen, CitizenModel<AbstractEntityCitizen>> {

    /** Where the allow-list lives, in this mod's assets (a resource pack may replace it). */
    public static final ResourceLocation HAT_MODELS_FILE =
            ResourceLocation.fromNamespaceAndPath(ColonySeasons.MODID, "clothes/hat_models.json");

    /** The standard first boxes the scarf and the hat are drawn against (HumanoidModel's head and body). */
    private static final float STANDARD_BODY_TOP = 0.0F;
    private static final float STANDARD_HEAD_TOP = -8.0F;

    /** AgeableListModel's numbers for a HumanoidModel (CitizenModel does not change them). */
    private static final float BABY_HEAD_SCALE = 1.5F / 2.0F;
    private static final float BABY_HEAD_Y = 16.0F / 16.0F;
    private static final float BABY_BODY_SCALE = 1.0F / 2.0F;
    private static final float BABY_BODY_Y = 24.0F / 16.0F;

    private static volatile HatModels hatModels = HatModels.NONE;
    /** Set on the first unexpected error, so a broken frame costs one log line and the clothes, never the game. */
    private static volatile boolean broken;

    private final ScarfModel scarf;
    private final WoolHatModel hat;
    /** The first box of a model part, as {centre x, top y, centre z}; parts are made once per model, so they key by identity. */
    private final Map<ModelPart, float[]> firstBoxes = new WeakHashMap<>();
    private final Map<String, RenderType> renderTypes = new HashMap<>();

    public ClothesLayer(final RenderLayerParent<AbstractEntityCitizen, CitizenModel<AbstractEntityCitizen>> parent,
                        final EntityModelSet models) {
        super(parent);
        this.scarf = new ScarfModel(models.bakeLayer(ScarfModel.LAYER));
        this.hat = new WoolHatModel(models.bakeLayer(WoolHatModel.LAYER));
    }

    @Override
    public void render(final PoseStack poseStack, final MultiBufferSource buffers, final int light,
                       final AbstractEntityCitizen citizen, final float limbSwing, final float limbSwingAmount,
                       final float partialTick, final float ageInTicks, final float netHeadYaw, final float headPitch) {
        if (broken) {
            return;
        }
        try {
            draw(poseStack, buffers, light, citizen);
        } catch (final RuntimeException | LinkageError unexpected) {
            broken = true;
            ColonySeasons.LOGGER.error("Colony Seasons: the winter clothes are switched off for this session, drawing them failed", unexpected);
        }
    }

    private void draw(final PoseStack poseStack, final MultiBufferSource buffers, final int light,
                      final AbstractEntityCitizen citizen) {
        final SeasonStatePayload state = ClientSeasonState.get();
        if (state == null || citizen.isInvisible()) {
            return;
        }
        final CitizenModel<AbstractEntityCitizen> model = getParentModel();
        final ClothesRules.Wearer wearer = new ClothesRules.Wearer(citizen.getCivilianID(),
                citizen.getPose() == Pose.SLEEPING, hatModel(citizen, model), head(citizen),
                chestArmour(citizen));
        final ClothesRules.Outfit outfit = ClothesRules.outfit(SeasonsClientConfig.showClothes(), state.season(),
                state.festivals(), wearer);
        if (outfit.isEmpty()) {
            return;
        }
        final int overlay = LivingEntityRenderer.getOverlayCoords(citizen, 0.0F);

        if (outfit.scarf() != null && model.body.visible) {
            poseStack.pushPose();
            if (model.young) {
                poseStack.scale(BABY_BODY_SCALE, BABY_BODY_SCALE, BABY_BODY_SCALE);
                poseStack.translate(0.0F, BABY_BODY_Y, 0.0F);
            }
            model.body.translateAndRotate(poseStack);
            moveOnto(poseStack, model.body, STANDARD_BODY_TOP);
            final boolean chest = model.body.hasChild("breast") && model.body.getChild("breast").visible;
            scarf.render(poseStack, buffers.getBuffer(renderType(ClothesRules.scarfTexture(outfit.scarf()))), light, overlay, chest);
            poseStack.popPose();
        }

        if (outfit.hat() != null && model.head.visible) {
            poseStack.pushPose();
            if (model.young) {
                poseStack.scale(BABY_HEAD_SCALE, BABY_HEAD_SCALE, BABY_HEAD_SCALE);
                poseStack.translate(0.0F, BABY_HEAD_Y, 0.0F);
            }
            model.head.translateAndRotate(poseStack);
            moveOnto(poseStack, model.head, STANDARD_HEAD_TOP);
            hat.render(poseStack, buffers.getBuffer(renderType(ClothesRules.hatTexture(outfit.hat()))), light, overlay);
            poseStack.popPose();
        }
    }

    /** The model and gender are on the allow-list, and the renderer is not drawing a player skin over this head. */
    private static boolean hatModel(final AbstractEntityCitizen citizen, final CitizenModel<AbstractEntityCitizen> model) {
        if (!model.head.visible) {
            return false;
        }
        final ICitizenDataView view = citizen.getCitizenDataView();
        if (view != null && view.getCustomTexture() != null) {
            return false;
        }
        final ResourceLocation id = citizen.getModelType();
        return id != null && hatModels.allows(id.toString(), citizen.isFemale());
    }

    /**
     * He wears body armour as MineColonies' armour layer draws it (CitizenArmorLayer.renderArmorPiece): the data
     * view's display armour for the chest first (the inventory's armour slot), else the entity's chest slot.
     */
    private static boolean chestArmour(final AbstractEntityCitizen citizen) {
        final ICitizenDataView view = citizen.getCitizenDataView();
        ItemStack chest = view == null ? ItemStack.EMPTY : view.getDisplayArmor(EquipmentSlot.CHEST);
        if (chest.isEmpty()) {
            chest = citizen.getItemBySlot(EquipmentSlot.CHEST);
        }
        return chest.getItem() instanceof ArmorItem;
    }

    /** What the head shows: the head slot, else MineColonies' own display armour (its holiday hat). */
    private static ClothesRules.Head head(final AbstractEntityCitizen citizen) {
        final boolean worn = !citizen.getItemBySlot(EquipmentSlot.HEAD).isEmpty();
        final ICitizenDataView view = citizen.getCitizenDataView();
        final boolean theirs = !worn && view != null && !view.getDisplayArmor(EquipmentSlot.HEAD).isEmpty();
        return ClothesRules.head(worn, theirs);
    }

    /** Moves from the standard first box of a head or body onto this part's own first box (same size, maybe elsewhere). */
    private void moveOnto(final PoseStack poseStack, final ModelPart part, final float standardTop) {
        final float[] box = firstBoxes.computeIfAbsent(part, ClothesLayer::firstBox);
        if (box.length == 3) {
            poseStack.translate(box[0] / 16.0F, (box[1] - standardTop) / 16.0F, box[2] / 16.0F);
        }
    }

    /** {centre x, top y, centre z} of a part's own first box, or an empty array when it has none. */
    private static float[] firstBox(final ModelPart part) {
        final float[][] found = {new float[0]};
        part.visit(new PoseStack(), (pose, path, index, cube) -> {
            if (path.isEmpty() && index == 0) {
                found[0] = new float[]{(cube.minX + cube.maxX) / 2.0F, cube.minY, (cube.minZ + cube.maxZ) / 2.0F};
            }
        });
        return found[0];
    }

    private RenderType renderType(final String texture) {
        return renderTypes.computeIfAbsent(texture, path ->
                RenderType.entityCutoutNoCull(ResourceLocation.fromNamespaceAndPath(ColonySeasons.MODID, path)));
    }

    /** Reads the hat allow-list (a client resource reload). A missing or broken file means no wool hats, and one log line. */
    public static void reloadHatModels(final ResourceManager resources) {
        final Optional<Resource> file = resources.getResource(HAT_MODELS_FILE);
        if (file.isEmpty()) {
            hatModels = HatModels.NONE;
            ColonySeasons.LOGGER.warn("Colony Seasons: {} is missing, no colonist gets a wool hat", HAT_MODELS_FILE);
            return;
        }
        try (Reader reader = file.get().openAsReader()) {
            hatModels = HatModels.parse(reader);
        } catch (final Exception unreadable) {
            hatModels = HatModels.NONE;
            ColonySeasons.LOGGER.warn("Colony Seasons: {} cannot be read ({}), no colonist gets a wool hat", HAT_MODELS_FILE,
                    unreadable.getMessage());
        }
    }
}

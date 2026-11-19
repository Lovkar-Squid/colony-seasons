package me.lovkar.seasons.ai;

import com.minecolonies.api.entity.ai.statemachine.AITarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIInteract;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.colony.BuildingDecorator;
import me.lovkar.seasons.colony.JobDecorator;
import me.lovkar.seasons.decor.DecorPhase;
import me.lovkar.seasons.decor.Kits;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The Decorator's day (design section 4). Every 20 ticks {@link #decide()} looks at the calendar and the hut:
 *
 * <ul>
 *   <li>the phase comes from {@link DecorPhase#of}: festival days are Hosting, an open decoration window is
 *       Decorating (S4 splits Tending out of it), decorations still up after the window are Taking down, anything
 *       else is the Off season;</li>
 *   <li>outside the Off season, every {@link #PHASE_VISIT_TICKS} ticks he goes through that phase's state. In this
 *       chunk the states are stubs that note the visit and come straight back: S4 fills in Decorating, Tending and
 *       Taking down, S7 Hosting and Launching;</li>
 *   <li>Preparing goes on in every phase when the hut's "prepare all year" is on, and from the first day of a window
 *       when it is off: for every kit below the stock cap he asks the colony for one batch of what is missing (async
 *       requests, MineColonies drops duplicates), and when his pack holds a whole batch he walks to the hut and
 *       assembles it.</li>
 * </ul>
 *
 * <p>Nothing that must survive is kept here: MineColonies resets this AI for sleep, rain and raids. The fields are
 * scratch (timers, a walk counter, the kit he was about to assemble); progress is the building's NBT, stock is items
 * in the hut and in his own pack. An assembly happens in one server tick, so a reset can never catch it halfway.</p>
 */
public class EntityAIWorkDecorator extends AbstractEntityAIInteract<JobDecorator, BuildingDecorator> {

    /** The Decorator's own states: assembling, and the phase states S4 and S7 fill in. */
    public enum DecoratorState implements IAIState {
        /** At the hut, turning one batch of materials into decorations. */
        ASSEMBLE(true),
        /** Putting decorations up (S4). */
        DECORATING(true),
        /** Once a game day, putting back what went missing (S4). */
        TENDING(true),
        /** Taking the decorations down after the window (S4). */
        TAKING_DOWN(true),
        /** Standing at the tree on festival days (S7). */
        HOSTING(false),
        /** The New Year show (S7). */
        LAUNCHING(false);

        private final boolean okayToEat;

        DecoratorState(final boolean okayToEat) {
            this.okayToEat = okayToEat;
        }

        @Override
        public boolean isOkayToEat() {
            return okayToEat;
        }
    }

    /** Ticks between decisions. Nothing here is urgent. */
    private static final int TICK_DELAY = 20;
    /** Ticks between two visits of the current phase's state. */
    public static final int PHASE_VISIT_TICKS = 200;
    /** Decisions spent walking to the hut before he assembles where he stands. */
    public static final int WALK_ATTEMPTS = 60;
    /** Delay after one batch: {@code ASSEMBLY_DELAY - ASSEMBLY_DELAY_PER_DEXTERITY x Dexterity}, at least {@code ASSEMBLY_DELAY_MIN}. */
    public static final int ASSEMBLY_DELAY = 60;
    public static final int ASSEMBLY_DELAY_PER_DEXTERITY = 2;
    public static final int ASSEMBLY_DELAY_MIN = 20;
    /** When neither the hut nor his pack has room for a batch, he waits this long before he looks again. */
    public static final int FULL_DELAY = 600;

    // scratch only: a reset of the AI loses these and nothing else
    private long nextPhaseVisit = Long.MIN_VALUE;
    private int walkAttempts;
    private Kits.Kit kitToAssemble;

    public EntityAIWorkDecorator(final @NotNull JobDecorator job) {
        super(job);
        super.registerTargets(
                new AITarget<IAIState>(AIWorkerState.IDLE, () -> AIWorkerState.START_WORKING, 20),
                new AITarget<IAIState>(AIWorkerState.START_WORKING, this::decide, TICK_DELAY),
                new AITarget<IAIState>(DecoratorState.ASSEMBLE, this::assemble, TICK_DELAY),
                new AITarget<IAIState>(DecoratorState.DECORATING, () -> visit(DecoratorState.DECORATING), TICK_DELAY),
                new AITarget<IAIState>(DecoratorState.TENDING, () -> visit(DecoratorState.TENDING), TICK_DELAY),
                new AITarget<IAIState>(DecoratorState.TAKING_DOWN, () -> visit(DecoratorState.TAKING_DOWN), TICK_DELAY),
                new AITarget<IAIState>(DecoratorState.HOSTING, () -> visit(DecoratorState.HOSTING), TICK_DELAY),
                new AITarget<IAIState>(DecoratorState.LAUNCHING, () -> visit(DecoratorState.LAUNCHING), TICK_DELAY));
    }

    @Override
    public Class<BuildingDecorator> getExpectedBuildingClass() {
        return BuildingDecorator.class;
    }

    @Override
    public IAIState getStateAfterPickUp() {
        return AIWorkerState.START_WORKING;
    }

    // ---------------------------------------------------------------- deciding

    private IAIState decide() {
        job.countDecision();
        if (building.getBuildingLevel() < 1) {
            job.setStatus("waiting for the hut to be built");
            return AIWorkerState.IDLE;
        }
        final DecorPhase phase = DecorPhase.of(CalendarManager.current(), building.decorationsUp());
        job.setPhase(phase);

        final long now = world.getGameTime();
        if (phase != DecorPhase.OFF_SEASON && now >= nextPhaseVisit) {
            nextPhaseVisit = now + PHASE_VISIT_TICKS;
            return switch (phase) {
                case DECORATING -> DecoratorState.DECORATING;
                case HOSTING -> DecoratorState.HOSTING;
                case TAKING_DOWN -> DecoratorState.TAKING_DOWN;
                case OFF_SEASON -> AIWorkerState.START_WORKING;
            };
        }

        if (!SeasonsConfig.needsMaterials()) {
            // decorations are free: nothing to ask for, nothing to assemble
            job.setStatus("decorations need no materials on this server; nothing to prepare");
            walkToBuilding();
            return AIWorkerState.START_WORKING;
        }
        if (phase == DecorPhase.OFF_SEASON && !building.prepareAllYear()) {
            job.setStatus("off season: the hut prepares only during a festival's window");
            walkToBuilding();
            return AIWorkerState.START_WORKING;
        }
        return prepare();
    }

    /**
     * One look at every kit below the cap: ask for what is missing, then assemble the first one a whole batch of
     * which is in his pack.
     */
    private IAIState prepare() {
        final IItemHandler pack = worker.getInventoryCitizen();
        final int cap = building.stockCap();
        final List<Kits.Kit> wanted = new ArrayList<>();
        for (final Kits.Kit kit : Kits.all()) {
            if (building.stock(kit.output()) + kit.count() <= cap) {
                wanted.add(kit);
            }
        }
        if (wanted.isEmpty()) {
            job.setStatus(Kits.all().isEmpty() ? "no decoration kits are loaded" : "stock is full: every kit is at the cap");
            walkToBuilding();
            return AIWorkerState.START_WORKING;
        }
        for (final Kits.Kit kit : wanted) {
            if (!Kits.hasBatch(pack, kit)) {
                askFor(kit, pack);
            }
        }
        for (final Kits.Kit kit : wanted) {
            if (Kits.hasBatch(pack, kit)) {
                kitToAssemble = kit;
                walkAttempts = 0;
                job.setStatus("assembling " + kit.id());
                return DecoratorState.ASSEMBLE;
            }
        }
        job.setStatus("waiting for materials");
        walkToBuilding();
        return AIWorkerState.START_WORKING;
    }

    /**
     * Async requests for one batch of every ingredient the pack is short of. Both calls also move matching items
     * from the hut into the pack when the hut has them; their boolean is not trusted (the tag call wants one stack
     * that holds the whole count), the pack is counted again afterwards.
     */
    private void askFor(final Kits.Kit kit, final IItemHandler pack) {
        for (final Kits.Ingredient ingredient : kit.ingredients()) {
            final int have = Kits.count(pack, ingredient);
            if (have >= ingredient.count()) {
                continue;
            }
            if (ingredient.tag() != null) {
                checkIfRequestForTagExistOrCreateAsync(ingredient.tag(), ingredient.count() - have);
            } else {
                checkIfRequestForItemExistOrCreateAsync(new ItemStack(ingredient.item()), ingredient.count(), ingredient.count());
            }
        }
    }

    // ---------------------------------------------------------------- assembling

    private IAIState assemble() {
        final Kits.Kit kit = kitToAssemble == null ? null : Kits.byId(kitToAssemble.id());
        if (kit == null) {
            return AIWorkerState.START_WORKING;               // a reload took the kit away
        }
        if (!walkToBuilding() && ++walkAttempts < WALK_ATTEMPTS) {
            return DecoratorState.ASSEMBLE;                   // still on the way
        }
        job.recordVisit(DecoratorState.ASSEMBLE);
        kitToAssemble = null;
        return assembleNow(kit);
    }

    /**
     * One batch, in one tick: count again, make sure the output has room (the hut first, then his pack), take the
     * exact ingredients out of the pack, put the output in. If anything is short, what was taken goes back and nothing
     * is made. Nothing waits for a later tick, so a reset of the AI cannot catch it halfway.
     */
    private IAIState assembleNow(final Kits.Kit kit) {
        final IItemHandler pack = worker.getInventoryCitizen();
        final IItemHandler hut = building.getItemHandlerCap();
        if (!SeasonsConfig.needsMaterials() || !Kits.hasBatch(pack, kit)
                || building.stock(kit.output()) + kit.count() > building.stockCap()) {
            return AIWorkerState.START_WORKING;
        }
        final ItemStack output = new ItemStack(kit.output(), kit.count());
        final IItemHandler target;
        if (hut != null && ItemHandlerHelper.insertItemStacked(hut, output.copy(), true).isEmpty()) {
            target = hut;
        } else if (ItemHandlerHelper.insertItemStacked(pack, output.copy(), true).isEmpty()) {
            target = pack;
        } else {
            job.setStatus("the hut and his pack are full; no room for " + kit.id());
            setDelay(FULL_DELAY);
            return AIWorkerState.START_WORKING;
        }

        final List<ItemStack> taken = new ArrayList<>();
        for (final Kits.Ingredient ingredient : kit.ingredients()) {
            int need = ingredient.count();
            for (int slot = 0; slot < pack.getSlots() && need > 0; slot++) {
                final ItemStack stack = pack.getStackInSlot(slot);
                if (ingredient.matches(stack)) {
                    final ItemStack got = pack.extractItem(slot, Math.min(need, stack.getCount()), false);
                    if (!got.isEmpty()) {
                        taken.add(got);
                        need -= got.getCount();
                    }
                }
            }
            if (need > 0) {
                for (final ItemStack back : taken) {
                    putAway(back, pack, hut);
                }
                return AIWorkerState.START_WORKING;
            }
        }
        putAway(output, target, target == hut ? pack : hut);
        building.addAssembled();
        incrementActionsDoneAndDecSaturation();
        job.setStatus("assembled " + kit.count() + " x " + kit.id());
        setDelay(Math.max(ASSEMBLY_DELAY_MIN, ASSEMBLY_DELAY - ASSEMBLY_DELAY_PER_DEXTERITY * getSecondarySkillLevel()));
        return AIWorkerState.START_WORKING;
    }

    /** Puts a stack into {@code first}, the rest into {@code second}, and drops what still does not fit: never lost. */
    private void putAway(final ItemStack stack, final IItemHandler first, final IItemHandler second) {
        ItemStack rest = first == null ? stack : ItemHandlerHelper.insertItemStacked(first, stack, false);
        if (!rest.isEmpty() && second != null) {
            rest = ItemHandlerHelper.insertItemStacked(second, rest, false);
        }
        if (!rest.isEmpty()) {
            InventoryUtils.spawnItemStack(world, worker.getX(), worker.getY(), worker.getZ(), rest);
        }
    }

    // ---------------------------------------------------------------- the phase states (stubs until S4 and S7)

    /** A phase state's whole work in this chunk: note the visit and go back to deciding. */
    private IAIState visit(final DecoratorState state) {
        job.recordVisit(state);
        return AIWorkerState.START_WORKING;
    }
}

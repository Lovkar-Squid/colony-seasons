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
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.DecorPhase;
import me.lovkar.seasons.decor.DecorPlacer;
import me.lovkar.seasons.decor.DecorWork;
import me.lovkar.seasons.decor.Kits;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Decorator's day (design section 4). Every 20 ticks {@link #decide()} looks at the calendar and the hut:
 *
 * <ul>
 *   <li>the phase comes from {@link DecorPhase#of} (decorations count as up while the hut says so or the decoration
 *       ledger lists any for the colony): festival days are Hosting, an open decoration window is Decorating,
 *       decorations still up after the window are Taking down, anything else is the Off season;</li>
 *   <li>outside the Off season, every {@link #PHASE_VISIT_TICKS} ticks he goes through that phase's state, and after a
 *       visit that put something up or took something down he pauses {@link #visitPause} ticks (shorter with more
 *       Creativity). Decorating picks the next building ({@link DecorWork#nextToDecorate}), walks there and places
 *       what the plan and his stock allow; when nothing is left for the day it turns into Tending (once a game day
 *       per decorated group). Taking down clears one group per visit by the removal rule and puts the items back in
 *       the hut. Hosting is S7's: it notes the visit and goes on with the upkeep;</li>
 *   <li>Preparing goes on in every phase when the hut's "prepare all year" is on, and only while a window is open
 *       when it is off: for every kit below the stock cap he asks the colony for one batch of what is missing (async
 *       requests, MineColonies drops duplicates), and when his pack holds a whole batch he walks to the hut and
 *       assembles it.</li>
 * </ul>
 *
 * <p>Nothing that must survive is kept here: MineColonies resets this AI for sleep, rain and raids. The fields are
 * scratch (timers, a walk counter, the kit he was about to assemble, the building he was walking to); progress is
 * the decoration ledger and the building's NBT, stock is items in the hut and in his own pack. An assembly and every
 * placing or removing visit happen in one server tick, so a reset can never catch one halfway.</p>
 */
public class EntityAIWorkDecorator extends AbstractEntityAIInteract<JobDecorator, BuildingDecorator> {

    /** The Decorator's own states: assembling, the upkeep of the decorations, and the festival states S7 fills in. */
    public enum DecoratorState implements IAIState {
        /** At the hut, turning one batch of materials into decorations. */
        ASSEMBLE(true),
        /** Putting decorations up. */
        DECORATING(true),
        /** Once a game day, putting back what went missing. */
        TENDING(true),
        /** Taking the decorations down after the window. */
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

    /** Decisions spent walking to a building before he works from where he stands (the rules do not need him there). */
    public static final int WALK_ATTEMPTS_OUT = 40;
    /**
     * Pause after a visit that put something up or took something down: {@code VISIT_PAUSE - VISIT_PAUSE_PER_CREATIVITY x
     * Creativity}, at least {@code VISIT_PAUSE_MIN} ticks. Creativity is how quickly he decorates.
     */
    public static final int VISIT_PAUSE = 200;
    public static final int VISIT_PAUSE_PER_CREATIVITY = 6;
    public static final int VISIT_PAUSE_MIN = 40;

    // scratch only: a reset of the AI loses these and nothing else (progress is the ledger and the building's NBT)
    private long nextPhaseVisit = Long.MIN_VALUE;
    private int walkAttempts;
    private Kits.Kit kitToAssemble;
    private DecorWork.Target target;

    public EntityAIWorkDecorator(final @NotNull JobDecorator job) {
        super(job);
        super.registerTargets(
                new AITarget<IAIState>(AIWorkerState.IDLE, () -> AIWorkerState.START_WORKING, 20),
                new AITarget<IAIState>(AIWorkerState.START_WORKING, this::decide, TICK_DELAY),
                new AITarget<IAIState>(DecoratorState.ASSEMBLE, this::assemble, TICK_DELAY),
                new AITarget<IAIState>(DecoratorState.DECORATING, this::decorating, TICK_DELAY),
                new AITarget<IAIState>(DecoratorState.TENDING, this::tending, TICK_DELAY),
                new AITarget<IAIState>(DecoratorState.TAKING_DOWN, this::takingDown, TICK_DELAY),
                new AITarget<IAIState>(DecoratorState.HOSTING, this::hosting, TICK_DELAY),
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
        final CalendarManager.Snapshot snapshot = CalendarManager.current();
        final DecorLedger ledger = ledger();
        final boolean up = building.decorationsUp() || ledger != null && ledger.hasEntries(building.getColony().getID());
        final DecorPhase phase = DecorPhase.of(snapshot, up);
        final boolean window = DecorPhase.windowOpen(snapshot);
        job.setPhase(phase);

        final long now = world.getGameTime();
        if (phase != DecorPhase.OFF_SEASON && now >= nextPhaseVisit) {
            nextPhaseVisit = now + PHASE_VISIT_TICKS;
            target = null;
            walkAttempts = 0;
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
        if (!building.prepareAllYear() && !window) {
            job.setStatus("the hut prepares only during a festival's window");
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
            if (Kits.wanted(building.stock(kit.output()), kit.count(), cap)) {
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
     * from the hut into the pack when the hut has them; their boolean is not trusted, the pack is counted again
     * afterwards.
     *
     * <p>The tag call is always given the whole batch count, never only what is missing: MineColonies' tag call
     * returns at once, doing nothing, when ONE stack in the pack holds the count it is given. Asked for the missing
     * part, a pack with a partial batch (say 2 oak leaves of 4) would answer "there already" for ever and the kit
     * would never be made. A whole batch more is at worst a few leaves too many in his pack.</p>
     */
    private void askFor(final Kits.Kit kit, final IItemHandler pack) {
        for (final Kits.Ingredient ingredient : kit.ingredients()) {
            final int have = Kits.count(pack, ingredient);
            if (have >= ingredient.count()) {
                continue;
            }
            if (ingredient.tag() != null) {
                checkIfRequestForTagExistOrCreateAsync(ingredient.tag(), ingredient.count());
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
                || !Kits.wanted(building.stock(kit.output()), kit.count(), building.stockCap())) {
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

    // ---------------------------------------------------------------- the phase states

    private DecorLedger ledger() {
        return world instanceof ServerLevel server ? DecorLedger.get(server) : null;
    }

    /** The pause after a visit that did something: shorter with more Creativity. */
    public static int visitPause(final int creativity) {
        return Math.max(VISIT_PAUSE_MIN, VISIT_PAUSE - VISIT_PAUSE_PER_CREATIVITY * creativity);
    }

    private void afterWork() {
        nextPhaseVisit = world.getGameTime() + visitPause(getPrimarySkillLevel());
        incrementActionsDoneAndDecSaturation();
    }

    /** True once he stands at the target, or has walked long enough (he then works from where he is). */
    private boolean arrived() {
        if (target == null) {
            return true;
        }
        if (walkToSafePos(target.stand())) {
            return true;
        }
        return ++walkAttempts >= WALK_ATTEMPTS_OUT;
    }

    private DecorPlacer.Stock stock() {
        return DecorWork.stockOf(building, worker);
    }

    /**
     * Decorating: the next building (town hall, then the square, then the nearest) while the day's budget lasts, then
     * tending. While the window is open this is also where he goes on festival days, after the Hosting stub.
     */
    private IAIState decorating() {
        job.recordVisit(DecoratorState.DECORATING);
        final DecorLedger ledger = ledger();
        if (ledger == null) {
            return AIWorkerState.START_WORKING;
        }
        final ServerLevel level = (ServerLevel) world;
        final long day = DecorWork.day(level);
        final int hutLevel = building.getBuildingLevel();
        if (target == null) {
            target = DecorWork.nextToDecorate(level, building.getColony(), hutLevel, building.getPosition(), ledger, stock(), day);
            walkAttempts = 0;
            if (target == null) {
                target = DecorWork.nextToTend(level, building.getColony(), building.getPosition(), ledger, day);
                if (target != null) {
                    job.setStatus("tending the decorations at " + target.owner());
                    return DecoratorState.TENDING;
                }
                job.setStatus(ledger.budgetUsed(building.getColony().getID(), day) >= DecorWork.quota(hutLevel)
                        ? "decorated as many buildings as he can today" : "waiting for decorations (or every building is decorated)");
                return AIWorkerState.START_WORKING;
            }
            job.setStatus("on his way to decorate " + target.owner());
        }
        if (!arrived()) {
            return DecoratorState.DECORATING;
        }
        final DecorWork.Outcome outcome = DecorWork.decorate(level, building.getColony().getID(), target, hutLevel, stock(), day, ledger);
        askForSquare(outcome);
        if (outcome.placed() > 0) {
            building.setDecorationsUp(true);
            afterWork();
        }
        job.setStatus("decorated " + target.owner() + ": " + outcome.status());
        target = null;
        return AIWorkerState.START_WORKING;
    }

    /** Vanilla blocks of the square (spruce logs and leaves) come from the warehouse like his kits' materials. */
    private void askForSquare(final DecorWork.Outcome outcome) {
        if (!SeasonsConfig.needsMaterials()) {
            return;
        }
        for (final Map.Entry<Item, Integer> e : outcome.missing().entrySet()) {
            checkIfRequestForItemExistOrCreateAsync(new ItemStack(e.getKey()), Math.min(64, e.getValue()), 1);
        }
    }

    /** Tending: once a game day per decorated group, what went missing is put back. */
    private IAIState tending() {
        job.recordVisit(DecoratorState.TENDING);
        final DecorLedger ledger = ledger();
        if (ledger == null || target == null) {
            target = null;
            return AIWorkerState.START_WORKING;
        }
        if (!arrived()) {
            return DecoratorState.TENDING;
        }
        final ServerLevel level = (ServerLevel) world;
        final DecorWork.Outcome outcome = DecorWork.tend(level, building.getColony().getID(), target, building.getBuildingLevel(),
                stock(), DecorWork.day(level), ledger);
        askForSquare(outcome);
        if (outcome.placed() > 0) {
            building.setDecorationsUp(true);
            afterWork();
        }
        job.setStatus("tended " + target.owner() + ": " + outcome.status());
        target = null;
        return AIWorkerState.START_WORKING;
    }

    /**
     * Taking down after the window: one group per visit, nearest first, by the removal rule, the items back to the
     * hut for next year; none left: the decorations are down.
     */
    private IAIState takingDown() {
        job.recordVisit(DecoratorState.TAKING_DOWN);
        final DecorLedger ledger = ledger();
        if (ledger == null) {
            return AIWorkerState.START_WORKING;
        }
        final ServerLevel level = (ServerLevel) world;
        final int colony = building.getColony().getID();
        final long day = DecorWork.day(level);
        if (target == null) {
            if (!ledger.hasEntries(colony)) {
                ledger.dropEmptyGroups(colony);
                building.setDecorationsUp(false);
                job.setStatus("the decorations are down");
                return AIWorkerState.START_WORKING;
            }
            if (ledger.budgetUsed(colony, day) >= DecorWork.quota(building.getBuildingLevel())) {
                job.setStatus("took down as many buildings as he can today");
                return AIWorkerState.START_WORKING;
            }
            target = DecorWork.nextToTakeDown(level, building.getColony(), building.getPosition(), ledger);
            walkAttempts = 0;
            if (target == null) {
                job.setStatus("waiting for the decorations' chunks to load");
                return AIWorkerState.START_WORKING;
            }
            job.setStatus("on his way to take down " + target.owner());
        }
        if (!arrived()) {
            return DecoratorState.TAKING_DOWN;
        }
        final DecorPlacer.Removed removed = DecorWork.takeDown(level, colony, target.owner(), stock(), day, ledger);
        job.setStatus("took down " + target.owner() + ": " + removed.removed() + " removed, " + removed.forgotten()
                + " changed by someone and left alone");
        target = null;
        if (removed.touched() > 0) {
            afterWork();
        }
        if (!ledger.hasEntries(colony)) {
            ledger.dropEmptyGroups(colony);
            building.setDecorationsUp(false);
        }
        return AIWorkerState.START_WORKING;
    }

    /**
     * Hosting (S7 fills it in): the visit is noted, then the upkeep goes on, so festival days inside a window still
     * see decorating and tending, and decorations after one are still taken down.
     */
    private IAIState hosting() {
        job.recordVisit(DecoratorState.HOSTING);
        target = null;
        walkAttempts = 0;
        if (DecorPhase.windowOpen(CalendarManager.current())) {
            return DecoratorState.DECORATING;
        }
        final DecorLedger ledger = ledger();
        if (ledger != null && ledger.hasEntries(building.getColony().getID())) {
            return DecoratorState.TAKING_DOWN;
        }
        return AIWorkerState.START_WORKING;
    }

    /** A phase state that S7 fills in (Launching): note the visit and go back to deciding. */
    private IAIState visit(final DecoratorState state) {
        job.recordVisit(state);
        return AIWorkerState.START_WORKING;
    }
}

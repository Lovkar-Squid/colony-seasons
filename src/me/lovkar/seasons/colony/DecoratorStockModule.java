package me.lovkar.seasons.colony;

import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModule;
import com.minecolonies.api.colony.buildings.modules.IAltersRequiredItems;
import me.lovkar.seasons.decor.Kits;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.util.TriConsumer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Tells MineColonies what the Decorator's hut keeps, so couriers do not carry his stock away: every kit's output
 * up to the hut's stock cap (kept in the building), and every ingredient up to one batch (kept in his own pack too,
 * the way MineColonies keeps a worker's food). Re-read on every call, so it follows the hut's level and the kits of
 * the last datapack reload. Server only; it has no window tab.
 */
public class DecoratorStockModule extends AbstractBuildingModule implements IAltersRequiredItems {

    @Override
    public void alterItemsToBeKept(final TriConsumer<Predicate<ItemStack>, Integer, Boolean> consumer) {
        if (!(building instanceof BuildingDecorator decorator)) {
            return;
        }
        final int cap = decorator.stockCap();
        final Map<String, Kits.Ingredient> ingredients = new LinkedHashMap<>();
        final Map<String, Integer> amounts = new LinkedHashMap<>();
        for (final Kits.Kit kit : Kits.all()) {
            final Item output = kit.output();
            consumer.accept(stack -> stack.is(output), cap, false);
            for (final Kits.Ingredient ingredient : kit.ingredients()) {
                ingredients.putIfAbsent(ingredient.key(), ingredient);
                amounts.merge(ingredient.key(), ingredient.count(), Math::max);
            }
        }
        for (final Map.Entry<String, Kits.Ingredient> entry : ingredients.entrySet()) {
            final Kits.Ingredient ingredient = entry.getValue();
            consumer.accept(ingredient::matches, amounts.get(entry.getKey()), true);
        }
    }
}

import com.google.gson.GsonBuilder;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Asks the game's own classes what changes blocks over time, and prints it as JSON.
 *
 * For every block: the class that implements it, how many of its states the game ticks at
 * random, and which class holds its random tick and its scheduled tick. Then the game's own
 * tables of copper weathering and waxing.
 *
 * Run by probe_game_facts.py with the server's jar and libraries on the class path. Nothing of
 * the game is copied: the output names blocks and classes only.
 */
public final class GameFacts {

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Map<String, Object> blocks = new TreeMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("class", block.getClass().getSimpleName());
            int ticking = 0;
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                ticking += state.isRandomlyTicking() ? 1 : 0;
            }
            entry.put("states", block.getStateDefinition().getPossibleStates().size());
            entry.put("random_tick_states", ticking);
            entry.put("random_tick", declares(block, "randomTick"));
            entry.put("tick", declares(block, "tick"));
            entry.put("block_entity", block instanceof EntityBlock);
            blocks.put(id(block), entry);
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("minecraft", SharedConstants.getCurrentVersion().name());
        facts.put("blocks", blocks);
        facts.put("copper_next", names(WeatheringCopper.NEXT_BY_BLOCK.get()));
        facts.put("waxed", names(HoneycombItem.WAXABLES.get()));
        Bootstrap.realStdoutPrintln(new GsonBuilder().setPrettyPrinting().create().toJson(facts));
    }

    private static String id(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    private static Map<String, String> names(Map<Block, Block> map) {
        Map<String, String> names = new TreeMap<>();
        map.forEach((from, to) -> names.put(id(from), id(to)));
        return names;
    }

    /** The most specific class of the block that has its own method of this name, without the game's base class. */
    private static String declares(Block block, String name) {
        for (Class<?> type = block.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && !method.isSynthetic() && method.getParameterCount() == 4) {
                    return type.getSimpleName().equals("BlockBehaviour") ? null : type.getSimpleName();
                }
            }
        }
        return null;
    }
}

package mycelium.merge;

import mycelium.rules.Rules;
import java.util.Objects;

/**
 * What a merge needs besides the data itself.
 *
 * @param precedence which side wins "newer side" rules
 * @param rules      noise rules, unit definitions and the block table behind them
 * @param dimension  the dimension id that report entries carry, such as {@code minecraft:overworld}
 */
public record MergeContext(Precedence precedence, Rules rules, String dimension) {

    public MergeContext {
        Objects.requireNonNull(precedence, "precedence");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(dimension, "dimension");
    }
}

package klumpler.lazycraft;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared mod identity and logging. Loader startup code belongs in platform modules.
 */
public final class LazyCraft {
    public static final String MOD_ID = "lazycraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private LazyCraft() {
    }
}

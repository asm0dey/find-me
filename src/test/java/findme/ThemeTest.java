package findme;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeTest {
    @Test void percentRoundsAndClamps() {
        assertEquals("71%", Theme.percent(0.7149f));
        assertEquals("72%", Theme.percent(0.715f));
        assertEquals("0%", Theme.percent(-0.05f));
        assertEquals("100%", Theme.percent(1.2f));
        assertEquals(40, Theme.percentValue(0.40f));
    }

    @Test void bundledFontsLoad() {
        assertTrue(Theme.bold(64).getFamily().startsWith("Pragmasevka"));
        assertEquals(64f, Theme.regular(64).getSize2D());
    }
}

package findme;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;

/// Palette, bundled font and pacing of the demo window.
final class Theme {
    static final Color BG = new Color(0x0F172A);
    static final Color CARD = new Color(0x1B2336);
    static final Color MUTED = new Color(0x94A3B8);
    static final Color MATCH = new Color(0x22C55E);
    static final Color NO_MATCH = new Color(0xEF4444);
    static final Color SCANNING = new Color(0x38BDF8);
    static final Color LANDMARK = new Color(0xFDE047);
    static final Color TEXT = new Color(0xF8FAFC);
    static final Color TRACK = new Color(0x272F42);
    static final Color SLOT_BORDER = new Color(0x334155);
    /// How long each photo stays on screen, in ms: a photo without the person, one with, and the lock-on animation.
    static final long MISS_MS = 150;
    static final long MATCH_MS = 1500;
    static final long LOCK_ON_MS = 300;
    /// The fastest the display may go to make its deadline, and that deadline: launch + RUN_MS, so the end gallery gets
    /// the rest of a 2-minute recording.
    /// No match shows for less than MIN_MATCH_MS, so the stamp stays readable.
    static final long MIN_MISS_MS = 30;
    static final long MIN_MATCH_MS = 1000;
    static final long RUN_MS = 110_000;
    private static final Font REGULAR = load("pragmasevka-nf-regular.ttf");
    private static final Font BOLD = load("pragmasevka-nf-bold.ttf");

    private Theme() {
    }

    static Font regular() {
        return REGULAR.deriveFont((float) 64);
    }

    static Font bold(float px) {
        return BOLD.deriveFont(px);
    }

    static String percent(float similarity) {
        return percentValue(similarity) + "%";
    }

    static int percentValue(float similarity) {
        return Math.clamp(Math.round(similarity * 100), 0, 100);
    }

    private static Font load(String name) {
        try (var in = Theme.class.getResourceAsStream("/fonts/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing bundled font " + name);
            }
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (IOException | FontFormatException e) {
            throw new IllegalStateException("cannot load bundled font " + name, e);
        }
    }
}

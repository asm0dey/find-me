package findme;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sprouts.Var;
import swingtree.SwingTree;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Lays the real window out at 1080×960 and paints it into an image through every phase, in publish order on one
/// state property, as the app does: no exception, not blank, and the regions where the design puts them.
class DemoPaintTest {
    @BeforeAll
    static void scaleOne() {
        SwingTree.initializeUsing(cfg -> cfg.uiScaleFactor(1f));
    }

    private static Shot shot(int w, int h, float... similarities) {
        List<Box> boxes = new ArrayList<>();
        for (int i = 0; i < similarities.length; i++) {
            float x = (float) (40 + i * 120);
            float[] lm = {x + 20, 80, x + 60, 80, x + 40, 110, x + 25, 140, x + 55, 140};
            boxes.add(new Box(x, 40, x + 80, 160, lm, similarities[i]));
        }
        float best = 0;
        for (float s : similarities) {
            best = Math.max(best, s);
        }
        return new Shot(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), boxes, best);
    }

    private static List<BufferedImage> crops(int n) {
        List<BufferedImage> crops = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            crops.add(new BufferedImage(448, 448, BufferedImage.TYPE_INT_RGB));
        }
        return crops;
    }

    private static List<Shot> matches(int n, int w, int h) {
        List<Shot> shots = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            shots.add(shot(w, h, .8f, .1f));
        }
        return shots;
    }

    /// Lays out and paints `view` (twice: bound sizes settle on the first paint), then checks it isn't blank.
    private static BufferedImage paint(JComponent view, String what) {
        BufferedImage img = new BufferedImage(DemoView.W, DemoView.H, BufferedImage.TYPE_INT_RGB);
        for (int pass = 0; pass < 2; pass++) {
            view.setSize(DemoView.W, DemoView.H);
            layOut(view);
            Graphics2D g = img.createGraphics();
            view.paint(g);
            g.dispose();
        }
        boolean drawn = false;
        for (int y = 0; y < img.getHeight() && !drawn; y += 4) {
            for (int x = 0; x < img.getWidth() && !drawn; x += 4) {
                drawn = img.getRGB(x, y) != Theme.BG.getRGB();
            }
        }
        assertTrue(drawn, what + " painted nothing");
        return img;
    }

    /// What `validate()` does once a window is shown: lays out `c`, then its children at their new sizes.
    private static void layOut(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) {
            if (child instanceof Container k) {
                layOut(k);
            }
        }
    }

    /// Bounds, in window coordinates, of the deepest component at (x, y).
    private static Rectangle at(JComponent view, int x, int y) {
        Component c = SwingUtilities.getDeepestComponentAt(view, x, y);
        return SwingUtilities.convertRectangle(c.getParent(), c.getBounds(), view);
    }

    @Test
    void everyPhasePaintsThroughTheComponentTree() throws Exception {
        List<DemoState> states = List.of(
                new Learning(List.of(), 0, 0, null, 0, 0, 0),
                new Learning(crops(30), 30, 40, shot(720, 1080, 1), 0, 0, 0),
                // listing meanwhile
                new Learning(crops(3), 3, 40, shot(720, 1080, 1), 2514, 5, 5),
                new Listing(crops(30), 0, 5, 0, 1234),
                new Listing(crops(3), 0, 0, 12, 61_000),
                new Scanning(crops(30), 3, 215, 0, true, shot(1080, 720, .1f, .05f, .2f), false),
                new Scanning(crops(30), 4, 215, 1, true, shot(1080, 720, .7f, .1f), true),
                new Scanning(crops(30), 1, 215, 0, false, null, false),
                new Done(crops(30), 13, matches(1, 1080, 720), 6_800),
                new Done(crops(30), 13, matches(2, 720, 1080), 6_800),
                new Done(crops(30), 13, matches(4, 1080, 675), 6_800),
                new Done(crops(30), 13, matches(9, 1080, 1080), 6_800),
                new Done(crops(30), 13, List.of(), 6_800),
                new Failed("no usable reference photos, and a message long enough to need wrapping onto lines")
        );
        SwingUtilities.invokeAndWait(() -> {
            Var<DemoState> state = Var.of(DemoState.class, states.getFirst());
            JComponent view = DemoView.create(state);
            for (DemoState s : states) {
                state.set(s);
                paint(view, s.toString());
            }
        });
    }

    @Test
    void regionsSitWhereTheDesignPutsThem() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Var<DemoState> state = Var.of(DemoState.class, new Learning(crops(3), 3, 40, shot(720, 1080, 1), 0, 0, 0));
            JComponent view = DemoView.create(state);
            paint(view, "learning");
            assertEquals(new Rectangle(368, 172, 672, DemoView.LEARNING_BODY), at(view, 700, 500));

            state.set(new Listing(crops(3), 1, 5, 12, 1000));
            paint(view, "listing");
            assertEquals(new Rectangle(368, 172, 672, DemoView.LISTING_BODY), at(view, 400, 200));

            state.set(new Scanning(crops(30), 3, 215, 0, true, shot(1080, 720, .1f), false));
            paint(view, "scanning");
            assertEquals(new Rectangle(368, 172, 672, DemoView.SCANNING_BODY), at(view, 700, 500));

            state.set(new Done(crops(30), 13, matches(9, 1080, 1080), 6_800));
            paint(view, "gallery");
            assertEquals(new Rectangle(40, 172, 320, 364), at(view, 50, 180));
            // the "+N more" tile
            assertEquals(new Rectangle(720, 556, 320, 364), at(view, 1030, 910));
        });
    }

    @Test
    void matchAnimationPaintsMidLockOnAndMidStamp() {
        Shot match = shot(1080, 720, .7f, .1f);
        for (float progress : new float[] {0, .25f, .75f, 1}) {
            BufferedImage img = new BufferedImage(672, 630, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            assertDoesNotThrow(() -> PhotoPainter.paintScan(g, new Rectangle(672, 630), match, progress));
            g.dispose();
        }
    }
}

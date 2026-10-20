package findme;

import static swingtree.UI.ALIGN_X_CENTER;
import static swingtree.UI.ALIGN_Y_CENTER;
import static swingtree.UI.FILL;
import static swingtree.UI.GAP_TOP;
import static swingtree.UI.GROW;
import static swingtree.UI.GROW_X;
import static swingtree.UI.INS;
import static swingtree.UI.SPAN;
import static swingtree.UI.TOP;
import static swingtree.UI.WRAP;
import static swingtree.UI.box;
import static swingtree.UI.label;
import static swingtree.UI.panel;

import java.awt.Color;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.ToIntFunction;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.miginfocom.layout.AC;
import net.miginfocom.layout.CC;
import net.miginfocom.layout.HideMode;
import net.miginfocom.layout.LC;
import org.jspecify.annotations.Nullable;
import sprouts.Tuple;
import sprouts.Val;
import sprouts.Var;
import swingtree.UI;
import swingtree.UIForAnySwing;
import swingtree.UIForBox;
import swingtree.UIForLabel;
import swingtree.animation.LifeTime;
import swingtree.components.JBox;
import swingtree.style.ComponentStyleDelegate;

/// The demo window: one screen per phase, each a swing-tree of labels and styled boxes bound to the published
/// [DemoState]. Only the photos are drawn by hand ([PhotoPainter]). Design pixels at 1080×960, phone-readable sizes:
/// no text under 32 px.
@SuppressWarnings("java:S3358") // a nested ternary picks a line's copy or color by phase detail at a glance
final class DemoView {

    static final int W = 1080, H = 960, PAD = 40, GAP = 28, COL = 300, HEADER = 104;
    /// Body heights (under the header, over each phase's footer); the screens' layouts below come out the same.
    static final int LEARNING_BODY = 677, LISTING_BODY = 748, SCANNING_BODY = 630;
    static final int LABEL = 36, LABEL_LINE = 43, LABEL_GAP = 14, TILE = 94, TILE_GAP = 9, MOSAIC_COLS = 3;
    static final int BAR_W = W - 2 * PAD, BAR = 34, TICK = 8, RADIUS = PhotoPainter.RADIUS;
    /// The scan screen's footer: the similarity/threshold labels, a gap, then the bar with its tick above and below.
    static final int SCORE_LABEL = 44, SCORE_GAP = 4, BAR_AREA = BAR + 2 * TICK, FOOTER = SCORE_LABEL + SCORE_GAP + BAR_AREA;
    /// The listing's photo count: 240 px digits, in a row 250 px tall.
    static final int COUNT = 240, COUNT_ROW = 250;
    /// The lock-on, then the stamp landing.
    static final LifeTime MATCH_ANIMATION = LifeTime.of(2 * Theme.LOCK_ON_MS, TimeUnit.MILLISECONDS);
    private static final Font FAILED = Theme.bold(44);
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    private enum Phase {
        LEARNING, LISTING, SCANNING, DONE, FAILED;

        static Phase of(DemoState s) {
            return switch (s) {
                case Learning _ -> LEARNING;
                case Listing _ -> LISTING;
                case Scanning _ -> SCANNING;
                case Done _ -> DONE;
                case Failed _ -> FAILED;
            };
        }
    }

    /// A mosaic tile: one of your face crops, the count of those that don't fit, or the slot the next one goes in.
    sealed interface Tile {}

    record Face(BufferedImage crop, boolean justLearned) implements Tile {}

    record More(int count) implements Tile {}

    record Next() implements Tile {}

    private DemoView() {}

    static JComponent create(Var<DemoState> state) {
        return panel(FILL.and(INS(0)))
                .withPrefSize(W, H)
                .withStyle(it -> it.backgroundColor(Theme.BG))
                .add(GROW, state.viewAs(Phase.class, Phase::of), phase -> switch (phase) {
                    case LEARNING -> learning(as(state, Learning.class));
                    case LISTING -> listing(as(state, Listing.class));
                    case SCANNING -> scanning(as(state, Scanning.class));
                    case DONE -> done(as(state, Done.class));
                    case FAILED -> failed(as(state, Failed.class));
                })
                .get(JPanel.class);
    }

    private static UIForBox<JBox> learning(Val<Learning> l) {
        Val<Boolean> listing = l.viewAs(Boolean.class, it -> it.album() > 0); // the albums listed meanwhile
        return box().withLayout(screen(), columns(),
                        new AC().size(fixed(HEADER)).gap().grow().gap().size(fixed(LABEL_LINE)))
                .add(SPAN.and(GROW_X).and(WRAP), header(Val.of("LEARNING"), Val.of(Theme.TEXT),
                        progress(l.viewAsString(it -> it.done() + " / " + it.total()))
                                .isVisibleIf(l.viewAs(Boolean.class, it -> it.total() > 0))))
                .add(TOP, mosaic(Val.of("YOU"),
                        l.viewAsTuple(Tile.class, it -> tiles(it.crops(), mosaicSlots(LEARNING_BODY), true))))
                .add(GROW.and(WRAP), learnedPhoto(l.viewAsNullable(Shot.class, Learning::current)))
                .add(SPAN, text(l.viewAsString(it -> it.album() > 0
                                ? "Python inside the JVM · album " + it.album() + "/" + it.albums() + " · " + it.listed()
                                        + " photos"
                                : "your face → 512 numbers → averaged"), LABEL)
                        .withForeground(listing.viewAs(Color.class, on -> on ? Theme.LANDMARK : Theme.TEXT)));
    }

    private static UIForBox<JBox> listing(Val<Listing> l) {
        return box().withLayout(screen(), columns(), new AC().size(fixed(HEADER)).gap().grow())
                .add(SPAN.and(GROW_X).and(WRAP), header(Val.of("LISTING"), Val.of(Theme.TEXT),
                        progress(l.viewAsString(it -> "album " + Math.max(1, it.album()) + "/" + it.albums()))
                                .isVisibleIf(l.viewAs(Boolean.class, it -> it.albums() > 0)),
                        progress(l.viewAsString(it -> clock(it.elapsedMs())))))
                .add(TOP, mosaic(l.viewAsString(it -> "YOU · " + it.crops().size()),
                        l.viewAsTuple(Tile.class, it -> tiles(it.crops(), mosaicSlots(LISTING_BODY), false))))
                .add(GROW, box().withLayout(stack().align("center", "center")).withStyle(DemoView::card)
                        .add(new CC().alignX("center").height(fixed(COUNT_ROW)), // the digits, without the line's leading
                                text(l.viewAsString(it -> String.valueOf(it.found())), COUNT).withForeground(Theme.TEXT))
                        .add(ALIGN_X_CENTER.and(GAP_TOP(16)), text(Val.of("photos found"), LABEL)
                                .withForeground(Theme.MUTED))
                        .add(ALIGN_X_CENTER.and(GAP_TOP(16)), text(Val.of("Python inside the JVM"), 44)
                                .withForeground(Theme.LANDMARK)));
    }

    private static UIForBox<JBox> scanning(Val<Scanning> s) {
        Val<@Nullable Shot> shot = s.viewAsNullable(Shot.class, Scanning::current);
        // the bottom margin gives the threshold tick the 8 px it stands out below the bar
        return box().withLayout(screen().insets(PAD + "", PAD + "", PAD - TICK + "", PAD + ""), columns(),
                        new AC().size(fixed(HEADER)).gap().grow().gap().size(fixed(FOOTER)))
                .add(SPAN.and(GROW_X).and(WRAP), header(s.viewAsString(it -> "FOUND " + it.found()),
                        s.viewAs(Color.class, it -> it.found() > 0 ? Theme.MATCH : Theme.TEXT),
                        progress(s.viewAsString(it -> it.shown() + " / " + it.total() + (it.totalFinal() ? "" : "+"))),
                        text(Val.of("slowed down"), 34).withForeground(Theme.LANDMARK)
                                .isVisibleIf(s.viewAs(Boolean.class, Scanning::slowedDown))))
                .add(TOP, mosaic(s.viewAsString(it -> "YOU · " + it.crops().size()),
                        s.viewAsTuple(Tile.class, it -> tiles(it.crops(), mosaicSlots(SCANNING_BODY), false))))
                .add(GROW.and(WRAP), scannedPhoto(shot))
                .add(SPAN.and(GROW), box().withLayout(stack(), new AC().grow(),
                                new AC().size(fixed(SCORE_LABEL)).gap(SCORE_GAP + "").size(fixed(BAR_AREA)))
                        .add(GROW_X, box().withLayout(tight(), new AC().gap("push"))
                                .add(text(shot.viewAsString(it -> "similarity " + Theme.percent(it == null ? 0 : it.best())), 38)
                                        .withForeground(Theme.TEXT))
                                .add(text(Val.of("threshold " + Theme.percent(FindMe.THRESHOLD)), 38)
                                        .withForeground(Theme.TEXT)))
                        .add(bar(shot)));
    }

    @SuppressWarnings({"NullableProblems", "ConstantValue"}) // swing-tree's withStyle(Val<T>, ..) assumes a non-null T; this Val is null until there is a photo
    private static UIForBox<JBox> learnedPhoto(Val<@Nullable Shot> shot) {
        return box().withStyle(shot, (current, it) -> current == null ? card(it) : card(it).painter(UI.Layer.CONTENT,
                g -> PhotoPainter.paintLearning(g, area(it), current)));
    }

    @SuppressWarnings({"NullableProblems", "ConstantValue"}) // swing-tree's withStyle(Val<T>, ..) assumes a non-null T; this Val is null until there is a photo
    private static UIForBox<JBox> scannedPhoto(Val<@Nullable Shot> shot) {
        return box().withStyle(shot, MATCH_ANIMATION, (current, anim, it) -> current == null ? card(it)
                : card(it).painter(UI.Layer.CONTENT,
                        g -> PhotoPainter.paintScan(g, area(it), current, (float) anim.progress())));
    }

    private static UIForBox<JBox> done(Val<Done> d) {
        return box().withLayout(screen(), new AC().grow(), new AC().size(fixed(HEADER)).gap().grow())
                .add(GROW_X.and(WRAP), header(d.viewAsString(it -> "FOUND " + it.matches().size()),
                        d.viewAs(Color.class, it -> it.matches().isEmpty() ? Theme.TEXT : Theme.MATCH),
                        progress(d.viewAsString(it -> "of " + it.total())),
                        progress(d.viewAsString(it ->
                                String.format(Locale.ROOT, "real %.1f s", it.realScanMs() / 1000.0)))))
                .add(GROW, d, DemoView::gallery);
    }

    private static UIForBox<JBox> failed(Val<Failed> f) {
        return box().withLayout(stack().insets(PAD + "").align("center", "center"))
                .addAll(ALIGN_X_CENTER, f.viewAsTuple(String.class, it -> Tuple.of(String.class, wrap(it.message(), W - 2 * PAD,
                                line -> (int) Math.ceil(FAILED.getStringBounds(line, FRC).getWidth())))),
                        line -> label(line).withFont(FAILED).withForeground(Theme.NO_MATCH));
    }

    /// The title on the left; on the right, up to two lines of progress stacked on its middle.
    private static UIForBox<JBox> header(Val<String> title, Val<Color> color, UIForLabel<?>... progress) {
        return box().withLayout(tight(), new AC().gap("push"), new AC().size(fixed(HEADER)))
                .add(ALIGN_Y_CENTER, text(title, 72).withForeground(color))
                .add(ALIGN_Y_CENTER, box().withLayout(stack().hideMode(HideMode.DISREGARD), new AC().align("right"))
                        .add(progress));
    }

    /// Your face crops under `label`, three to a row.
    private static UIForBox<JBox> mosaic(Val<String> label, Val<Tuple<Tile>> tiles) {
        AC tile = new AC().size(fixed(TILE)).fill(); // for every column and row: the last spec repeats
        return box().withLayout(stack(), new AC().size(fixed(COL)), new AC().size(fixed(LABEL_LINE)).gap(LABEL_GAP + ""))
                .add(text(label, LABEL).withForeground(Theme.MUTED))
                .add(box().withLayout(tight().gridGap(TILE_GAP + "", TILE_GAP + "").wrapAfter(MOSAIC_COLS), tile, tile)
                        .addAll(tiles, DemoView::tile));
    }

    private static UIForAnySwing<?, ?> tile(Tile tile) {
        return switch (tile) {
            case Face f -> box().withStyle(it -> it
                    .borderRadius(8)
                    .border(f.justLearned() ? 5 : 0, Theme.SCANNING)
                    .image(img -> img.image(f.crop()).fitMode(UI.FitComponent.WIDTH_AND_HEIGHT)));
            case More m -> text(Val.of("+" + m.count()), 40).withForeground(Theme.TEXT)
                    .withStyle(it -> it.backgroundColor(Theme.CARD).borderRadius(8))
                    .withHorizontalAlignment(UI.HorizontalAlignment.CENTER);
            case Next _ -> box().withStyle(it -> it.backgroundColor(Theme.CARD).borderRadius(8)
                    .border(3, Theme.SLOT_BORDER));
        };
    }

    /// The similarity bar: the track, the score filling it (growing in with the lock-on on a match), and the white
    /// threshold tick standing out above and below.
    @SuppressWarnings({"NullableProblems", "ConstantValue"}) // swing-tree's withStyle(Val<T>, ..) assumes a non-null T; this Val is null until there is a photo
    private static UIForBox<JBox> bar(Val<@Nullable Shot> shot) {
        int tick = Math.round(BAR_W * FindMe.THRESHOLD);
        // absolute positions: the tick overlaps the track, and the first child added paints on top
        return box().withLayout(tight(), new AC().size(fixed(BAR_W)), new AC().size(fixed(BAR_AREA)))
                .add(new CC().pos(tick + "", "0", tick + 6 + "", BAR_AREA + ""),
                        box().withStyle(it -> it.backgroundColor(Color.WHITE)))
                .add(new CC().pos("0", TICK + "", BAR_W + "", TICK + BAR + ""),
                        box().withLayout(tight(), new AC(), new AC().size(fixed(BAR)).fill())
                        .withStyle(it -> it.backgroundColor(Theme.TRACK).borderRadius(BAR / 2.0))
                        .add(box().withStyle(shot, MATCH_ANIMATION, (current, anim, it) -> {
                            boolean match = current != null && current.best() >= FindMe.THRESHOLD;
                            float score = current == null ? 0 : Theme.percentValue(current.best()) / 100f;
                            float grow = match ? PhotoPainter.ease(PhotoPainter.lockOn((float) anim.progress())) : 1;
                            return it.prefWidth(BAR_W * score * grow)
                                    .backgroundColor(match ? Theme.MATCH : Theme.NO_MATCH)
                                    .borderRadius(BAR / 2.0);
                        })));
    }

    private static UIForAnySwing<?, ?> gallery(Done done) {
        Grid grid = DemoState.grid(done.matches().size());
        if (grid.photos() == 0) {
            return text(Val.of("NOT FOUND"), 120).withForeground(Theme.NO_MATCH)
                    .withHorizontalAlignment(UI.HorizontalAlignment.CENTER);
        }
        AC cells = new AC().grow().fill().sizeGroup(); // equal cells: the last spec repeats
        return box().withLayout(tight().gridGap("20", "20").wrapAfter(grid.cols()), cells, cells)
                .apply(it -> done.matches().stream().limit(grid.photos()).forEach(shot -> it.add(box()
                        .withStyle(s -> card(s).painter(UI.Layer.CONTENT, g -> PhotoPainter.paintTile(g, area(s), shot))))))
                .applyIf(grid.more() > 0, it -> it.add(box().withLayout(stack().align("center", "center"))
                        .withStyle(DemoView::card)
                        .add(ALIGN_X_CENTER, text(Val.of("+" + grid.more()), 72).withForeground(Theme.TEXT))
                        .add(ALIGN_X_CENTER, text(Val.of("more"), LABEL).withForeground(Theme.MUTED))));
    }

    /// A MigLayout size that doesn't flex: exactly `px`.
    private static String fixed(int px) {
        return px + "!";
    }

    /// A phase's screen: the window's margins and the gaps between its regions; a hidden line takes no room.
    private static LC screen() {
        return new LC().insets(PAD + "").gridGap(GAP + "", GAP + "").hideMode(HideMode.DISREGARD);
    }

    /// Your crops on the left, the photo or the count on the right.
    private static AC columns() {
        return new AC().size(fixed(COL)).gap().grow();
    }

    /// No margins, no gaps.
    private static LC tight() {
        return new LC().insets("0").gridGap("0", "0");
    }

    /// One column, top to bottom, no margins, no gaps.
    private static LC stack() {
        return tight().wrapAfter(1);
    }

    /// Text in the theme's bold font, `px` tall.
    private static UIForLabel<JLabel> text(Val<String> text, int px) {
        return label(text).withFont(Theme.bold(px));
    }

    private static UIForLabel<JLabel> progress(Val<String> text) {
        return text(text, 44).withForeground(Theme.MUTED);
    }

    /// The rounded card a photo sits on.
    private static <C extends JComponent> ComponentStyleDelegate<C> card(ComponentStyleDelegate<C> it) {
        return it.backgroundColor(Theme.CARD).borderRadius(RADIUS);
    }

    private static Rectangle area(ComponentStyleDelegate<?> it) {
        return new Rectangle(it.componentWidth(), it.componentHeight());
    }

    /// The state while it is an `S`. Once the next phase takes over, this phase's view is on its way out: it keeps
    /// what it showed first rather than read a state that isn't its own.
    private static <S extends DemoState> Val<S> as(Val<DemoState> state, Class<S> phase) {
        S first = phase.cast(state.get());
        return state.view(first, s -> phase.isInstance(s) ? phase.cast(s) : null);
    }

    private static String clock(long ms) {
        long sec = ms / 1000;
        return String.format(Locale.ROOT, "%02d:%02d", sec / 60, sec % 60);
    }

    /// Crop tiles that fit in a column of height `h` (label included): whole rows only.
    static int mosaicSlots(int h) {
        int rows = (h - LABEL_LINE - LABEL_GAP + TILE_GAP) / (TILE + TILE_GAP);
        return Math.max(0, rows) * MOSAIC_COLS;
    }

    /// Crops drawn in `slots` tiles; when they overflow, the last tile is "+K" instead (K = crops - drawn).
    static int shownCrops(int crops, int slots) {
        return crops <= slots ? crops : Math.max(0, slots - 1);
    }

    /// The mosaic's tiles: the newest crops that fit, the "+K" overflow tile, and while `learning` the face just
    /// learned marked and, while there's room, an empty slot for the next.
    static Tuple<Tile> tiles(List<BufferedImage> crops, int slots, boolean learning) {
        int shown = shownCrops(crops.size(), slots);
        int from = crops.size() - shown; // overflowing: the oldest go into "+K", so the face just learned stays in view
        List<Tile> tiles = new ArrayList<>();
        for (int i = 0; i < Math.min(slots, crops.size() + (learning ? 1 : 0)); i++) {
            tiles.add(i < shown ? new Face(crops.get(from + i), learning && i == shown - 1)
                    : i < crops.size() ? new More(crops.size() - shown)
                    : new Next());
        }
        return Tuple.of(Tile.class, tiles);
    }

    /// Greedy word wrap to `max` pixels; a word wider than a line gets a line of its own.
    static List<String> wrap(String text, int max, ToIntFunction<String> width) {
        List<String> lines = new ArrayList<>();
        String line = "";
        for (String word : text.split("\\s+")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && width.applyAsInt(next) > max) {
                lines.add(line);
                line = word;
            } else {
                line = next;
            }
        }
        if (!line.isEmpty()) {
            lines.add(line);
        }
        return lines;
    }
}

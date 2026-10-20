package findme;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.TextAttribute;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/// Java2D drawing of the photos on their cards: face boxes, landmark mesh, score tags, the ✓/✗ mark, the match stamp,
/// and the gallery tiles, at the demo's phone-readable sizes (design pixels at 1080×960).
// nested ternaries read fine here
@SuppressWarnings("java:S3358")
final class PhotoPainter {
    static final int RADIUS = 12, STROKE = 6, TAG_FONT = 44, TAG_H = 52, TAG_PAD = 8, VERDICT = 180;
    private static final int[] GLOW = {8, 14, 20};
    private static final float[] GLOW_ALPHA = {.35f, .20f, .10f};
    /// How much larger than the face a matching box starts before it tightens on (lock-on).
    private static final float LOCK_FROM = .5f;
    /// The 5 landmarks are left eye, right eye, nose, left and right mouth corner; these pairs draw the face mesh.
    private static final int[][] MESH = {{0, 1}, {0, 2}, {1, 2}, {2, 3}, {2, 4}, {3, 4}};
    // landmarks at 60%
    private static final Color MESH_LINE = new Color(253, 224, 71, 153);
    // background at 75%
    private static final Color STAMP_BG = new Color(15, 23, 42, 191);

    private PhotoPainter() {
    }

    /// The scan's photo, fit to `area`, `progress` (0..1) into its animation: a match locks on and gets the stamp, a
    /// miss gets the ✗ corner mark.
    static void paintScan(Graphics2D g, Rectangle area, Shot shot, float progress) {
        if (shot.best() >= FindMe.THRESHOLD) {
            paintShot(g, area, shot, lockOn(progress), false);
            paintStamp(g, area, shot.best(), stamp(progress));
        } else {
            paintShot(g, area, shot, 1, true);
        }
    }

    /// The lock-on's 0..1 over the first half of the match animation.
    static float lockOn(float progress) {
        return Math.clamp(2 * progress, 0f, 1f);
    }

    /// The stamp's 0..1 over the second half: it lands once the box has closed on the face.
    static float stamp(float progress) {
        return Math.clamp(2 * progress - 1, 0f, 1f);
    }

    /// The scan's photo, fit to `area`: boxes red/green by each face's own score, `lockOn` (0..1) tightening the
    /// matching ones, and the ✓/✗ corner mark when `verdict`.
    static void paintShot(Graphics2D g, Rectangle area, Shot shot, float lockOn, boolean verdict) {
        paint(g, area, shot, contain(shot, area), lockOn, false, false);
        if (verdict) {
            paintVerdict(g, area, shot.best() >= FindMe.THRESHOLD);
        }
    }

    /// A reference photo while learning: boxes in scanning blue, no scores (every reference face is you).
    static void paintLearning(Graphics2D g, Rectangle area, Shot shot) {
        paint(g, area, shot, contain(shot, area), 1, true, false);
    }

    /// A gallery tile: the photo cropped to fill `area` around its best face, with only the matching boxes.
    static void paintTile(Graphics2D g, Rectangle area, Shot shot) {
        Box best = best(shot);
        float fx = best == null ? shot.image().getWidth() / 2f : (best.x1() + best.x2()) / 2;
        float fy = best == null ? shot.image().getHeight() / 2f : (best.y1() + best.y2()) / 2;
        paint(g, area, shot, cover(shot.image().getWidth(), shot.image().getHeight(), area, fx, fy), 1, false, true);
    }

    /// The "MATCH / similarity n%" stamp, landing on the photo as `progress` goes 0..1.
    static void paintStamp(Graphics2D g, Rectangle area, float similarity, float progress) {
        if (progress <= 0) {
            return;
        }
        Font big = Theme.bold(130).deriveFont(Map.of(TextAttribute.TRACKING, 8 / 130f));
        Font small = Theme.bold(46);
        String sub = "similarity " + Theme.percent(similarity);
        int w = Math.max(g.getFontMetrics(big).stringWidth("MATCH"), g.getFontMetrics(small).stringWidth(sub)) + 60;
        int h = 6 + 130 + 16 + 46 + 12;
        float e = ease(progress);
        Graphics2D s = (Graphics2D) g.create();
        s.translate(area.getCenterX(), area.y + area.height * .74);
        s.rotate(Math.toRadians(-6));
        // slams down from larger
        s.scale(1 + .6 * (1 - e), 1 + .6 * (1 - e));
        s.setComposite(AlphaComposite.SrcOver.derive(e));
        Rectangle box = new Rectangle(-w / 2 - 8, -h / 2 - 8, w + 16, h + 16);
        s.setColor(STAMP_BG);
        s.fill(box);
        s.setColor(Theme.MATCH);
        s.setStroke(new BasicStroke(8));
        s.draw(new Rectangle(box.x + 4, box.y + 4, box.width - 8, box.height - 8));
        centered(s, "MATCH", big, Theme.MATCH, 0, -h / 2.0 + 6 + 65);
        centered(s, sub, small, Theme.MATCH, 0, h / 2.0 - 12 - 23);
        s.dispose();
    }

    /// Whether a face gets its score tag: dropped when the box is narrower than the tag, except on the best face.
    static boolean showsTag(double boxWidth, int tagWidth, boolean best) {
        return best || boxWidth >= tagWidth;
    }

    /// Where an `iw`×`ih` image lands when fit whole inside `area`, centered.
    static Rectangle2D.Double contain(int iw, int ih, Rectangle area) {
        double k = Math.min((double) area.width / iw, (double) area.height / ih);
        return new Rectangle2D.Double(area.getCenterX() - iw * k / 2, area.getCenterY() - ih * k / 2, iw * k, ih * k);
    }

    /// Where an `iw`×`ih` image lands when it fills `area`, centered on image point (fx, fy) as far as its edges allow.
    static Rectangle2D.Double cover(int iw, int ih, Rectangle area, float fx, float fy) {
        double k = Math.max((double) area.width / iw, (double) area.height / ih);
        double w = iw * k, h = ih * k;
        // min/max, not Math.clamp: w can come out an ulp short of the area, which would make clamp's bounds cross
        //noinspection MathClampMigration
        double x = Math.min(area.x, Math.max(area.getMaxX() - w, area.getCenterX() - fx * k));
        //noinspection MathClampMigration
        double y = Math.min(area.y, Math.max(area.getMaxY() - h, area.getCenterY() - fy * k));
        return new Rectangle2D.Double(x, y, w, h);
    }

    static float ease(float t) {
        float u = 1 - t;
        // ease-out cubic
        return 1 - u * u * u;
    }

    /// Draws `s` with its cap height centered on `cy` and its middle on `cx`.
    static void centered(Graphics2D g, String s, Font f, Color c, double cx, double cy) {
        g.setFont(f);
        g.setColor(c);
        g.drawString(s, (float) (cx - g.getFontMetrics().stringWidth(s) / 2.0), baseline(g, f, cy));
    }

    /// The baseline that centers capital letters and digits of `f` on `cy`, the same for any string.
    static float baseline(Graphics2D g, Font f, double cy) {
        double cap = f.createGlyphVector(g.getFontRenderContext(), "H").getVisualBounds().getHeight();
        return (float) (cy + cap / 2);
    }

    static void antialias(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    private static Rectangle2D.Double contain(Shot shot, Rectangle area) {
        return contain(shot.image().getWidth(), shot.image().getHeight(), area);
    }

    private static @Nullable Box best(Shot shot) {
        Box best = null;
        for (Box b : shot.boxes()) {
            if (best == null || b.similarity() > best.similarity()) {
                best = b;
            }
        }
        return best;
    }

    private static void paint(
            Graphics2D g,
            Rectangle area,
            Shot shot,
            Rectangle2D.Double at,
            float lockOn,
            boolean learning,
            boolean tile
    ) {
        // also for the ✓/✗ and the stamp drawn after
        antialias(g);
        Graphics2D p = (Graphics2D) g.create();
        p.clip(rounded(area));
        p.drawImage(
                shot.image(),
                (int) Math.round(at.x),
                (int) Math.round(at.y),
                (int) Math.round(at.width),
                (int) Math.round(at.height),
                null
        );
        double k = at.width / shot.image().getWidth();
        Box best = best(shot);
        List<Box> boxes = shot
            .boxes()
            .stream()
            .filter(b -> !tile || b.similarity() >= FindMe.THRESHOLD)
            .toList();
        float grow = LOCK_FROM * (1 - ease(lockOn));
        for (Box b : boxes) {
            boolean match = b.similarity() >= FindMe.THRESHOLD;
            Color c = learning ? Theme.SCANNING : match ? Theme.MATCH : Theme.NO_MATCH;
            Rectangle2D.Double r = rect(b, at, k, match && !learning ? grow : 0);
            Shape outline = new RoundRectangle2D.Double(
                    r.x - STROKE / 2.0,
                    r.y - STROKE / 2.0,
                    r.width + STROKE,
                    r.height + STROKE,
                    6,
                    6
            );
            if (match && !learning) {
                for (int i = GLOW.length - 1; i >= 0; i--) {
                    p.setComposite(AlphaComposite.SrcOver.derive(GLOW_ALPHA[i] * ease(lockOn)));
                    p.setColor(c);
                    p.setStroke(new BasicStroke(GLOW[i] + STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    p.draw(outline);
                }
                p.setComposite(AlphaComposite.SrcOver);
            }
            p.setColor(c);
            p.setStroke(new BasicStroke(STROKE));
            p.draw(outline);
            mesh(p, b.landmarks(), at, k, (b.x2() - b.x1()) * k);
        }
        if (!learning) {
            // tags last, so no box draws over another face's score
            Font f = Theme.bold(TAG_FONT);
            p.setFont(f);
            for (Box b : boxes) {
                boolean match = b.similarity() >= FindMe.THRESHOLD;
                Rectangle2D.Double r = rect(b, at, k, match ? grow : 0);
                String tag = Theme.percent(b.similarity());
                int tw = p.getFontMetrics().stringWidth(tag) + 2 * TAG_PAD;
                if (!showsTag(r.width + 2 * STROKE, tw, b == best)) {
                    continue;
                }
                // min/max, not Math.clamp: a tag wider than the area would cross clamp's bounds; it starts at the left
                //noinspection MathClampMigration
                double tx = Math.max(area.x, Math.min(r.x - STROKE, area.getMaxX() - tw)), ty = r.y - STROKE - TAG_H;
                if (ty < area.y) {
                    // no room above the box: inside its top edge instead of cut off
                    ty = r.y;
                }
                p.setColor(match ? Theme.MATCH : Theme.NO_MATCH);
                p.fill(new Rectangle2D.Double(tx, ty, tw, TAG_H));
                p.setColor(match ? Color.BLACK : Color.WHITE);
                p.drawString(tag, (float) tx + TAG_PAD, baseline(p, f, ty + TAG_H / 2.0));
            }
        }
        p.dispose();
    }

    /// Landmark marker diameter for a face box `boxWidth` wide on screen: small enough to never hide the face.
    static double marker(double boxWidth) {
        return Math.clamp(boxWidth * .06, 4, 10);
    }

    /// The movie-style face mesh: thin lines between the landmarks, a hollow ring with a center dot on each.
    private static void mesh(Graphics2D g, float[] lm, Rectangle2D.Double at, double k, double boxWidth) {
        if (lm.length < 10) {
            return;
        }
        double[] x = new double[5], y = new double[5];
        for (int i = 0; i < 5; i++) {
            x[i] = at.x + lm[2 * i] * k;
            y[i] = at.y + lm[2 * i + 1] * k;
        }
        g.setColor(MESH_LINE);
        g.setStroke(new BasicStroke(1.25f));
        for (int[] e : MESH) {
            g.draw(new Line2D.Double(x[e[0]], y[e[0]], x[e[1]], y[e[1]]));
        }
        double d = marker(boxWidth);
        g.setColor(Theme.LANDMARK);
        g.setStroke(new BasicStroke(1.75f));
        for (int i = 0; i < 5; i++) {
            g.draw(new Ellipse2D.Double(x[i] - d / 2, y[i] - d / 2, d, d));
            g.fill(new Ellipse2D.Double(x[i] - .75, y[i] - .75, 1.5, 1.5));
        }
    }

    /// Box `b` on screen, grown by `grow` of its size around its center.
    private static Rectangle2D.Double rect(Box b, Rectangle2D.Double at, double k, double grow) {
        double w = (b.x2() - b.x1()) * k, h = (b.y2() - b.y1()) * k;
        double x = at.x + b.x1() * k - w * grow / 2, y = at.y + b.y1() * k - h * grow / 2;
        return new Rectangle2D.Double(x, y, w * (1 + grow), h * (1 + grow));
    }

    private static void paintVerdict(Graphics2D g, Rectangle area, boolean ok) {
        double u = VERDICT / 24.0, x0 = area.getMaxX() - 16 - VERDICT, y0 = area.getMaxY() - 16 - VERDICT;
        g.setColor(ok ? Theme.MATCH : Theme.NO_MATCH);
        g.fill(new Ellipse2D.Double(x0 + u, y0 + u, 22 * u, 22 * u));
        Path2D.Double mark = new Path2D.Double();
        if (ok) {
            mark.moveTo(6.5, 12.5);
            mark.lineTo(10, 16);
            mark.lineTo(17.5, 8);
        } else {
            mark.moveTo(7.5, 7.5);
            mark.lineTo(16.5, 16.5);
            mark.moveTo(16.5, 7.5);
            mark.lineTo(7.5, 16.5);
        }
        Graphics2D m = (Graphics2D) g.create();
        m.translate(x0, y0);
        m.scale(u, u);
        m.setColor(ok ? Theme.BG : Color.WHITE);
        m.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        m.draw(mark);
        m.dispose();
    }

    private static Shape rounded(Rectangle r) {
        return new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, 2 * RADIUS, 2 * RADIUS);
    }
}

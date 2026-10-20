package findme;

import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoViewTest {
    @Test void mosaicFitsWholeRowsOnly() {
        assertEquals(15, DemoView.mosaicSlots(DemoView.SCANNING_BODY)); // 5 rows
        assertEquals(18, DemoView.mosaicSlots(DemoView.LEARNING_BODY)); // 6 rows
        assertEquals(18, DemoView.mosaicSlots(DemoView.LISTING_BODY));
        assertEquals(0, DemoView.mosaicSlots(40));
    }

    @Test void overflowingCropsGiveUpTheLastTileForTheCount() {
        assertEquals(10, DemoView.shownCrops(10, 15));
        assertEquals(15, DemoView.shownCrops(15, 15));
        assertEquals(14, DemoView.shownCrops(30, 15)); // "+16" in the 15th tile
        assertEquals(0, DemoView.shownCrops(3, 0));
    }

    @Test void mosaicTilesCountTheOverflowAndMarkTheFaceJustLearned() {
        List<BufferedImage> crops = java.util.stream.Stream.generate(
                () -> new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)).limit(30).toList();
        var learning = DemoView.tiles(crops.subList(0, 3), 18, true);
        assertEquals(4, learning.size());
        assertEquals(new DemoView.Face(crops.get(2), true), learning.get(2));
        assertEquals(new DemoView.Next(), learning.get(3));
        var overflow = DemoView.tiles(crops, 15, false);
        assertEquals(15, overflow.size());
        assertEquals(new DemoView.Face(crops.get(16), false), overflow.get(0)); // the newest 14; "+16" the oldest
        assertEquals(new DemoView.Face(crops.get(29), false), overflow.get(13));
        assertEquals(new DemoView.More(16), overflow.get(14));
        var learningOverflow = DemoView.tiles(crops, 15, true);
        assertEquals(15, learningOverflow.size()); // no room for the next slot
        assertEquals(new DemoView.Face(crops.get(29), true), learningOverflow.get(13)); // the face just learned, marked
        assertEquals(new DemoView.More(16), learningOverflow.get(14));
    }

    @Test void lockOnThenStamp() {
        assertEquals(0, PhotoPainter.lockOn(0));
        assertEquals(.5f, PhotoPainter.lockOn(.25f), 1e-6);
        assertEquals(1, PhotoPainter.lockOn(.6f));
        assertEquals(0, PhotoPainter.stamp(.5f));
        assertEquals(.5f, PhotoPainter.stamp(.75f), 1e-6);
        assertEquals(1, PhotoPainter.stamp(1));
    }

    @Test void wrapsOnWords() {
        assertEquals(List.of("no usable", "reference", "photos"), DemoView.wrap("no usable reference photos", 9, String::length));
        assertEquals(List.of("averylongword", "x"), DemoView.wrap("averylongword x", 5, String::length));
    }

    @Test void facesNarrowerThanTheirTagLoseIt() {
        assertFalse(PhotoPainter.showsTag(0, 100, false));
        assertFalse(PhotoPainter.showsTag(99.9, 100, false));
        assertTrue(PhotoPainter.showsTag(100, 100, false)); // the boundary: exactly as wide as the tag fits
        assertTrue(PhotoPainter.showsTag(120, 100, false));
    }

    @Test void landmarkMarkersScaleWithTheFace() {
        assertEquals(4, PhotoPainter.marker(20));
        assertEquals(6, PhotoPainter.marker(100), 1e-9);
        assertEquals(10, PhotoPainter.marker(400));
    }

    @Test void containFitsWholeAndCoverFillsAroundTheFocus() {
        Rectangle area = new Rectangle(0, 0, 600, 600);
        assertEquals(new Rectangle2D.Double(0, 100, 600, 400), PhotoPainter.contain(1080, 720, area));
        // cover: scaled to 900×600, the focus at the right edge pins the image's right edge to the area's
        assertEquals(new Rectangle2D.Double(-300, 0, 900, 600), PhotoPainter.cover(1080, 720, area, 1080, 360));
        assertEquals(new Rectangle2D.Double(-150, 0, 900, 600), PhotoPainter.cover(1080, 720, area, 540, 360));
    }

    @Test void coverNeverThrowsAndAlwaysFillsTheGalleryCells() {
        // the gallery's cells for 1, 2, 4 and 6+ matches (DemoPaintTest checks the layout gives these)
        Rectangle[] cells = {new Rectangle(40, 172, 1000, 748), new Rectangle(550, 172, 490, 748),
                new Rectangle(550, 556, 490, 364), new Rectangle(720, 556, 320, 364)};
        int[][] images = {{1080, 720}, {720, 1080}, {1080, 810}, {810, 1080}, {1080, 608}, {608, 1080}, {1080, 1080},
                {1080, 675}};
        for (Rectangle cell : cells) {
            for (int[] im : images) {
                for (float[] f : new float[][] {{0, 0}, {im[0] / 2f, im[1] / 2f}, {im[0], im[1]}}) {
                    Rectangle2D.Double r = PhotoPainter.cover(im[0], im[1], cell, f[0], f[1]);
                    String at = im[0] + "x" + im[1] + " in " + cell;
                    assertTrue(r.x <= cell.x + 1e-6 && r.getMaxX() >= cell.getMaxX() - 1e-6, at);
                    assertTrue(r.y <= cell.y + 1e-6 && r.getMaxY() >= cell.getMaxY() - 1e-6, at);
                }
            }
        }
        // the reviewer's case: a portrait photo in a 2-match cell
        Rectangle2D.Double r = PhotoPainter.cover(720, 1080, new Rectangle(40, 172, 490, 748), 360, 540);
        assertEquals(172, r.y, 1e-6);
        assertEquals(748, r.height, 1e-6);
    }
}

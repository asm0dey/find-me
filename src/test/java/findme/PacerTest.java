package findme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

// the sleeps give the pacer's own thread time to act, or to show it doesn't
@SuppressWarnings("java:S2925")
class PacerTest {
    private static final byte[] JPEG = jpeg();

    @org.junit.jupiter.api.BeforeAll
    static void loadOpenCv() throws IOException {
        // the first decode loads the natives: keep that out of the holds measured below
        Faces.decode(JPEG).close();
    }

    private final List<Long> sleeps = new java.util.concurrent.CopyOnWriteArrayList<>();
    // a deadline nobody reaches: full pace
    private static final long FAR = Long.MAX_VALUE / 4;

    @Test
    void holdsMatchesLongerThanMisses() {
        Pacer.Pace full = new Pacer.Pace(150, 1500);
        assertEquals(1500, Pacer.holdMs(scored(0.71f), full));
        assertEquals(150, Pacer.holdMs(scored(0.12f), full));
        assertEquals(0, Pacer.holdMs(new Pacer.Failed(), full));
    }

    @Test
    void paceFitsTheDeadline() {
        // plenty of time
        assertEquals(new Pacer.Pace(150, 1500), Pacer.pace(600_000, 100, 3));
        // misses at the floor still don't fit: matches share what is left, (95 s − 2500 × 30 ms) / 14
        assertEquals(new Pacer.Pace(30, 1428), Pacer.pace(95_000, 2500, 14));
        // but no shorter than 1000 ms
        assertEquals(new Pacer.Pace(30, 1000), Pacer.pace(60_000, 1500, 20));
        // just fits at the floor: full holds
        assertEquals(new Pacer.Pace(30, 1500), Pacer.pace(96_000, 2500, 14));
        // matches take at most half, floor 1000
        assertEquals(new Pacer.Pace(30, 1000), Pacer.pace(20_000, 100, 40));
        // (40 s − 15 s) / 200
        assertEquals(new Pacer.Pace(125, 1500), Pacer.pace(40_000, 200, 10));
        // 15 s of matches > half of 25 s
        assertEquals(new Pacer.Pace(62, 1250), Pacer.pace(25_000, 200, 10));
        // late: as fast as allowed
        assertEquals(new Pacer.Pace(30, 1000), Pacer.pace(0, 10, 1));
        assertEquals(new Pacer.Pace(30, 1000), Pacer.pace(-5, 10, 1));
        // nothing left but this photo
        assertEquals(new Pacer.Pace(150, 1500), Pacer.pace(10_000, 0, 0));
    }

    @Test
    void neverRunsAheadOfThePipelineAndLearnsTheTotal() throws Exception {
        var queue = new LinkedBlockingQueue<Pacer.Result>();
        var published = new java.util.concurrent.CopyOnWriteArrayList<DemoState>();
        Pacer p = new Pacer(queue, List.of(), new float[] {1, 0}, published::add, _ -> {}, FAR);
        p.add(2);
        var run = new java.util.concurrent.FutureTask<>(p::run);
        Thread.ofPlatform().start(run);
        Thread.sleep(200);
        // nothing scanned yet: nothing shown
        assertTrue(published.isEmpty());
        queue.add(scored(0.1f));
        while (published.isEmpty()) {
            // a deliberate poll: the pacer runs on its own thread
            //noinspection BusyWait
            Thread.sleep(5);
        }
        Scanning first = (Scanning) published.getFirst();
        assertEquals(List.of(1, 2, false), List.of(first.shown(), first.total(), first.totalFinal()));
        Thread.sleep(200);
        // the queue is empty: it waits for the next photo
        assertEquals(1, published.size());
        p.add(1);
        p.listingFinished();
        queue.add(scored(0.2f));
        queue.add(scored(0.3f));
        // returns once all 3 are shown
        run.get(5, java.util.concurrent.TimeUnit.SECONDS);
        Scanning last = (Scanning) published.getLast();
        assertEquals(List.of(3, 3, true), List.of(last.shown(), last.total(), last.totalFinal()));
    }

    @Test
    void missesTakeTheFloorWhileTheTotalStillGrows() throws InterruptedException {
        var queue = new LinkedBlockingQueue<Pacer.Result>(List.of(scored(0.1f)));
        Pacer[] p = new Pacer[1];
        p[0] = new Pacer(queue, List.of(), new float[] {1, 0}, _ -> {}, ms -> {
            if (sleeps.isEmpty()) {
                // the last album arrives while the first photo is on screen
                p[0].add(1);
                p[0].listingFinished();
                queue.add(scored(0.2f));
            }
            sleeps.add(ms);
        }, FAR);
        p[0].add(1);
        p[0].run();
        assertEquals(2, sleeps.size());
        // the total is unknown: as fast as allowed
        assertTrue(sleeps.getFirst() <= 30, "while listing: " + sleeps);
        // then the full pace, with time to spare
        assertTrue(sleeps.getLast() > 100, "listed: " + sleeps);
    }

    @Test
    void failuresAdvanceTheCounterWithoutShowing() throws InterruptedException {
        List<DemoState> published = run(scored(0.1f), new Pacer.Failed(), scored(0.8f));
        Scanning last = (Scanning) published.getLast();
        assertEquals(3, last.shown());
        assertEquals(1, last.found());
        // nothing slept for the failure; each hold less the ms spent decoding the photo, however slow the machine:
        // a miss at most its 150 ms, the match at most 1500 ms and longer than any miss
        assertEquals(2, sleeps.size());
        assertTrue(sleeps.get(0) <= 150, sleeps.toString());
        assertTrue(sleeps.get(1) > 150 && sleeps.get(1) <= 1500, sleeps.toString());
    }

    @Test
    void negativeSimilarityIsAMiss() {
        assertEquals(150, Pacer.holdMs(scored(-0.05f), new Pacer.Pace(150, 1500)));
    }

    @Test
    void slowedDownOnlyWhileBehindThePipeline() throws InterruptedException {
        // one result, queue empty after the take, pipeline still running: the display keeps up
        assertFalse(((Scanning) run(scored(0.1f)).getLast()).slowedDown());
        // two queued: the first is shown while the second waits
        assertTrue(((Scanning) run(scored(0.1f), scored(0.2f)).getFirst()).slowedDown());
        // pipeline finished: whatever is left is shown at display pace
        var queue = new LinkedBlockingQueue<Pacer.Result>(List.of(scored(0.1f)));
        List<DemoState> published = new ArrayList<>();
        Pacer p = pacer(queue, published::add);
        p.pipelineFinished();
        p.run();
        assertTrue(((Scanning) published.getLast()).slowedDown());
    }

    @Test
    void matchesAreReturnedBestFirst() throws InterruptedException {
        var queue = new LinkedBlockingQueue<Pacer.Result>(List.of(scored(0.5f), scored(0.9f), scored(0.6f)));
        List<Shot> matches = pacer(queue, _ -> {}).run();
        assertEquals(List.of(0.9f, 0.6f, 0.5f), matches.stream().map(Shot::best).toList());
    }

    @Test
    void boxesAreScaledToTheDisplayedImage() throws InterruptedException {
        // a 2160-wide photo shows at 1080: every coordinate halves; similarity is the face's own dot with me
        byte[] big = jpeg(2160, 1080);
        var face = new Faces.Face(
                100,
                200,
                300,
                400,
                new float[] {10, 20, 30, 40, 50, 60, 70, 80, 90, 100},
                new float[] {0.6f, 0.8f}
        );
        var queue = new LinkedBlockingQueue<Pacer.Result>(List.of(new Pacer.Scored(big, List.of(face), 0.6f)));
        List<DemoState> published = new ArrayList<>();
        pacer(queue, published::add).run();
        Shot shot = ((Scanning) published.getLast()).current();
        assertNotNull(shot);
        assertEquals(1080, shot.image().getWidth());
        Box b = shot.boxes().getFirst();
        assertEquals(List.of(50f, 100f, 150f, 200f), List.of(b.x1(), b.y1(), b.x2(), b.y2()));
        assertEquals(5f, b.landmarks()[0]);
        assertEquals(0.6f, b.similarity(), 1e-6);
    }

    private List<DemoState> run(Pacer.Result... results) throws InterruptedException {
        List<DemoState> published = new ArrayList<>();
        pacer(new LinkedBlockingQueue<>(List.of(results)), published::add).run();
        return published;
    }

    /// A pacer for everything already queued, with the listing finished and no deadline in sight.
    private Pacer pacer(LinkedBlockingQueue<Pacer.Result> queue, java.util.function.Consumer<DemoState> publish) {
        Pacer p = new Pacer(queue, List.of(), new float[] {1, 0}, publish, sleeps::add, FAR);
        p.add(queue.size());
        p.listingFinished();
        return p;
    }

    /// A photo with one face whose similarity to me = (1, 0) is `best`.
    private static Pacer.Scored scored(float best) {
        float[] e = {best, (float) Math.sqrt(Math.max(0, 1 - best * best))};
        return new Pacer.Scored(JPEG, List.of(new Faces.Face(1, 1, 5, 5, new float[10], e)), best);
    }

    private static byte[] jpeg() {
        return jpeg(16, 16);
    }

    private static byte[] jpeg(int w, int h) {
        try {
            var out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR), "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

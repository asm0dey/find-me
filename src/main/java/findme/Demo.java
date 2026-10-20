package findme;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.swing.WindowConstants;
import org.bytedeco.javacpp.PointerScope;
import org.bytedeco.opencv.opencv_core.Mat;
import org.jspecify.annotations.Nullable;
import sprouts.Var;
import swingtree.SwingTree;
import swingtree.UI;
import swingtree.UIFactoryMethods;

/// The demo: the real pipeline, with listing on its own thread from launch, overlapping learning and then the scan,
/// which takes each album's photos as soon as the album is listed. The director thread learns and feeds the scan, the
/// pacer shows its results; together they publish an immutable [DemoState] at every step, which the window
/// ([DemoView]) renders.
// stderr for what the window can't show: a failed decode or listing tick
@SuppressWarnings("java:S106")
final class Demo {
    private static final int CROP_SIDE = 448;
    private static final long TICK_MS = 100;
    /// Ends the listed albums on the lister → director queue; compared by identity.
    static final List<FindMe.Photo> END = new ArrayList<>();

    private Demo() {
    }

    /// `args` are the CLI's: reference folder, then albums and folders. Done and Failed stay on screen; closing the
    /// window exits, 0 after Done and 1 otherwise (also ending a pacer, lister or director still running).
    static void run(String[] args) {
        long launch = System.nanoTime();
        // 1080×960 device pixels, whatever the desktop's scale
        System.setProperty("sun.java2d.uiScale", "1");
        if (args.length < 2) {
            System.err.println(FindMe.USAGE);
            System.exit(1);
        }
        SwingTree.initializeUsing(cfg -> cfg.uiScaleFactor(1f));
        Var<DemoState> state = Var.of(DemoState.class, new Learning(List.of(), 0, 0, null, 0, 0, 0));
        UIFactoryMethods.show("find-me", frame -> {
            frame.setResizable(false);
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    System.exit(state.get() instanceof Done ? 0 : 1);
                }
            });
            return DemoView.create(state);
        });
        // the EDT only ever reads these immutable values, set in publish order
        Screen screen = new Screen(s -> UI.runLater(() -> state.set(s)), launch);
        Thread
            .ofPlatform()
            .name("director")
            .start(() -> direct(args, screen, launch + TimeUnit.MILLISECONDS.toNanos(Theme.RUN_MS)));
    }

    /// Composes the screen from learning and listing, which run at once, until the pacer takes over; Done or Failed
    /// is final. Every publish goes through here, under one lock, so they reach the view in order.
    static final class Screen {
        private final Consumer<DemoState> publish;
        private final long start;
        private Learning learning = new Learning(List.of(), 0, 0, null, 0, 0, 0);
        private int album;
        private int albums;
        private int listed;
        private boolean listingFinished;
        private boolean learned;
        private boolean scanning;
        private boolean over;

        Screen(Consumer<DemoState> publish, long start) {
            this.publish = publish;
            this.start = start;
        }

        synchronized void learning(Learning l) {
            learning = l;
            show();
        }

        synchronized void listing(int album, int albums, int listed) {
            this.album = album;
            this.albums = albums;
            this.listed = listed;
            show();
        }

        synchronized void listingFinished(int listed) {
            this.listed = listed;
            listingFinished = true;
            show();
        }

        /// Learning is over: the Listing screen until the first scanned photo.
        synchronized void learned() {
            learned = true;
            show();
        }

        synchronized void tick() {
            show();
        }

        synchronized void scanning(DemoState s) {
            if (!over) {
                if (!scanning && s instanceof Scanning(
                        var crops,
                        var shown,
                        var total,
                        var found,
                        var slowedDown,
                        var current,
                        var totalFinal
                ) && current != null) {
                    // the scan screen takes its first state as settled; built on no photo, it animates the first one
                    publish.accept(new Scanning(crops, shown, total, found, slowedDown, null, totalFinal));
                }
                scanning = true;
                publish.accept(s);
            }
        }

        synchronized void end(DemoState s) {
            if (!over) {
                over = true;
                publish.accept(s);
            }
        }

        private void show() {
            if (scanning || over) {
                return;
            }
            if (learned) {
                publish.accept(
                        new Listing(
                                learning.crops(),
                                listingFinished ? albums : album,
                                albums,
                                listed,
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                        )
                );
            } else {
                Learning l = learning;
                publish.accept(
                        new Learning(
                                l.crops(),
                                l.done(),
                                l.total(),
                                l.current(),
                                listed,
                                listingFinished ? 0 : album,
                                albums
                        )
                );
            }
        }
    }

    // catches even an Error, so the view always hears how the run ended
    @SuppressWarnings("java:S1181")
    private static void direct(String[] args, Screen screen, long deadline) {
        try (var ticker = Executors.newSingleThreadScheduledExecutor()) {
            ticker.scheduleAtFixedRate(() -> {
                try {
                    // a throw would silently cancel scheduleAtFixedRate
                    screen.tick();
                } catch (RuntimeException e) {
                    System.err.println("screen tick failed: " + e);
                }
            }, 0, TICK_MS, TimeUnit.MILLISECONDS);
            var listed = new LinkedBlockingQueue<List<FindMe.Photo>>();
            var listing = new FutureTask<@Nullable Void>(() -> {
                try {
                    list(List.of(args).subList(1, args.length), screen, listed);
                } finally {
                    listed.add(END);
                }
                return null;
            });
            // the single GraalPy context lives and dies on this thread
            Thread.ofPlatform().name("lister").start(listing);
            try {
                FindMe.ensureModels();
                try (Faces faces = new Faces(FindMe.MODELS)) {
                    List<BufferedImage> crops = new ArrayList<>();
                    float[] me = learn(faces, Path.of(args[0]), crops, screen);
                    if (me == null) {
                        screen.end(new Failed("no usable reference photos"));
                        return;
                    }
                    screen.learned();
                    Semaphore cpus = new Semaphore(FindMe.CPUS);
                    scan(p -> check(faces, cpus, me, p), me, listed, listing, crops, screen, deadline);
                }
            } finally {
                // a no-op once listed; after a failure, stops the lister where it can be stopped
                listing.cancel(true);
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            screen.end(new Failed("interrupted"));
        } catch (Throwable e) {
            // whatever went wrong, even an Error, the view must hear about it
            Throwable t = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            screen.end(new Failed(t.getMessage() != null ? t.getMessage() : t.toString()));
        }
    }

    /// Averaged embedding of the reference photos with exactly one face, as the CLI does; null if there are none.
    private static float @Nullable [] learn(Faces faces, Path dir, List<BufferedImage> crops, Screen screen)
            throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IOException("reference folder not found: " + dir);
        }
        List<Path> files;
        try (var s = Files.list(dir)) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        }
        List<float[]> embeddings = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            try (var _ = new PointerScope()) {
                Mat img = Faces.decode(Files.readAllBytes(files.get(i)));
                List<Faces.Face> found = faces.faces(img);
                if (found.size() != 1) {
                    continue;
                }
                Faces.Face f = found.getFirst();
                embeddings.add(f.embedding());
                crops.add(Faces.toImage(Faces.alignedCrop(img, f.landmarks(), CROP_SIDE), CROP_SIDE));
                // similarity to itself: a reference face is you by definition, and Learning shows no verdict;
                // the listing fields are the Screen's to fill
                screen.learning(
                        new Learning(crops, i + 1, files.size(), Pacer.shot(img, found, f.embedding(), 1), 0, 0, 0)
                );
            } catch (IOException _) {
                // not an image: skipped, as the CLI does
            }
        }
        return embeddings.isEmpty() ? null : FindMe.average(embeddings);
    }

    /// Lists every folder, then every album, like the CLI but against an empty seen set (the demo always rescans
    /// everything), putting each one's new photos on `listed` as soon as it is listed.
    private static void list(List<String> sources, Screen screen, BlockingQueue<List<FindMe.Photo>> listed)
            throws IOException,
            InterruptedException {
        Map<String, FindMe.Photo> found = new LinkedHashMap<>();
        List<String> albums = new ArrayList<>();
        List<String> dirs = new ArrayList<>();
        for (String a : sources) {
            boolean dir = Files.isDirectory(Path.of(a));
            if (!dir && !a.contains("://")) {
                // not an album URL either: a folder that isn't there
                throw new IOException("album folder not found: " + a);
            }
            (dir ? dirs : albums).add(a);
        }
        for (String d : dirs) {
            int before = found.size();
            FindMe.listDir(Path.of(d), new HashSet<>(), found);
            listed.add(List.copyOf(new ArrayList<>(found.values()).subList(before, found.size())));
            screen.listing(0, albums.size(), found.size());
        }
        if (!albums.isEmpty()) {
            // skips starting Python when there's nothing to list
            // runs inside gallery-dl's job, on this thread: only update counters, queue and publish, never throw
            FindMe.listAlbums(albums, new HashSet<>(), found, new FindMe.AlbumProgress() {
                @Override
                public void photo(int album, int n, int listedInAlbum) {
                    report(() -> screen.listing(album, n, found.size() + listedInAlbum));
                }

                @Override
                public void albumStarted(int album, int n) {
                    report(() -> screen.listing(album, n, found.size()));
                }

                @Override
                public void albumListed(int album, int n, List<FindMe.Photo> photos) {
                    report(() -> {
                        listed.add(photos);
                        // after dedup across albums
                        screen.listing(album, n, found.size());
                    });
                }
            });
        }
        screen.listingFinished(found.size());
    }

    /// Runs a listing progress update, which must never throw into gallery-dl's job.
    private static void report(Runnable update) {
        try {
            update.run();
        } catch (RuntimeException e) {
            System.err.println("listing progress failed: " + e);
        }
    }

    /// The CLI's scan, minus seen.txt and matches.txt, fed album by album as `listed` delivers them: every result goes
    /// on the queue, and the pacer shows it. `check` fetches and scores one photo.
    static void scan(
            Function<FindMe.Photo, Pacer.Result> check,
            float[] me,
            BlockingQueue<List<FindMe.Photo>> listed,
            FutureTask<@Nullable Void> listing,
            List<BufferedImage> crops,
            Screen screen,
            long deadline
    ) throws InterruptedException, ExecutionException {
        // unbounded: the pipeline never waits for the display
        var queue = new LinkedBlockingQueue<Pacer.Result>();
        Pacer pacer = new Pacer(queue, crops, me, screen::scanning, Thread::sleep, deadline);
        var pacing = new FutureTask<>(pacer::run);
        Thread.ofPlatform().name("pacer").start(pacing);
        try {
            Semaphore inFlight = new Semaphore(FindMe.IN_FLIGHT);
            AtomicLong firstStart = new AtomicLong();
            int total = 0;
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var pending = new ArrayDeque<FindMe.Photo>();
                boolean ended = false;
                while (!ended || !pending.isEmpty()) {
                    // count every album as soon as it's listed, not when the in-flight cap lets it in: the pace needs
                    // the real total
                    for (var album = pending.isEmpty() ? listed.take() : listed.poll(); album != null && !ended; album = listed.poll()) {
                        if (album == END) {
                            ended = true;
                            // the lister's failure, if any, is the director's
                            listing.get();
                            pacer.listingFinished();
                        } else {
                            // before any of their results can reach the queue
                            pacer.add(album.size());
                            total += album.size();
                            pending.addAll(album);
                        }
                    }
                    FindMe.Photo p = pending.poll();
                    if (p == null) {
                        continue;
                    }
                    inFlight.acquire();
                    pool.execute(() -> {
                        firstStart.compareAndSet(0, System.nanoTime());
                        // even if check throws, the pacer gets its total
                        Pacer.Result r = new Pacer.Failed();
                        try {
                            r = check.apply(p);
                        } finally {
                            inFlight.release();
                            queue.add(r);
                        }
                    });
                }
            }
            long realScanMs = total == 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - firstStart.get());
            pacer.pipelineFinished();
            screen.end(new Done(crops, total, pacing.get(), realScanMs));
        } finally {
            // a no-op once shown; after a failure, the pacer stops polling
            pacing.cancel(true);
        }
    }

    private static Pacer.Result check(Faces faces, Semaphore cpus, float[] me, FindMe.Photo p) {
        byte[] bytes = p.imageUrl().startsWith("file:") ? FindMe.read(p.imageUrl()) : FindMe.download(p.imageUrl());
        if (bytes == null) {
            return new Pacer.Failed();
        }
        try {
            cpus.acquire();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return new Pacer.Failed();
        }
        try (var _ = new PointerScope()) {
            List<Faces.Face> found = faces.faces(Faces.decode(bytes));
            // as the CLI: 0 with no faces
            float best = 0;
            for (Faces.Face f : found) {
                best = Math.max(best, Faces.similarity(f.embedding(), me));
            }
            return new Pacer.Scored(bytes, found, best);
        } catch (IOException e) {
            System.err.println("decode failed: " + e.getMessage());
            return new Pacer.Failed();
        } finally {
            cpus.release();
        }
    }
}

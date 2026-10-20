package findme;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.bytedeco.javacpp.PointerScope;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.graalvm.python.embedding.GraalPyResources;

/// Find your face in public Flickr albums and local folders. Everything but the downloads runs locally, on the GPU when CUDA is available.
///
/// Usage: `java -jar target/find-me.jar <reference-photos-dir> <album-url-or-dir> [<album-url-or-dir> ...]`
@SuppressWarnings({"java:S106", "java:S1168"}) // a CLI: stdout/stderr are its UI; null bytes mean "failed, don't mark seen"
public final class FindMe {

    private static final float THRESHOLD = 0.40f; // cosine similarity; raise if too many false matches, lower if it misses you
    private static final Path MODELS = Path.of(System.getenv().getOrDefault("FIND_ME_MODELS",
            System.getProperty("user.home") + "/.insightface/models/buffalo_l"));
    private static final String MODELS_URL =
            "https://github.com/deepinsight/insightface/releases/download/model-zoo/buffalo_l.zip"; // where insightface gets it
    private static final List<String> MODEL_FILES = List.of("det_10g.onnx", "w600k_r50.onnx");
    private static final Path SEEN = Path.of("seen.txt");
    private static final Path MATCHES = Path.of("matches.txt");
    private static final int CPUS = Runtime.getRuntime().availableProcessors();
    private static final int IN_FLIGHT = 64; // photos downloaded but not yet scanned: bounds memory when downloads outrun the GPU
    private static final int ATTEMPTS = 4;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private record Photo(String id, String imageUrl, String page) {}

    // gallery-dl's DataJob is what `gallery-dl -j` runs; type 3 entries are files: [3, image_url, metadata]
    private static final String LIST_ALBUM = """
            from gallery_dl import config, job
            config.set((), "size-max", 1024)
            def photos(url):
                j = job.DataJob(url, file=None)
                j.run()
                if j.exception:
                    raise j.exception
                return [(e[1], str(e[2]["id"]), e[2]["owner"]["nsid"]) for e in j.data if e[0] == 3]
            photos
            """;

    public static void main(String[] args) throws Exception {
        // Python JIT off: album listing is a short network-bound burst, where compiling costs more than it saves
        // (27 s with the JIT vs 21 s without on a 215-photo album). Must be set before Truffle loads.
        System.setProperty("truffle.TruffleRuntime", "com.oracle.truffle.api.impl.DefaultTruffleRuntime");
        if (args.length < 2) {
            System.err.println("usage: java -jar find-me.jar <reference-photos-dir> <album-url-or-dir> [<album-url-or-dir> ...]");
            System.exit(1);
        }
        ensureModels();
        try (Faces faces = new Faces(MODELS)) {
            float[] me = reference(faces, Path.of(args[0]));
            Set<String> seen = Files.exists(SEEN) ? new HashSet<>(Files.readAllLines(SEEN)) : new HashSet<>();
            List<String> albums = new ArrayList<>();
            Map<String, Photo> found = new LinkedHashMap<>();
            for (String a : List.of(args).subList(1, args.length)) {
                if (Files.isDirectory(Path.of(a))) {
                    listDir(Path.of(a), seen, found);
                } else {
                    albums.add(a);
                }
            }
            if (!albums.isEmpty()) { // skips starting Python when there's nothing to list
                listAlbums(albums, seen, found);
            }
            List<Photo> todo = new ArrayList<>(found.values());
            System.err.println(todo.size() + " photos to check");
            scan(faces, me, todo);
        }
    }

    /// Fetches insightface's buffalo_l on first use, as insightface does, keeping only the two models this app runs.
    private static void ensureModels() throws IOException, InterruptedException {
        if (MODEL_FILES.stream().allMatch(f -> Files.exists(MODELS.resolve(f)))) {
            return;
        }
        Files.createDirectories(MODELS);
        Path zip = Files.createTempFile(MODELS, "buffalo_l", ".zip");
        try {
            System.err.println("downloading face models (~290 MB) to " + MODELS);
            HttpResponse<Path> r = HTTP.send(HttpRequest.newBuilder(URI.create(MODELS_URL)).build(),
                    HttpResponse.BodyHandlers.ofFile(zip));
            if (r.statusCode() != 200) {
                throw new IOException("model download failed: HTTP " + r.statusCode() + " from " + MODELS_URL);
            }
            try (FileSystem fs = FileSystems.newFileSystem(zip)) {
                for (String f : MODEL_FILES) { // via .part so an interrupted run never leaves a truncated model behind
                    Path part = MODELS.resolve(f + ".part");
                    Files.copy(fs.getPath(f), part, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(part, MODELS.resolve(f), StandardCopyOption.ATOMIC_MOVE);
                }
            }
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    private static float[] reference(Faces faces, Path dir) throws IOException {
        List<Path> files;
        try (var s = Files.list(dir)) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        }
        float[] sum = null;
        int used = 0;
        for (Path p : files) {
            float[] e = onlyFace(faces, p);
            if (e == null) {
                continue;
            }
            if (sum == null) {
                sum = new float[e.length];
            }
            for (int i = 0; i < e.length; i++) {
                sum[i] += e[i];
            }
            used++;
        }
        if (used == 0) {
            System.err.println("no usable reference photos");
            System.exit(1);
        }
        System.err.println("learned face from " + used + " photos");
        double norm = 0;
        for (float v : sum) {
            norm += v * v;
        }
        for (int i = 0; i < sum.length; i++) {
            sum[i] /= (float) Math.sqrt(norm);
        }
        return sum;
    }

    /// The embedding of the one face in a reference photo; null, with the reason printed, if it has none or several.
    private static float[] onlyFace(Faces faces, Path p) {
        List<float[]> found;
        try (var _ = new PointerScope()) {
            found = faces.embeddings(Faces.decode(Files.readAllBytes(p)));
        } catch (IOException e) {
            System.err.println("skip " + p.getFileName() + ": " + e.getMessage());
            return null;
        }
        if (found.size() != 1) {
            System.err.println("skip " + p.getFileName() + ": " + found.size() + " faces");
            return null;
        }
        return found.getFirst();
    }

    /// Lists the albums one after another in a single Python context; photos already in seen.txt or repeated across
    /// albums are dropped. Not parallel: only one GraalPy context per process may load native modules (gallery-dl needs
    /// termios), and gallery-dl isn't thread-safe within one interpreter (shared extractor generator and SQLite cache).
    private static void listAlbums(List<String> albums, Set<String> seen, Map<String, Photo> todo)
            throws IOException, InterruptedException {
        try (Context py = GraalPyResources.contextBuilder().option("engine.WarnInterpreterOnly", "false").build()) {
            Value list = py.eval("python", LIST_ALBUM);
            for (int n = 0; n < albums.size(); n++) {
                String url = albums.get(n);
                // gallery-dl doesn't support flic.kr short links; use where they redirect
                String resolved = HTTP.send(HttpRequest.newBuilder(URI.create(url)).build(),
                        HttpResponse.BodyHandlers.discarding()).uri().toString();
                Value found = list.execute(resolved);
                System.err.println("listed album " + (n + 1) + "/" + albums.size() + ": " + url
                        + " (" + found.getArraySize() + " photos)");
                for (long i = 0; i < found.getArraySize(); i++) {
                    Value t = found.getArrayElement(i);
                    String id = t.getArrayElement(1).asString();
                    if (!seen.contains(id)) {
                        todo.putIfAbsent(id, new Photo(id, t.getArrayElement(0).asString(),
                                "https://www.flickr.com/photos/" + t.getArrayElement(2).asString() + "/" + id));
                    }
                }
            }
        }
    }

    /// Image files under `dir`, recursively; the absolute path is both the seen.txt ID and what matches.txt lists.
    private static void listDir(Path dir, Set<String> seen, Map<String, Photo> todo) throws IOException {
        int before = todo.size();
        try (var s = Files.walk(dir)) {
            s.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().toLowerCase(Locale.ROOT)
                            .matches(".*\\.(jpe?g|png|webp|bmp|tiff?)"))
                    .map(f -> f.toAbsolutePath().normalize())
                    .sorted()
                    .filter(f -> !seen.contains(f.toString()))
                    .forEach(f -> todo.putIfAbsent(f.toString(), new Photo(f.toString(), f.toUri().toString(), f.toString())));
        }
        System.err.println("listed folder " + dir + " (" + (todo.size() - before) + " new photos)");
    }

    private static void scan(Faces faces, float[] me, List<Photo> todo) throws IOException, InterruptedException {
        Semaphore inFlight = new Semaphore(IN_FLIGHT);
        Semaphore cpus = new Semaphore(CPUS); // caps concurrent decode+inference at the CPU count
        AtomicInteger checked = new AtomicInteger();
        try (Writer seenOut = Files.newBufferedWriter(SEEN, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                Writer matchOut = Files.newBufferedWriter(MATCHES, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Photo p : todo) {
                inFlight.acquire();
                pool.execute(() -> { // execute, not submit: a failure prints instead of vanishing into an unread Future
                    try {
                        if (check(faces, cpus, me, p, seenOut, matchOut)) {
                            int n = checked.incrementAndGet();
                            if (n % 10 == 0) {
                                System.err.println(n + "/" + todo.size());
                            }
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    } finally {
                        inFlight.release();
                    }
                });
            }
        }
    }

    /// Fetches and scores one photo, recording it in seen.txt (and matches.txt on a match); false if it couldn't be
    /// fetched or decoded, so it isn't marked seen and a re-run retries it.
    private static boolean check(Faces faces, Semaphore cpus, float[] me, Photo p, Writer seenOut, Writer matchOut)
            throws IOException {
        byte[] bytes = p.imageUrl().startsWith("file:") ? read(p.imageUrl()) : download(p.imageUrl());
        if (bytes == null) {
            return false;
        }
        float score = score(faces, cpus, bytes, me);
        if (Float.isNaN(score)) {
            return false;
        }
        synchronized (FindMe.class) {
            if (score >= THRESHOLD) {
                String line = String.format(Locale.ROOT, "MATCH %.2f %s", score, p.page());
                System.out.println(line);
                matchOut.write(line + "\n");
                matchOut.flush();
            }
            seenOut.write(p.id() + "\n");
            seenOut.flush();
        }
        return true;
    }

    /// Best cosine similarity of any face in the photo, 0 if none, NaN if it can't be decoded.
    private static float score(Faces faces, Semaphore cpus, byte[] bytes, float[] me) {
        try {
            cpus.acquire();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return Float.NaN;
        }
        try (var _ = new PointerScope()) {
            float best = 0;
            for (float[] e : faces.embeddings(Faces.decode(bytes))) {
                float dot = 0;
                for (int i = 0; i < e.length; i++) {
                    dot += e[i] * me[i];
                }
                best = Math.max(best, dot);
            }
            return best;
        } catch (IOException e) {
            System.err.println("decode failed: " + e.getMessage());
            return Float.NaN;
        } finally {
            cpus.release();
        }
    }

    /// Reads a local photo; null if unreadable, so like a failed download it isn't marked seen.
    private static byte[] read(String uri) {
        try {
            return Files.readAllBytes(Path.of(URI.create(uri)));
        } catch (IOException e) {
            System.err.println("read failed " + uri + ": " + e.getMessage());
            return null;
        }
    }

    /// Downloads with retries on network errors, 429 and 5xx; returns null after the last attempt fails.
    private static byte[] download(String url) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).build();
        for (int attempt = 1; ; attempt++) {
            String error;
            boolean retry = true;
            try {
                HttpResponse<byte[]> r = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() == 200) {
                    return r.body();
                }
                error = "HTTP " + r.statusCode();
                retry = r.statusCode() == 429 || r.statusCode() >= 500; // client error: retrying won't help
            } catch (IOException e) {
                error = e.toString();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (!retry || attempt >= ATTEMPTS) {
                System.err.println("download failed " + url + ": " + error);
                return null;
            }
            try {
                Thread.sleep(1000L << attempt); // 2s, 4s, 8s
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }
}

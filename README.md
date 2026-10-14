# find-me

Finds photos of you in public Flickr albums and local folders. Give it a folder of photos of yourself and one or more album links or folders; it learns your face, goes through every photo in the albums, and lists the ones you appear in. Face recognition runs locally, on the GPU when one is available; only the photos themselves are downloaded.

```
java -jar target/find-me.jar ~/Pictures/me https://flic.kr/s/aHBqjD7cDj https://www.flickr.com/photos/<user>/albums/<id> ~/Pictures/conference
```

An argument that is an existing directory is scanned recursively for `.jpg`, `.jpeg`, `.png`, `.webp`, `.bmp` and `.tif(f)` files; anything else is treated as an album link.

Output:

- `matches.txt`: one line per photo you're in, `MATCH <similarity> <flickr page URL or absolute file path>`, also printed to stdout as they're found.
- `seen.txt`: IDs (Flickr photo IDs, or absolute paths for local files) of photos already checked. Re-running skips them, so an interrupted run resumes where it stopped and a re-run only checks photos added since. Photos whose download failed aren't marked seen and get retried.

Both are written to the current directory.

## How it works

1. **Learn your face.** Every photo in the reference folder must contain exactly one face (others are skipped with a message). Their face embeddings are averaged into one.
2. **List the albums.** [gallery-dl](https://github.com/mikf/gallery-dl) runs inside the JVM on [GraalPy](https://www.graalvm.org/python/) and lists every photo with its 1024 px image URL, one album after another (GraalPy allows native modules in only one context per process, and gallery-dl isn't thread-safe within one interpreter).
3. **Scan.** Photos are downloaded (up to 64 in flight, retries with backoff on network errors, 429 and 5xx), every face in each photo is embedded, and a photo matches when any face has cosine similarity ≥ 0.40 to yours. Raise `THRESHOLD` in `FindMe.java` if you get false matches, lower it if it misses you.

Under the hood:

- **Face models:** insightface `buffalo_l`: SCRFD detector (`det_10g.onnx`) and ArcFace recognizer (`w600k_r50.onnx`). Detection decoding, NMS and face alignment follow insightface's reference code; on 89 single-face and 13 group/stage photos (20 faces) the embeddings match insightface's own to cosine similarity ≥ 0.9998. Faces are embedded one per run: `w600k_r50` gives wrong embeddings when batched.
- **Inference:** ONNX Runtime via [onnxruntime-java](https://github.com/yuzawa-san/onnxruntime-java) (FFM/Panama). CUDA on Linux, CoreML on macOS, CPU fallback with a warning. GPU runs are serialized: concurrent CUDA runs exhausted a 6 GB GPU.
- **Image ops:** OpenCV 4.13 via [bytedeco/JavaCPP](https://github.com/bytedeco/javacpp-presets/tree/master/opencv): decoding, resize, warpAffine, blobFromImage.

## Requirements

- **JDK:** Liberica NIK 25.0.4 (pinned in `.sdkmanrc`; `sdk env install` or mise). Any JDK 25+ should work, since GraalPy runs interpreted anyway (see Performance), but only NIK 25.0.4 is tested.
- **Face models:** nothing to do. On first run it downloads insightface's `buffalo_l.zip` (~290 MB) from insightface's GitHub releases and keeps the two models it uses (~190 MB) in `~/.insightface/models/buffalo_l/`, or in `FIND_ME_MODELS` if set. No checksum check: insightface publishes none.
- **Linux GPU (optional):** CUDA 13 and cuDNN 9 libraries, which aren't available from Maven Central. Install them system-wide (Arch: `cuda` + `cudnn`, ~6 GB), or put existing copies on `LD_LIBRARY_PATH` (e.g. the `nvidia/cu13/lib` and `nvidia/cudnn/lib` directories of a Python environment with `nvidia-cublas`/`nvidia-cudnn` installed). Without them it runs on the CPU.

## Platforms

Maven picks native libraries for the machine it builds on (profiles in `pom.xml`):

| Platform | Inference |
|---|---|
| Linux x86_64 | CUDA, CPU fallback |
| macOS Apple Silicon | CoreML, CPU fallback |

Intel Macs aren't supported (no ONNX Runtime build). Other platforms fail the build with an unresolved `${javacpp.platform}`.

## Build

```
mvn clean package
```

Use `clean` after changing dependency versions, or stale jars stay in `target/lib`.

### Native image

```
mvn -Pnative package
target/find-me ~/Pictures/me https://flic.kr/s/aHBqjD7cDj
```

Builds `target/find-me`, a standalone executable (~570 MB, mostly the native libraries it embeds; ONNX Runtime's CUDA provider alone is 259 MB), in about 2.5 minutes (on macOS: ~340 MB, under 2 minutes). Run it with the same `LD_LIBRARY_PATH` as the jar if the CUDA libraries aren't installed system-wide (see Requirements); without them it falls back to the CPU, as on the JVM.

Reflection, resource and FFM metadata is per platform, because it names that platform's native libraries: `src/main/native-image/<platform>/reachability-metadata.json`, where `<platform>` is `javacpp.platform` from the OS profile (`linux-x86_64`, `macosx-arm64`).

#### Recording metadata (on a new platform, or after code or dependency changes)

The tracing agent records what a real run touches, so run the jar on the platform itself and make it go through every code path:

```
mvn clean package
P=$PWD M=$PWD/src/main/native-image/linux-x86_64   # macosx-arm64 on a Mac
mkdir -p "$M" && cd "$(mktemp -d)"                 # empty dir: no seen.txt, so every photo gets scanned
java -agentlib:native-image-agent=config-merge-dir="$M" -jar "$P/target/find-me.jar" ~/Pictures/me https://flic.kr/s/aHBqjD7cDj
```

- **Linux:** record twice, once with the CUDA libraries on `LD_LIBRARY_PATH` and once without. ONNX Runtime's CUDA setup and the CPU fallback take different paths, and an image recorded without CUDA dies at startup on a machine that has it (`NoSuchMethodException: ...SessionOptionsAppendExecutionProvider_CUDA_V2$Function.apply`). `config-merge-dir` merges the runs.
- **macOS:** nothing to export, no `DYLD_LIBRARY_PATH` (the Mac counterpart of `LD_LIBRARY_PATH`). CoreML is part of the OS, and ONNX Runtime, OpenCV and OpenBLAS unpack their own `.dylib`s from the jars. A run where CoreML loads doesn't record the CPU fallback; untested whether that fallback then works in the native image.

Then move the `directUpcalls` the agent writes to `upcalls` (see Gotchas), and build and test the native image:

```
jq '.foreign.upcalls = ((.foreign.upcalls // []) + [(.foreign.directUpcalls // [])[] | del(.class, .method)] | unique) | del(.foreign.directUpcalls)' \
  "$M/reachability-metadata.json" > "$M/tmp.json" && mv -f "$M/tmp.json" "$M/reachability-metadata.json"
cd "$P" && mvn -Pnative package
```

The first build downloads ~500 MB into `~/.m2`: ONNX Runtime (~305 MB with the CUDA provider on Linux), GraalPy and Truffle (~150 MB), OpenCV/OpenBLAS for this OS (~53 MB on Linux); and pip-installs gallery-dl and its dependencies from PyPI into the jar.

## Gotchas

- `onnxruntime-gpu` 1.30.0's pom depends on itself with `-linux-x86_64-gpu`/`-windows-x86_64-gpu` classifiers that were never published; `pom.xml` excludes them. `onnxruntime-cpu` lists itself once per OS and is excluded the same way, so only one OS gets pulled.
- GraalPy 25.0.4 is Python 3.12, and its `ssl` module lacks `VERIFY_X509_PARTIAL_CHAIN`. urllib3 2.4+ imports it and, when that fails, can't make any HTTPS connection ("Can't create an SSLContext object without an ssl module"). `pom.xml` pins `urllib3==2.3.0`.
- urllib3 warns at startup that `ssl` is "GraalVM JSSE", not OpenSSL. Harmless.
- In native image, `directUpcalls` ignore an `AddressLayout`'s target layout, so upcall pointers arrive zero-length ([reported upstream](https://github.com/oracle/graal/issues/14676)). onnxruntime-java's log callback then dies reading the message, killing the process the first time ONNX Runtime logs. Registered under plain `upcalls` it works.
- Native image needs `-H:+SharedArenaSupport`: onnxruntime-java frees tensors with `Arena.ofShared`.
- Should GraalPy's JIT ever be wanted, `graalpy.version` must equal the JDK's GraalVM version (`GRAALVM_VERSION` in the JDK's `release` file); GraalPy 25.4.4.1.1 refuses NIK 25.0.3 and 25.0.4.

## Performance

GraalPy runs interpreted on purpose (`truffle.TruffleRuntime` is set at the start of `main`). Listing a 215-photo album, single runs over the network, so noisy:

| Python runtime | Time |
|---|---|
| GraalPy 25.0.4, JIT on | 27.4 s, 27.7 s |
| GraalPy 25.0.4, interpreted | 21.4 s |
| CPython 3.14 gallery-dl, for reference | 17.5 s, 8.7 s |

Listing is a short, network-bound burst that ends before JIT-compiled code gets reused, so compiling only costs time. It happens once per run. On CUDA it takes about twice as long as scanning a 215-photo album (see below); scanning grows with album size, listing much less.

### JVM vs native image

Liberica NIK 25.0.4, CUDA, 5 reference photos, the same 215-photo album from an empty `seen.txt`; two runs each, over the network. Both find the same match at the same similarity.

| Phase | JVM | Native image |
|---|---|---|
| Learn face (startup, model load, 5 photos) | 4.1 s, 3.8 s | 3.3 s, 3.1 s |
| List album | 18.7 s, 17.7 s | 10.3 s, 10.1 s |
| Scan 215 photos | 8.2 s, 7.8 s | 7.7 s, 7.9 s |
| **Total** | **31.0 s, 29.3 s** | **21.3 s, 21.1 s** |
| Peak RSS | 2.6 GB, 2.8 GB | 2.8 GB, 2.7 GB |

The native image wins only on album listing (GraalPy); scanning is GPU- and network-bound and takes the same time.

## Verified

- Linux, CUDA, Liberica NIK 25.0.4: a full run on a 215-photo album takes 56 s and finds the same match, with the same similarity (0.77), as insightface's Python pipeline.
- macOS 26 on Apple Silicon (M2), CoreML, Liberica NIK 25.0.4 (`25.0.4.r25-nik`; sdkman there has no `fx` build): jar and native image both find the same match at 0.77. CoreML takes 133 of the detector's 153 nodes. The native image runs the 215-photo album in 48 s. The first JVM run started with no `~/.insightface` and downloaded the models itself.

## Not verified yet

- macOS CPU fallback in the native image (the metadata was recorded with CoreML loading), and Mac JVM vs native timings without the tracing agent.
- OpenCV's and ONNX Runtime's own thread pools vs the per-photo parallelism (possible CPU oversubscription).

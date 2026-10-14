# find-me

Finds photos of you in public Flickr albums. Give it a folder of photos of yourself and one or more album links; it learns your face, goes through every photo in the albums, and lists the ones you appear in. Face recognition runs locally, on the GPU when one is available; only the photos themselves are downloaded.

```
java -jar target/find-me.jar ~/Pictures/me https://flic.kr/s/aHBqjD7cDj https://www.flickr.com/photos/<user>/albums/<id>
```

Output:

- `matches.txt`: one line per photo you're in, `MATCH <similarity> <flickr page URL>`, also printed to stdout as they're found.
- `seen.txt`: IDs of photos already checked. Re-running skips them, so an interrupted run resumes where it stopped and a re-run only checks photos added since. Photos whose download failed aren't marked seen and get retried.

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

Builds `target/find-me`, a standalone executable (~1.5 GB: it embeds the native libraries and the Python environment), in about 2.5 minutes. Reflection, resource and FFM metadata is in `src/main/resources/META-INF/native-image/findme/find-me/reachability-metadata.json`, recorded with the tracing agent on a real run:

```
java -agentlib:native-image-agent=config-merge-dir=src/main/resources/META-INF/native-image/findme/find-me -jar target/find-me.jar <ref-dir> <album-url>
```

After re-recording, move the `directUpcalls` entry the agent writes for ONNX Runtime's log callback to `upcalls` (see Gotchas).

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

Listing is a short, network-bound burst that ends before JIT-compiled code gets reused, so compiling only costs time. It happens once per run; scanning the photos takes much longer.

## Verified

- Linux, CUDA, Liberica NIK 25.0.4: a full run on a 215-photo album takes 56 s and finds the same match, with the same similarity (0.77), as insightface's Python pipeline.

## Not verified yet

- Anything on macOS: CoreML being present in the macOS ONNX Runtime build, GraalPy's and JavaCPP's Mac natives, inference speed. The mac profile selection was only checked by faking `os.name`/`os.arch`.
- First-run model download (never run against an empty model directory).
- OpenCV's and ONNX Runtime's own thread pools vs the per-photo parallelism (possible CPU oversubscription).

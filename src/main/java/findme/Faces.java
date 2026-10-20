package findme;

import com.jyuzawa.onnxruntime.Environment;
import com.jyuzawa.onnxruntime.ExecutionProvider;
import com.jyuzawa.onnxruntime.NamedCollection;
import com.jyuzawa.onnxruntime.OnnxRuntime;
import com.jyuzawa.onnxruntime.OnnxRuntimeLoggingLevel;
import com.jyuzawa.onnxruntime.OnnxValue;
import com.jyuzawa.onnxruntime.Session;
import com.jyuzawa.onnxruntime.Transaction;
import static org.bytedeco.opencv.global.opencv_core.CV_32F;
import static org.bytedeco.opencv.global.opencv_core.CV_64F;
import static org.bytedeco.opencv.global.opencv_core.CV_8UC1;
import static org.bytedeco.opencv.global.opencv_core.CV_8UC3;
import static org.bytedeco.opencv.global.opencv_dnn.NMSBoxes;
import static org.bytedeco.opencv.global.opencv_dnn.blobFromImage;
import static org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_COLOR;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imdecode;
import static org.bytedeco.opencv.global.opencv_imgproc.resize;
import static org.bytedeco.opencv.global.opencv_imgproc.warpAffine;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerScope;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Rect2d;
import org.bytedeco.opencv.opencv_core.Rect2dVector;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;

/// insightface buffalo_l on ONNX Runtime + OpenCV: SCRFD detection (det_10g) + ArcFace embedding (w600k_r50).
/// Pre/post-processing mirrors insightface's Python code, with the same cv2 calls, so embeddings and the similarity
/// threshold stay comparable.
///
/// References:
/// - SCRFD detector: Guo et al., "Sample and Computation Redistribution for Efficient Face Detection",
///   [arXiv:2105.04714](https://arxiv.org/abs/2105.04714); Python reference:
///   [model_zoo/scrfd.py](https://github.com/deepinsight/insightface/blob/master/python-package/insightface/model_zoo/scrfd.py)
/// - ArcFace recognizer: Deng et al., "ArcFace: Additive Angular Margin Loss for Deep Face Recognition",
///   [arXiv:1801.07698](https://arxiv.org/abs/1801.07698); Python reference:
///   [model_zoo/arcface_onnx.py](https://github.com/deepinsight/insightface/blob/master/python-package/insightface/model_zoo/arcface_onnx.py)
/// - Alignment: [utils/face_align.py](https://github.com/deepinsight/insightface/blob/master/python-package/insightface/utils/face_align.py)
///
/// Thread-safe: ONNX Runtime sessions allow concurrent runs.
@SuppressWarnings("java:S106") // a CLI: stderr is its UI
final class Faces implements AutoCloseable {

    private static final int DET_SIZE = 640;
    private static final int REC_SIZE = 112;
    private static final float DET_THRESH = 0.5f;
    private static final float NMS_THRESH = 0.4f;
    private static final int[] STRIDES = {8, 16, 32};
    private static final int ANCHORS = 2;
    // Where the 5 landmarks must land in the 112x112 crop ArcFace was trained on, as x,y pairs: left eye, right eye,
    // nose tip, left mouth corner, right mouth corner. Copied from arcface_dst in https://github.com/deepinsight/insightface/blob/master/python-package/insightface/utils/face_align.py
    private static final float[] DST = {
        38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f
    };

    @SuppressWarnings("java:S6218") // never compared or printed
    private record Detection(float score, float x1, float y1, float x2, float y2, float[] kps) {}

    private final Environment env;
    private final Session det;
    private final Session rec;
    // the GPU runtime for this OS: CoreML (Apple GPU / Neural Engine) on macOS, CUDA elsewhere
    private static final ExecutionProvider GPU = System.getProperty("os.name").startsWith("Mac")
            ? ExecutionProvider.COREML_EXECUTION_PROVIDER : ExecutionProvider.CUDA_EXECUTION_PROVIDER;
    private boolean onGpu;

    Faces(Path modelDir) throws IOException {
        env = OnnxRuntime.get().getApi().newEnvironment().setLogSeverityLevel(OnnxRuntimeLoggingLevel.WARNING).build();
        det = open(modelDir.resolve("det_10g.onnx"));
        rec = open(modelDir.resolve("w600k_r50.onnx"));
        if (det.getOutputs().size() != 9) {
            throw new IllegalStateException("expected SCRFD with 9 outputs, got " + det.getOutputs().size());
        }
    }

    private Session open(Path model) throws IOException {
        try {
            Session s = env.newSession().setPath(model).addProvider(GPU).build();
            onGpu = true;
            return s;
        } catch (IOException | RuntimeException e) {
            System.err.println(GPU + " unavailable for " + model.getFileName() + ", falling back to CPU: " + e.getMessage());
            return env.newSession().setPath(model).build();
        }
    }

    /// Decodes like cv2.imread: BGR, EXIF orientation applied. The Mat belongs to the caller's PointerScope.
    static Mat decode(byte[] bytes) throws IOException {
        Mat img = imdecode(new Mat(1, bytes.length, CV_8UC1, new BytePointer(bytes)), IMREAD_COLOR);
        if (img.empty()) {
            throw new IOException("unsupported or corrupt image");
        }
        return img;
    }

    /// L2-normalized 512-d embeddings, one per detected face. `img` is BGR, as from [#decode].
    List<float[]> embeddings(Mat img) {
        try (var _ = new PointerScope()) {
            List<Detection> faces = detect(img);
            return faces.isEmpty() ? List.of() : embed(img, faces);
        }
    }

    private List<Detection> detect(Mat img) {
        int w = img.cols();
        int h = img.rows();
        double ratio = (double) h / w;
        int nw;
        int nh;
        if (ratio > 1.0) {
            nh = DET_SIZE;
            nw = (int) (DET_SIZE / ratio);
        } else {
            nw = DET_SIZE;
            nh = (int) (DET_SIZE * ratio);
        }
        float scale = (float) nh / h;
        // resize into the top-left of a black 640x640, then blobFromImage(1/128, mean 127.5, swapRB):
        // SCRFD.detect + SCRFD.forward in https://github.com/deepinsight/insightface/blob/master/python-package/insightface/model_zoo/scrfd.py
        Mat padded = new Mat(DET_SIZE, DET_SIZE, CV_8UC3, Scalar.all(0));
        resize(img, padded.apply(new Rect(0, 0, nw, nh)), new Size(nw, nh));
        Mat blob = blobFromImage(padded, 1 / 128.0, new Size(DET_SIZE, DET_SIZE), Scalar.all(127.5), true, false, CV_32F);

        List<Detection> found = new ArrayList<>();
        try (Transaction txn = det.newTransaction().build()) {
            txn.addInput(0).asTensor(List.of(1L, 3L, (long) DET_SIZE, (long) DET_SIZE)).getFloatBuffer()
                    .put(blob.<FloatBuffer>createBuffer());
            for (int i = 0; i < 9; i++) {
                txn.addOutput(i);
            }
            NamedCollection<OnnxValue> out = run(txn);
            for (int s = 0; s < STRIDES.length; s++) {
                decode(out, s, scale, found);
            }
        }
        return nms(found);
    }

    /// One GPU run at a time: 16 concurrent CUDA runs exhaust a 6 GB GPU (cudnnCreate fails with CUDNN_STATUS_INTERNAL_ERROR).
    private NamedCollection<OnnxValue> run(Transaction txn) {
        if (!onGpu) {
            return txn.run();
        }
        synchronized (this) {
            return txn.run();
        }
    }

    /// SCRFD outputs: `0..2` scores, `3..5` box distances, `6..8` landmark offsets, per stride 8/16/32.
    /// Port of SCRFD.forward, distance2bbox and distance2kps in [scrfd.py](https://github.com/deepinsight/insightface/blob/master/python-package/insightface/model_zoo/scrfd.py).
    private static void decode(NamedCollection<OnnxValue> out, int s, float scale, List<Detection> found) {
        int stride = STRIDES[s];
        int cells = DET_SIZE / stride;
        FloatBuffer scores = out.get(s).asTensor().getFloatBuffer();
        FloatBuffer boxes = out.get(s + 3).asTensor().getFloatBuffer();
        FloatBuffer kps = out.get(s + 6).asTensor().getFloatBuffer();
        int n = cells * cells * ANCHORS;
        for (int a = 0; a < n; a++) {
            float score = scores.get(a);
            if (score < DET_THRESH) {
                continue;
            }
            int cell = a / ANCHORS;
            int row = cell / cells;
            float cx = cell % cells * (float) stride;
            float cy = row * (float) stride;
            float[] k = new float[10];
            for (int i = 0; i < 5; i++) {
                k[2 * i] = (cx + kps.get(a * 10 + 2 * i) * stride) / scale;
                k[2 * i + 1] = (cy + kps.get(a * 10 + 2 * i + 1) * stride) / scale;
            }
            found.add(new Detection(score,
                    (cx - boxes.get(a * 4) * stride) / scale,
                    (cy - boxes.get(a * 4 + 1) * stride) / scale,
                    (cx + boxes.get(a * 4 + 2) * stride) / scale,
                    (cy + boxes.get(a * 4 + 3) * stride) / scale,
                    k));
        }
    }

    /// OpenCV's greedy NMS. insightface's own (SCRFD.nms in [scrfd.py](https://github.com/deepinsight/insightface/blob/master/python-package/insightface/model_zoo/scrfd.py)) counts box
    /// edges as +1 pixel, which only matters for tiny boxes.
    private static List<Detection> nms(List<Detection> dets) {
        Rect2dVector boxes = new Rect2dVector();
        FloatPointer scores = new FloatPointer(dets.size());
        for (int i = 0; i < dets.size(); i++) {
            Detection d = dets.get(i);
            boxes.push_back(new Rect2d(d.x1, d.y1, d.x2 - d.x1, d.y2 - d.y1));
            scores.put(i, d.score);
        }
        IntPointer keep = new IntPointer();
        NMSBoxes(boxes, scores, DET_THRESH, NMS_THRESH, keep);
        List<Detection> kept = new ArrayList<>();
        for (long i = 0; i < keep.limit(); i++) {
            kept.add(dets.get(keep.get(i)));
        }
        return kept;
    }

    /// insightface norm_crop + ArcFace, one face per run like insightface: w600k_r50 only works with batch size 1
    /// (batched runs return wrong embeddings for every face, with an ONNX Runtime "Expected shape `{1,512}`" warning).
    /// Port of ArcFaceONNX.get + get_feat in [arcface_onnx.py](https://github.com/deepinsight/insightface/blob/master/python-package/insightface/model_zoo/arcface_onnx.py) and norm_crop in
    /// [face_align.py](https://github.com/deepinsight/insightface/blob/master/python-package/insightface/utils/face_align.py).
    private List<float[]> embed(Mat img, List<Detection> faces) {
        List<float[]> result = new ArrayList<>(faces.size());
        for (Detection face : faces) {
            Mat crop = new Mat();
            warpAffine(img, crop, align(face.kps), new Size(REC_SIZE, REC_SIZE)); // bilinear, black border
            Mat blob = blobFromImage(crop, 1 / 127.5, new Size(REC_SIZE, REC_SIZE), Scalar.all(127.5), true, false, CV_32F);
            try (Transaction txn = rec.newTransaction().build()) {
                txn.addInput(0).asTensor(List.of(1L, 3L, (long) REC_SIZE, (long) REC_SIZE)).getFloatBuffer()
                        .put(blob.<FloatBuffer>createBuffer());
                txn.addOutput(0);
                FloatBuffer out = run(txn).get(0).asTensor().getFloatBuffer();
                float[] e = new float[out.capacity()];
                out.get(0, e);
                double norm = 0;
                for (float v : e) {
                    norm += v * v;
                }
                float inv = (float) (1 / Math.sqrt(norm));
                for (int k = 0; k < e.length; k++) {
                    e[k] *= inv;
                }
                result.add(e);
            }
        }
        return result;
    }

    /// Least-squares similarity transform from the 5 landmarks to the template, as 2x3 cv2 matrix. Same result as the
    /// skimage Umeyama estimate insightface uses (estimate_norm in [face_align.py](https://github.com/deepinsight/insightface/blob/master/python-package/insightface/utils/face_align.py);
    /// Umeyama, "Least-squares estimation of transformation parameters between two point patterns", IEEE TPAMI 13(4),
    /// 1991). cv2.estimateAffinePartial2D would differ (RANSAC), and bytedeco's calib3d drags in highgui/GTK.
    private static Mat align(float[] kps) {
        float msx = 0;
        float msy = 0;
        float mdx = 0;
        float mdy = 0;
        for (int i = 0; i < 5; i++) {
            msx += kps[2 * i];
            msy += kps[2 * i + 1];
            mdx += DST[2 * i];
            mdy += DST[2 * i + 1];
        }
        msx /= 5;
        msy /= 5;
        mdx /= 5;
        mdy /= 5;
        double numA = 0;
        double numB = 0;
        double den = 0;
        for (int i = 0; i < 5; i++) {
            double px = kps[2 * i] - msx;
            double py = kps[2 * i + 1] - msy;
            double qx = DST[2 * i] - mdx;
            double qy = DST[2 * i + 1] - mdy;
            numA += px * qx + py * qy;
            numB += px * qy - py * qx;
            den += px * px + py * py;
        }
        double a = numA / den; // dst = [[a,-b],[b,a]] * src + t
        double b = numB / den;
        double tx = mdx - (a * msx - b * msy);
        double ty = mdy - (b * msx + a * msy);
        return new Mat(2, 3, CV_64F, new DoublePointer(a, -b, tx, b, a, ty));
    }

    @Override
    public void close() {
        det.close();
        rec.close();
        env.close();
    }
}

package com.attendance;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.global.opencv_objdetect;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;

import ai.djl.inference.Predictor;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDArray;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class FaceEngine {
    // ArcFace cosine similarity matches are typically around 0.35 - 0.45+
    public static final float THRESHOLD = 0.35f;
    private static final Logger logger = Logger.getLogger(FaceEngine.class.getName());

    private static FaceEngine instance;
    private String activeEngine = "JavaCV";
    private boolean djlAvailable = false;
    private boolean djlRecognitionAvailable = false;

    // DJL components (Detection)
    private Predictor<Image, DetectedObjects> djlDetector;
    // DJL components (Recognition ArcFace)
    private Predictor<Image, float[]> djlRecognizer;

    // JavaCV components (fallback)
    private CascadeClassifier cvFaceCascade;
    private Java2DFrameConverter j2dConverter;
    private OpenCVFrameConverter.ToMat matConverter;

    public record FaceResult(Rectangle box, float[] embedding) {}

    public static synchronized FaceEngine getInstance() {
        if (instance == null) {
            instance = new FaceEngine();
        }
        return instance;
    }

    private FaceEngine() {
        initEngine();
    }

    private void initEngine() {
        initJavaCV();
        // Load DJL Detect & Recognize models async
        new Thread(() -> {
            try {
                logger.info("Attempting to load DJL Face Detection model...");
                Criteria<Image, DetectedObjects> detectCriteria = Criteria.builder()
                    .setTypes(Image.class, DetectedObjects.class)
                    .optApplication(ai.djl.Application.CV.OBJECT_DETECTION)
                    .optFilter("backbone", "resnet50")
                    .build();

                ZooModel<Image, DetectedObjects> detectModel = detectCriteria.loadModel();
                djlDetector = detectModel.newPredictor();
                djlAvailable = true;
                
                logger.info("Attempting to load Deep Learning Face Recognition (ArcFace)...");
                File onnxFile = new File("src/main/resources/insightface/w600k_r50.onnx");
                if (onnxFile.exists()) {
                    Criteria<Image, float[]> recCriteria = Criteria.builder()
                        .setTypes(Image.class, float[].class)
                        .optModelPath(onnxFile.toPath())
                        .optTranslator(new ArcFaceTranslator())
                        .optEngine("OnnxRuntime")
                        .build();
                    
                    ZooModel<Image, float[]> recModel = recCriteria.loadModel();
                    djlRecognizer = recModel.newPredictor();
                    djlRecognitionAvailable = true;
                    activeEngine = "DJL (ResNet50 Detect + ArcFace Recognize)";
                    logger.info("DJL ArcFace loaded successfully! Upgraded to Deep Learning Recognition.");
                } else {
                    activeEngine = "DJL (Detection) + Legacy CV Recognition";
                    logger.warning("ArcFace ONNX not found at " + onnxFile.getAbsolutePath());
                }

            } catch (Throwable t) {
                logger.warning("DJL init failed: " + t.getMessage());
            }
        }, "DJL-Init-Thread").start();
    }

    private void initJavaCV() {
        try {
            Loader.load(opencv_objdetect.class);
            j2dConverter = new Java2DFrameConverter();
            matConverter = new OpenCVFrameConverter.ToMat();

            File cascadeFile = extractCascade();
            if (cascadeFile != null && cascadeFile.exists()) {
                cvFaceCascade = new CascadeClassifier(cascadeFile.getAbsolutePath());
            } else {
                cvFaceCascade = new CascadeClassifier();
            }
        } catch (Throwable e) {
            logger.severe("Failed to initialize JavaCV: " + e.getMessage());
        }
    }

    private File extractCascade() {
        try {
            File temp = File.createTempFile("haarcascade_frontalface_alt", ".xml");
            temp.deleteOnExit();
            InputStream is = getClass().getResourceAsStream("/haarcascade_frontalface_alt.xml");
            if (is == null) return null;
            try (FileOutputStream out = new FileOutputStream(temp)) {
                is.transferTo(out);
                return temp;
            }
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized List<FaceResult> detectAndExtract(BufferedImage image) {
        List<FaceResult> results = new ArrayList<>();
        if (image == null) return results;

        // DJL Detection
        if (djlAvailable && djlDetector != null) {
            try {
                Image djlImg = ImageFactory.getInstance().fromImage(image);
                DetectedObjects detections = djlDetector.predict(djlImg);
                for (ai.djl.modality.Classifications.Classification c : detections.items()) {
                    if (c instanceof DetectedObjects.DetectedObject item) {
                        if ("face".equalsIgnoreCase(item.getClassName()) || "person".equalsIgnoreCase(item.getClassName())) {
                            ai.djl.modality.cv.output.BoundingBox box = item.getBoundingBox();
                            ai.djl.modality.cv.output.Rectangle rect = box.getBounds();
                            int x = Math.max(0, (int) (rect.getX() * image.getWidth()));
                            int y = Math.max(0, (int) (rect.getY() * image.getHeight()));
                            int w = Math.min(image.getWidth() - x, (int) (rect.getWidth() * image.getWidth()));
                            int h = Math.min(image.getHeight() - y, (int) (rect.getHeight() * image.getHeight()));
                            if (w > 20 && h > 20) {
                                BufferedImage sub = image.getSubimage(x, y, w, h);
                                float[] emb = extractEmbedding(sub);
                                results.add(new FaceResult(new Rectangle(x, y, w, h), emb));
                            }
                        }
                    }
                }
                return results;
            } catch (Exception e) {
                // fall back to javacv
            }
        }

        // OpenCV JavaCV detection
        if (cvFaceCascade != null && !cvFaceCascade.isNull()) {
            try {
                Mat mat = matConverter.convert(j2dConverter.convert(image));
                if (mat != null && !mat.empty()) {
                    Mat gray = new Mat();
                    opencv_imgproc.cvtColor(mat, gray, opencv_imgproc.COLOR_BGR2GRAY);
                    opencv_imgproc.equalizeHist(gray, gray);

                    RectVector faces = new RectVector();
                    cvFaceCascade.detectMultiScale(gray, faces, 1.1, 3, 0, new Size(40, 40), new Size());

                    for (long i = 0; i < faces.size(); i++) {
                        Rect r = faces.get(i);
                        int rx = Math.max(0, r.x());
                        int ry = Math.max(0, r.y());
                        int rw = Math.min(image.getWidth() - rx, r.width());
                        int rh = Math.min(image.getHeight() - ry, r.height());
                        if (rw > 20 && rh > 20) {
                            BufferedImage sub = image.getSubimage(rx, ry, rw, rh);
                            float[] emb = extractEmbedding(sub);
                            results.add(new FaceResult(new Rectangle(rx, ry, rw, rh), emb));
                        }
                    }
                    mat.release();
                    gray.release();
                }
            } catch (Exception e) {
                // Ignore
            }
        }
        return results;
    }

    public float[] extractEmbedding(BufferedImage faceImage) {
        if (faceImage == null) return new float[djlRecognitionAvailable ? 512 : 128];
        
        // Use Deep Learning ArcFace Model if available!
        if (djlRecognitionAvailable && djlRecognizer != null) {
            try {
                Image djlImg = ImageFactory.getInstance().fromImage(faceImage);
                float[] emb = djlRecognizer.predict(djlImg);
                
                // L2 Normalize
                float norm = 0f;
                for (float v : emb) norm += v * v;
                norm = (float) Math.sqrt(norm);
                if (norm > 0) {
                    for (int i = 0; i < emb.length; i++) emb[i] /= norm;
                }
                return emb;
            } catch (Exception e) {
                logger.warning("ArcFace failed, falling back to legacy... " + e.getMessage());
            }
        }

        // Legacy 128-dim fallback
        int targetDim = 128;
        float[] embedding = new float[targetDim];
        int w = 32, h = 32;
        BufferedImage resized = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        java.awt.Graphics2D g = resized.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(faceImage, 0, 0, w, h, null);
        g.dispose();

        float[] raw = new float[w * h];
        float sum = 0f;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int gray = resized.getRGB(x, y) & 0xFF;
                raw[y * w + x] = gray;
                sum += gray;
            }
        }
        float mean = sum / (w * h);
        float variance = 0f;
        for (float v : raw) variance += (v - mean) * (v - mean);
        float std = (float) Math.sqrt(variance / (w * h) + 1e-5);

        for (int i = 0; i < targetDim; i++) {
            float val = 0f;
            int stride = (w * h) / targetDim;
            for (int j = 0; j < stride; j++) {
                int idx = i * stride + j;
                val += (raw[idx] - mean) / std;
            }
            embedding[i] = val / stride;
        }

        float norm = 0f;
        for (float v : embedding) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < embedding.length; i++) embedding[i] /= norm;
        }
        return embedding;
    }

    public static float cosineSimilarity(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length) return 0f;
        float dot = 0f, normA = 0f, normB = 0f;
        for (int i = 0; i < v1.length; i++) {
            dot += v1[i] * v2[i];
            normA += v1[i] * v1[i];
            normB += v2[i] * v2[i];
        }
        if (normA == 0 || normB == 0) return 0f;
        return (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
    }

    public static float[] averageEmbeddings(List<float[]> samples) {
        if (samples == null || samples.isEmpty()) return new float[128];
        int dim = samples.get(0).length;
        float[] avg = new float[dim];
        for (float[] s : samples) {
            for (int i = 0; i < dim; i++) avg[i] += s[i];
        }
        float norm = 0f;
        for (int i = 0; i < dim; i++) {
            avg[i] /= samples.size();
            norm += avg[i] * avg[i];
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dim; i++) avg[i] /= norm;
        }
        return avg;
    }

    public String getActiveEngine() {
        return activeEngine;
    }
}

class ArcFaceTranslator implements Translator<Image, float[]> {
    @Override
    public NDList processInput(TranslatorContext ctx, Image input) {
        NDArray array = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        // ArcFace requires 112x112 input
        array = ai.djl.modality.cv.util.NDImageUtils.resize(array, 112, 112);
        // HWC to CHW
        array = array.transpose(2, 0, 1);
        // Normalize (x - 127.5) / 127.5
        array = array.sub(127.5f).div(127.5f);
        return new NDList(array);
    }

    @Override
    public float[] processOutput(TranslatorContext ctx, NDList list) {
        // Output is 1x512
        return list.singletonOrThrow().toFloatArray();
    }
}

package com.attendance;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public class CameraPanel extends JPanel {
    private static final Logger logger = Logger.getLogger(CameraPanel.class.getName());

    private static volatile BufferedImage currentFrame;
    private FFmpegFrameGrabber grabber;
    private final Java2DFrameConverter converter = new Java2DFrameConverter();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final FaceEngine faceEngine = FaceEngine.getInstance();
    private volatile List<Db.Person> cachedPersons = new ArrayList<>();
    private Runnable onAttendanceMarked;

    // ── Bulk-attendance state ──────────────────────────────────────────────────
    private final AtomicBoolean bulkMode = new AtomicBoolean(false);
    private volatile long bulkEndTime = 0;
    /** person id -> already marked during this bulk session */
    private final Set<Integer> bulkMarked = new HashSet<>();

    public record FaceOverlay(Rectangle rect, String label, boolean known) {}
    private volatile List<FaceOverlay> overlays = new ArrayList<>();

    public CameraPanel() {
        setBackground(Color.BLACK);
        setPreferredSize(new Dimension(640, 480));
        refreshPersons();
        startCamera();
    }

    public static BufferedImage getLatestFrame() {
        return currentFrame;
    }

    public void setOnAttendanceMarked(Runnable callback) {
        this.onAttendanceMarked = callback;
    }

    public void refreshPersons() {
        Executors.newSingleThreadExecutor().submit(() -> this.cachedPersons = Db.getAllPersons());
    }

    // ── Bulk attendance ────────────────────────────────────────────────────────

    /**
     * Start a 5-second bulk-attendance window.  Every face recognised in those
     * 5 s is marked (at most once per person per day, same as the normal flow).
     *
     * @param durationSeconds how long to scan (usually 5)
     * @param onDone called on the EDT when the session ends, receives summary string
     */
    public void startBulkAttendance(int durationSeconds, java.util.function.Consumer<String> onDone) {
        if (bulkMode.getAndSet(true)) return;   // already running
        bulkMarked.clear();
        bulkEndTime = System.currentTimeMillis() + (long) durationSeconds * 1000L;

        // schedule end of bulk window
        Executors.newSingleThreadScheduledExecutor().schedule(() -> {
            bulkMode.set(false);
            int count = bulkMarked.size();
            String summary = count == 0
                ? "No known faces were detected."
                : count + " person(s) marked present:\n" +
                  cachedPersons.stream()
                      .filter(p -> bulkMarked.contains(p.id()))
                      .map(p -> "  • " + p.name() + " (" + p.rollNo() + ")")
                      .reduce("", (a, b) -> a + "\n" + b);
            if (onDone != null)
                SwingUtilities.invokeLater(() -> onDone.accept(summary));
        }, durationSeconds, TimeUnit.SECONDS);
    }

    // ── Camera internals ───────────────────────────────────────────────────────

    private void startCamera() {
        Executors.newSingleThreadExecutor().submit(() -> {
            if (openWithFormat("avfoundation", "MacBook Air Camera")) return;
            if (openWithFormat("avfoundation", "FaceTime HD Camera")) return;
            if (openWithFormat("avfoundation", "0")) return;
            if (openWithFormat(null, "/dev/video0")) return;
            if (openWithFormat("dshow", "video=0")) return;
            logger.severe("All camera open attempts failed. No camera available.");
        });
    }

    private boolean openWithFormat(String format, String device) {
        try {
            logger.info("Trying camera: format=" + format + " device=" + device);
            FFmpegFrameGrabber g = new FFmpegFrameGrabber(device);
            if (format != null) g.setFormat(format);
            g.setImageWidth(640);
            g.setImageHeight(480);
            g.setFrameRate(30);
            g.setVideoOption("framerate", "30");
            g.start();

            Frame test = g.grab();
            if (test == null || test.image == null) {
                g.stop(); g.release();
                logger.warning("Camera opened but returned no frames: " + device);
                return false;
            }

            grabber = g;
            logger.info("Camera OPEN: format=" + format + " device=" + device);

            scheduler.scheduleAtFixedRate(this::grabFrame, 0, 33, TimeUnit.MILLISECONDS);
            scheduler.scheduleAtFixedRate(this::runRecognition, 300, 300, TimeUnit.MILLISECONDS);
            return true;

        } catch (Throwable t) {
            logger.warning("Camera failed [" + format + " | " + device + "]: " + t.getMessage());
            return false;
        }
    }

    private void grabFrame() {
        if (grabber == null) return;
        try {
            Frame frame = grabber.grab();
            if (frame != null && frame.image != null) {
                BufferedImage img = converter.convert(frame);
                if (img != null) {
                    currentFrame = img;
                    SwingUtilities.invokeLater(this::repaint);
                }
            }
        } catch (Exception ignored) {}
    }

    private void runRecognition() {
        BufferedImage img = currentFrame;
        if (img == null) return;

        List<FaceEngine.FaceResult> detections = faceEngine.detectAndExtract(img);
        List<FaceOverlay> newOverlays = new ArrayList<>();
        String date = LocalDate.now().toString();
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));

        boolean inBulk = bulkMode.get();

        for (FaceEngine.FaceResult face : detections) {
            float best = 0f;
            Db.Person match = null;
            for (Db.Person p : cachedPersons) {
                float sim = FaceEngine.cosineSimilarity(face.embedding(), p.embedding());
                System.out.printf("Compared to %s: sim = %.3f\n", p.name(), sim);
                if (sim > best) { best = sim; if (sim >= FaceEngine.THRESHOLD) match = p; }
            }
            boolean known = match != null;
            String label = known ? String.format("%s (%.0f%%)", match.name(), best * 100) : "Unknown";
            newOverlays.add(new FaceOverlay(face.box(), label, known));

            if (known) {
                final Db.Person finalMatch = match;
                if (inBulk) {
                    // bulk mode: mark once per person, track in bulkMarked
                    if (!bulkMarked.contains(finalMatch.id())) {
                        bulkMarked.add(finalMatch.id());
                        Executors.newSingleThreadExecutor().submit(() -> {
                            boolean marked = Db.markAttendance(finalMatch.id(), date, time);
                            if (marked && onAttendanceMarked != null) {
                                SwingUtilities.invokeLater(onAttendanceMarked);
                            }
                        });
                    }
                } else {
                    // normal continuous mode
                    Executors.newSingleThreadExecutor().submit(() -> {
                        boolean marked = Db.markAttendance(finalMatch.id(), date, time);
                        if (marked && onAttendanceMarked != null)
                            SwingUtilities.invokeLater(onAttendanceMarked);
                    });
                }
            }
        }
        this.overlays = newOverlays;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        BufferedImage img = currentFrame;
        if (img != null) {
            int pw = getWidth(), ph = getHeight();
            double scale = Math.min((double) pw / img.getWidth(), (double) ph / img.getHeight());
            int dw = (int) (img.getWidth() * scale);
            int dh = (int) (img.getHeight() * scale);
            int ox = (pw - dw) / 2, oy = (ph - dh) / 2;
            g2.drawImage(img, ox, oy, dw, dh, null);

            // If in bulk-mode, draw a countdown overlay
            if (bulkMode.get()) {
                long remaining = Math.max(0, bulkEndTime - System.currentTimeMillis());
                String countdown = String.format("Taking Attendance… %d s", (remaining / 1000) + 1);
                g2.setFont(new Font("SansSerif", Font.BOLD, 20));
                FontMetrics fm = g2.getFontMetrics();
                int tw = fm.stringWidth(countdown);
                int bx = (pw - tw) / 2 - 10;
                int by = 12;
                g2.setColor(new Color(255, 80, 0, 210));
                g2.fillRoundRect(bx, by, tw + 20, fm.getHeight() + 8, 12, 12);
                g2.setColor(Color.WHITE);
                g2.drawString(countdown, bx + 10, by + fm.getAscent() + 4);
            }

            for (FaceOverlay ov : overlays) {
                Rectangle r = ov.rect();
                int rx = ox + (int) (r.x * scale);
                int ry = oy + (int) (r.y * scale);
                int rw = (int) (r.width * scale);
                int rh = (int) (r.height * scale);

                g2.setColor(ov.known() ? new Color(46, 204, 113) : new Color(231, 76, 60));
                g2.setStroke(new BasicStroke(2.5f));
                g2.drawRoundRect(rx, ry, rw, rh, 8, 8);

                g2.setFont(new Font("SansSerif", Font.BOLD, 13));
                FontMetrics fm = g2.getFontMetrics();
                int tw = fm.stringWidth(ov.label()), th = fm.getHeight();
                g2.setColor(new Color(0, 0, 0, 180));
                g2.fillRoundRect(rx, Math.max(0, ry - th - 6), tw + 12, th + 4, 6, 6);
                g2.setColor(Color.WHITE);
                g2.drawString(ov.label(), rx + 6, Math.max(th - 2, ry - 6));
            }
        } else {
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("SansSerif", Font.PLAIN, 16));
            String msg = "Connecting to camera...";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, getHeight() / 2);
        }
        g2.dispose();
    }

    public void closeCamera() {
        try {
            scheduler.shutdownNow();
            if (grabber != null) { grabber.stop(); grabber.release(); }
        } catch (Exception e) { e.printStackTrace(); }
    }
}

package com.attendance;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

public class RegisterDialog extends JDialog {
    private final JTextField txtName = new JTextField(15);
    private final JTextField txtRoll = new JTextField(15);
    private final JLabel lblStatus = new JLabel("Enter details and click 'Start Auto Capture'.");
    private final JProgressBar progressBar = new JProgressBar(0, 5);
    private final JButton btnStart = new JButton("Start Auto Capture");
    private final JLabel lblPreview = new JLabel();

    private final List<float[]> capturedEmbeddings = new ArrayList<>();
    private final FaceEngine faceEngine = FaceEngine.getInstance();
    private final Runnable onSuccess;

    private Timer autoCaptureTimer;
    private Timer previewTimer;

    public RegisterDialog(JFrame parent, Runnable onSuccess) {
        super(parent, "Register New Person", true);
        this.onSuccess = onSuccess;

        setSize(480, 560);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout(10, 10));

        // ── Form Panel ────────────────────────────────────────────────────────
        JPanel formPanel = new JPanel(new GridLayout(2, 2, 8, 8));
        formPanel.setBorder(new EmptyBorder(15, 20, 10, 20));
        formPanel.add(new JLabel("Full Name:"));
        formPanel.add(txtName);
        formPanel.add(new JLabel("Roll No / ID:"));
        formPanel.add(txtRoll);

        add(formPanel, BorderLayout.NORTH);

        // ── Center Preview ────────────────────────────────────────────────────
        JPanel centerPanel = new JPanel(new BorderLayout(5, 5));
        centerPanel.setBorder(new EmptyBorder(0, 20, 0, 20));

        lblPreview.setPreferredSize(new Dimension(320, 240));
        lblPreview.setHorizontalAlignment(SwingConstants.CENTER);
        lblPreview.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        lblPreview.setText("Live Preview / Sample");
        centerPanel.add(lblPreview, BorderLayout.CENTER);

        progressBar.setValue(0);
        progressBar.setStringPainted(true);
        progressBar.setString("0 / 5 Samples");
        centerPanel.add(progressBar, BorderLayout.SOUTH);

        add(centerPanel, BorderLayout.CENTER);

        // ── Bottom Panel ──────────────────────────────────────────────────────
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 5));
        bottomPanel.setBorder(new EmptyBorder(10, 20, 15, 20));

        lblStatus.setHorizontalAlignment(SwingConstants.CENTER);
        bottomPanel.add(lblStatus, BorderLayout.NORTH);

        JPanel btnGroup = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        btnGroup.add(btnStart);
        bottomPanel.add(btnGroup, BorderLayout.SOUTH);

        add(bottomPanel, BorderLayout.SOUTH);

        // ── Actions ───────────────────────────────────────────────────────────
        btnStart.addActionListener(e -> startAutoCapture());

        // Update live preview in dialog
        previewTimer = new Timer(50, e -> updatePreview());
        previewTimer.start();
        
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                previewTimer.stop();
                if (autoCaptureTimer != null) autoCaptureTimer.stop();
            }
        });
    }

    private void updatePreview() {
        BufferedImage img = CameraPanel.getLatestFrame();
        if (img != null) {
            Image scaled = img.getScaledInstance(lblPreview.getWidth(), lblPreview.getHeight(), Image.SCALE_FAST);
            lblPreview.setIcon(new ImageIcon(scaled));
        }
    }

    private void startAutoCapture() {
        String name = txtName.getText().trim();
        String roll = txtRoll.getText().trim();

        if (name.isEmpty() || roll.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter Name and Roll/ID first.", "Input Required", JOptionPane.WARNING_MESSAGE);
            return;
        }

        txtName.setEnabled(false);
        txtRoll.setEnabled(false);
        btnStart.setEnabled(false);
        capturedEmbeddings.clear();
        
        lblStatus.setText("Auto-capturing... Please look at the camera.");
        lblStatus.setForeground(Color.BLUE);

        // Timer that fires every 600ms to grab a sample
        autoCaptureTimer = new Timer(600, e -> attemptCapture());
        autoCaptureTimer.start();
    }

    private void attemptCapture() {
        BufferedImage img = CameraPanel.getLatestFrame();
        if (img == null) return;

        List<FaceEngine.FaceResult> faces = faceEngine.detectAndExtract(img);
        if (faces.isEmpty()) {
            lblStatus.setText("No face detected! Please face the camera.");
            lblStatus.setForeground(Color.RED);
            return;
        }

        // Take primary face
        FaceEngine.FaceResult primary = faces.get(0);
        capturedEmbeddings.add(primary.embedding());

        int count = capturedEmbeddings.size();
        progressBar.setValue(count);
        progressBar.setString(count + " / 5 Samples");
        
        lblStatus.setText("Captured sample " + count + " of 5...");
        lblStatus.setForeground(new Color(34, 139, 34));

        if (count >= 5) {
            autoCaptureTimer.stop();
            lblStatus.setText("All samples captured! Saving...");
            savePerson();
        }
    }

    private void savePerson() {
        String name = txtName.getText().trim();
        String roll = txtRoll.getText().trim();

        float[] avgEmbedding = FaceEngine.averageEmbeddings(capturedEmbeddings);
        boolean saved = Db.savePerson(name, roll, avgEmbedding);

        if (saved) {
            JOptionPane.showMessageDialog(this, "Person registered successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
            if (onSuccess != null) {
                onSuccess.run();
            }
            dispose();
        } else {
            JOptionPane.showMessageDialog(this, "Failed to register. Roll/ID may already exist.", "Error", JOptionPane.ERROR_MESSAGE);
            // Reset for retry
            txtName.setEnabled(true);
            txtRoll.setEnabled(true);
            btnStart.setEnabled(true);
            lblStatus.setText("Failed to save. Try a different Roll/ID.");
            lblStatus.setForeground(Color.RED);
            capturedEmbeddings.clear();
            progressBar.setValue(0);
            progressBar.setString("0 / 5 Samples");
        }
    }
}

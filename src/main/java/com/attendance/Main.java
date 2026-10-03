package com.attendance;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.logging.Logger;

public class Main extends JFrame {
    private static final Logger logger = Logger.getLogger(Main.class.getName());
    private final CameraPanel cameraPanel;
    private final AttendancePanel attendancePanel;

    public Main() {
        super("Facial Attendance System");

        String activeEngine = FaceEngine.getInstance().getActiveEngine();
        logger.info("==================================================");
        logger.info("Facial Attendance System Started");
        logger.info("Active Face Engine: " + activeEngine);
        logger.info("==================================================");

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1200, 700);
        setMinimumSize(new Dimension(900, 550));
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // ── Toolbar ────────────────────────────────────────────────────────────
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));

        JButton btnRegister = new JButton("➕ Register Person");
        btnRegister.setFont(new Font("SansSerif", Font.BOLD, 12));
        btnRegister.setForeground(new Color(0x2c7be5));
        btnRegister.addActionListener(e -> openRegisterDialog());

        JButton btnManage = new JButton("📋 Manage Persons");
        btnManage.setFont(new Font("SansSerif", Font.BOLD, 12));
        btnManage.setForeground(new Color(0x6f42c1));
        btnManage.addActionListener(e -> openManageDialog());

        // ── Take Attendance button ─────────────────────────────────────────────
        JButton btnTakeAtt = new JButton("📸 Take Attendance (5 s)");
        btnTakeAtt.setFont(new Font("SansSerif", Font.BOLD, 13));
        btnTakeAtt.setForeground(new Color(0xff6f00));
        btnTakeAtt.setBackground(new Color(0xfff3e0));
        btnTakeAtt.setOpaque(true);
        btnTakeAtt.addActionListener(e -> startTakeAttendance(btnTakeAtt));

        JLabel lblEngine = new JLabel("Engine: " + activeEngine);
        lblEngine.setFont(new Font("SansSerif", Font.ITALIC, 11));
        lblEngine.setForeground(Color.DARK_GRAY);

        toolBar.add(btnRegister);
        toolBar.addSeparator(new Dimension(10, 0));
        toolBar.add(btnManage);
        toolBar.addSeparator(new Dimension(10, 0));
        toolBar.add(btnTakeAtt);
        toolBar.add(Box.createHorizontalGlue());
        toolBar.add(lblEngine);
        add(toolBar, BorderLayout.NORTH);

        // ── Center Split ────────────────────────────────────────────────────────
        cameraPanel = new CameraPanel();
        attendancePanel = new AttendancePanel();

        cameraPanel.setOnAttendanceMarked(() -> attendancePanel.refreshAttendance());

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, cameraPanel, attendancePanel);
        splitPane.setResizeWeight(0.60);
        splitPane.setContinuousLayout(true);
        add(splitPane, BorderLayout.CENTER);

        // ── Window close ─────────────────────────────────────────────────────────
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cameraPanel.closeCamera();
                dispose();
                System.exit(0);
            }
        });
    }

    private void openRegisterDialog() {
        RegisterDialog dialog = new RegisterDialog(this, () -> {
            cameraPanel.refreshPersons();
            attendancePanel.refreshAttendance();
        });
        dialog.setVisible(true);
    }

    private void openManageDialog() {
        ManagePersonsDialog dialog = new ManagePersonsDialog(this, () -> {
            cameraPanel.refreshPersons();
            attendancePanel.refreshAttendance();
        });
        dialog.setVisible(true);
    }

    private void startTakeAttendance(JButton btn) {
        if (!btn.isEnabled()) return;
        btn.setEnabled(false);
        btn.setText("📸 Scanning… (5 s)");

        // Show a non-blocking status message at bottom of window
        setTitle("Facial Attendance System — Taking Attendance…");

        cameraPanel.startBulkAttendance(5, summary -> {
            // Called on EDT after 5 seconds
            btn.setEnabled(true);
            btn.setText("📸 Take Attendance (5 s)");
            setTitle("Facial Attendance System");
            attendancePanel.refreshAttendance();
            JOptionPane.showMessageDialog(
                this,
                "Attendance session complete!\n\n" + summary,
                "Attendance Result",
                JOptionPane.INFORMATION_MESSAGE
            );
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            new Main().setVisible(true);
        });
    }
}

package com.attendance;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.util.List;

public class AttendancePanel extends JPanel {

    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JLabel lblCount = new JLabel("Present Today: 0");
    /** Stores the DB attendance record id per row so we can delete */
    private java.util.List<Integer> rowIds = new java.util.ArrayList<>();
    private boolean showingAll = false;

    public AttendancePanel() {
        setLayout(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(10, 10, 10, 10));

        // ── Header ────────────────────────────────────────────────────────
        JPanel topPanel = new JPanel(new BorderLayout());
        JLabel title = new JLabel("Today's Attendance (" + LocalDate.now() + ")");
        title.setFont(new Font("SansSerif", Font.BOLD, 15));
        topPanel.add(title, BorderLayout.WEST);

        lblCount.setFont(new Font("SansSerif", Font.PLAIN, 13));
        topPanel.add(lblCount, BorderLayout.EAST);
        add(topPanel, BorderLayout.NORTH);

        // ── Table ────────────────────────────────────────────────────────
        String[] columns = {"Name", "Roll No / ID", "Time Marked", "Date"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };

        table = new JTable(tableModel);
        table.setRowHeight(26);
        table.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scrollPane = new JScrollPane(table);
        add(scrollPane, BorderLayout.CENTER);

        // ── Bottom Controls ───────────────────────────────────────────────
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));

        JButton btnRefresh   = new JButton("Refresh");
        JButton btnToggle    = new JButton("Show All Dates");
        JButton btnDelete    = new JButton("Delete Selected");
        JButton btnExport    = new JButton("Export CSV");

        btnRefresh.addActionListener(e -> refreshAttendance());
        btnExport.addActionListener(e -> exportCsv());

        btnToggle.addActionListener(e -> {
            showingAll = !showingAll;
            btnToggle.setText(showingAll ? "Show Today" : "Show All Dates");
            refreshAttendance();
        });

        btnDelete.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) {
                JOptionPane.showMessageDialog(this,
                    "Please select a row to delete.", "No Selection", JOptionPane.WARNING_MESSAGE);
                return;
            }
            int confirm = JOptionPane.showConfirmDialog(this,
                "Delete attendance record for " + tableModel.getValueAt(row, 0) + "?",
                "Confirm Delete", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                int id = rowIds.get(row);
                if (Db.deleteAttendanceById(id)) {
                    refreshAttendance();
                } else {
                    JOptionPane.showMessageDialog(this, "Failed to delete record.", "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        bottomPanel.add(btnRefresh);
        bottomPanel.add(btnToggle);
        bottomPanel.add(btnDelete);
        bottomPanel.add(btnExport);
        add(bottomPanel, BorderLayout.SOUTH);

        refreshAttendance();
    }

    public void refreshAttendance() {
        tableModel.setRowCount(0);
        rowIds.clear();

        List<Db.AttendanceRecord> list = showingAll
            ? Db.getAllAttendance()
            : Db.getTodayAttendance(LocalDate.now().toString());

        for (Db.AttendanceRecord rec : list) {
            tableModel.addRow(new Object[]{rec.name(), rec.rollNo(), rec.time(), rec.date()});
            rowIds.add(rec.id());
        }

        if (!showingAll) {
            lblCount.setText("Present Today: " + list.size());
        } else {
            lblCount.setText("Total Records: " + list.size());
        }
    }

    private void exportCsv() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setSelectedFile(new File("attendance_" + LocalDate.now() + ".csv"));
        int option = fileChooser.showSaveDialog(this);
        if (option == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            try (PrintWriter pw = new PrintWriter(new FileWriter(file))) {
                pw.println("Name,Roll No,Time,Date");
                for (int i = 0; i < tableModel.getRowCount(); i++) {
                    pw.printf("\"%s\",\"%s\",\"%s\",\"%s\"%n",
                        tableModel.getValueAt(i, 0),
                        tableModel.getValueAt(i, 1),
                        tableModel.getValueAt(i, 2),
                        tableModel.getValueAt(i, 3));
                }
                JOptionPane.showMessageDialog(this, "CSV exported successfully!", "Export Done", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "Error exporting CSV: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
}

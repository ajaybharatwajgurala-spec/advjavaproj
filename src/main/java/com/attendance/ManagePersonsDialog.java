package com.attendance;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/**
 * Dialog that lists all registered persons and allows Edit / Delete operations.
 */
public class ManagePersonsDialog extends JDialog {

    private final DefaultTableModel tableModel;
    private final JTable table;
    private List<Db.Person> persons;
    private final Runnable onChanged;

    public ManagePersonsDialog(Frame parent, Runnable onChanged) {
        super(parent, "Manage Registered Persons", true);
        this.onChanged = onChanged;

        setSize(600, 400);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout(10, 10));

        // ── Table ────────────────────────────────────────────────────────
        String[] cols = {"#", "Name", "Roll No / ID"};
        tableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        table = new JTable(tableModel);
        table.setRowHeight(26);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setBorder(new EmptyBorder(10, 10, 0, 10));
        add(scrollPane, BorderLayout.CENTER);

        // ── Buttons ──────────────────────────────────────────────────────
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 10));

        JButton btnEdit   = new JButton("Edit Selected");
        JButton btnDelete = new JButton("Delete Selected");
        JButton btnClose  = new JButton("Close");

        btnEdit.addActionListener(e -> editSelected());
        btnDelete.addActionListener(e -> deleteSelected());
        btnClose.addActionListener(e -> dispose());

        btnPanel.add(btnEdit);
        btnPanel.add(btnDelete);
        btnPanel.add(btnClose);
        add(btnPanel, BorderLayout.SOUTH);

        loadData();
    }

    private void loadData() {
        tableModel.setRowCount(0);
        persons = Db.getAllPersons();
        int idx = 1;
        for (Db.Person p : persons) {
            tableModel.addRow(new Object[]{idx++, p.name(), p.rollNo()});
        }
    }

    private void editSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Please select a person to edit.", "No Selection", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Db.Person person = persons.get(row);

        JTextField nameField = new JTextField(person.name(), 18);
        JTextField rollField = new JTextField(person.rollNo(), 18);

        JPanel panel = new JPanel(new GridLayout(2, 2, 8, 8));
        panel.add(new JLabel("Full Name:"));
        panel.add(nameField);
        panel.add(new JLabel("Roll No / ID:"));
        panel.add(rollField);

        int result = JOptionPane.showConfirmDialog(this, panel,
            "Edit Person", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            String newName = nameField.getText().trim();
            String newRoll = rollField.getText().trim();
            if (newName.isEmpty() || newRoll.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Name and Roll No cannot be empty.", "Validation", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (Db.updatePerson(person.id(), newName, newRoll)) {
                JOptionPane.showMessageDialog(this, "Updated successfully.", "Success", JOptionPane.INFORMATION_MESSAGE);
                loadData();
                if (onChanged != null) onChanged.run();
            } else {
                JOptionPane.showMessageDialog(this, "Update failed. Roll/ID may already exist.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void deleteSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Please select a person to delete.", "No Selection", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Db.Person person = persons.get(row);
        int confirm = JOptionPane.showConfirmDialog(this,
            "Delete \"" + person.name() + "\" and ALL their attendance records?\nThis cannot be undone.",
            "Confirm Delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm == JOptionPane.YES_OPTION) {
            if (Db.deletePerson(person.id())) {
                JOptionPane.showMessageDialog(this, "Deleted successfully.", "Done", JOptionPane.INFORMATION_MESSAGE);
                loadData();
                if (onChanged != null) onChanged.run();
            } else {
                JOptionPane.showMessageDialog(this, "Delete failed.", "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
}

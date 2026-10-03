package com.attendance;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class Db {
    private static final String URL = "jdbc:sqlite:attendance.db";

    public record Person(int id, String name, String rollNo, float[] embedding) {}
    public record AttendanceRecord(int id, String name, String rollNo, String time, String date) {
        /** Convenience ctor without id (for backward compat) */
        public AttendanceRecord(String name, String rollNo, String time) {
            this(0, name, rollNo, time, "");
        }
    }

    static {
        init();
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL);
    }

    public static void init() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS persons (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    roll_no TEXT NOT NULL UNIQUE,
                    embedding BLOB NOT NULL
                );
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS attendance (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    person_id INTEGER NOT NULL,
                    date TEXT NOT NULL,
                    time TEXT NOT NULL,
                    UNIQUE(person_id, date),
                    FOREIGN KEY(person_id) REFERENCES persons(id)
                );
            """);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public static boolean savePerson(String name, String rollNo, float[] embedding) {
        String sql = "INSERT INTO persons(name, roll_no, embedding) VALUES(?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, name);
            pstmt.setString(2, rollNo);
            pstmt.setBytes(3, floatArrayToByteArray(embedding));
            pstmt.executeUpdate();
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static List<Person> getAllPersons() {
        List<Person> list = new ArrayList<>();
        String sql = "SELECT id, name, roll_no, embedding FROM persons";
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                int id = rs.getInt("id");
                String name = rs.getString("name");
                String rollNo = rs.getString("roll_no");
                byte[] blob = rs.getBytes("embedding");
                float[] emb = byteArrayToFloatArray(blob);
                list.add(new Person(id, name, rollNo, emb));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    public static boolean markAttendance(int personId, String date, String time) {
        String sql = "INSERT OR IGNORE INTO attendance(person_id, date, time) VALUES(?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, personId);
            pstmt.setString(2, date);
            pstmt.setString(3, time);
            int affected = pstmt.executeUpdate();
            return affected > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /** Update a person's name and roll number by id */
    public static boolean updatePerson(int id, String name, String rollNo) {
        String sql = "UPDATE persons SET name=?, roll_no=? WHERE id=?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, name);
            pstmt.setString(2, rollNo);
            pstmt.setInt(3, id);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /** Delete a person and all their attendance records */
    public static boolean deletePerson(int id) {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (
                PreparedStatement delAtt = conn.prepareStatement("DELETE FROM attendance WHERE person_id=?");
                PreparedStatement delPer = conn.prepareStatement("DELETE FROM persons WHERE id=?")
            ) {
                delAtt.setInt(1, id);
                delAtt.executeUpdate();
                delPer.setInt(1, id);
                delPer.executeUpdate();
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                e.printStackTrace();
                return false;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /** Delete a single attendance record by id */
    public static boolean deleteAttendanceById(int id) {
        String sql = "DELETE FROM attendance WHERE id=?";
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, id);
            return pstmt.executeUpdate() > 0;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /** Get all attendance records across all dates */
    public static List<AttendanceRecord> getAllAttendance() {
        List<AttendanceRecord> list = new ArrayList<>();
        String sql = """
            SELECT a.id, p.name, p.roll_no, a.time, a.date
            FROM attendance a
            JOIN persons p ON a.person_id = p.id
            ORDER BY a.date DESC, a.id DESC
        """;
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new AttendanceRecord(
                    rs.getInt("id"),
                    rs.getString("name"),
                    rs.getString("roll_no"),
                    rs.getString("time"),
                    rs.getString("date")
                ));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    public static List<AttendanceRecord> getTodayAttendance(String date) {
        List<AttendanceRecord> list = new ArrayList<>();
        String sql = """
            SELECT a.id, p.name, p.roll_no, a.time
            FROM attendance a
            JOIN persons p ON a.person_id = p.id
            WHERE a.date = ?
            ORDER BY a.id DESC
        """;
        try (Connection conn = getConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, date);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(new AttendanceRecord(
                        rs.getInt("id"),
                        rs.getString("name"),
                        rs.getString("roll_no"),
                        rs.getString("time"),
                        date
                    ));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    public static byte[] floatArrayToByteArray(float[] floats) {
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * 4);
        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    public static float[] byteArrayToFloatArray(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        FloatBuffer fb = buffer.asFloatBuffer();
        float[] floats = new float[fb.remaining()];
        fb.get(floats);
        return floats;
    }
}

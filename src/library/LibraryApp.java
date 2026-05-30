package library;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class LibraryApp {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            Ui.installTheme();
            new MainFrame().setVisible(true);
        });
    }
}

enum UserType {
    STUDENT, ADMIN
}

record LoginSession(UserType type, int id, String account, String displayName, String roleLevel) {
}

record Book(int id, String title, String authors, String subjects, String publisher, String publishYear,
            String isbnList, int totalCopies, int availableCopies, String status) {
}

record BorrowRow(int recordId, String studentNo, String studentName, String title, Timestamp borrowDate,
                 Timestamp dueDate, Timestamp returnDate, int borrowDays) {
    String statusText() {
        if (returnDate != null) {
            return "已歸還";
        }
        if (dueDate.toLocalDateTime().isBefore(LocalDateTime.now())) {
            return "逾期";
        }
        if (!dueDate.toLocalDateTime().isAfter(LocalDateTime.now().plusDays(3))) {
            return "即將到期";
        }
        return "借閱中";
    }
}

record UserRow(int id, String studentNo, String name, String roleLevel, String status, Timestamp createdAt,
               int activeLoans, int totalLoans) {
}

record AdminStats(int totalBooks, int availableBooks, int archivedBooks, int activeLoans, int overdueLoans,
                  int suspendedUsers) {
}

final class Db {
    private static final String DEFAULT_URL = "jdbc:mysql://localhost:3306/library_system?useSSL=false&serverTimezone=Asia/Taipei&allowPublicKeyRetrieval=true";

    private Db() {
    }

    static Connection connect() throws SQLException {
        String url = env("LIB_DB_URL", DEFAULT_URL);
        String user = env("LIB_DB_USER", "library_app");
        String password = env("LIB_DB_PASSWORD", "");
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException ignored) {
            // DriverManager can still locate the driver when the jar is on the classpath.
        }
        return DriverManager.getConnection(url, user, password);
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}

final class Passwords {
    private Passwords() {
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot hash password", ex);
        }
    }
}

final class AuthService {
    LoginSession loginStudent(String studentNo, String password) throws SQLException {
        String sql = """
                SELECT user_id, student_no, name, role_level, status
                FROM users
                WHERE student_no = ? AND password_hash = ?
                """;
        try (Connection conn = Db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, studentNo);
            ps.setString(2, Passwords.sha256(password));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("學生帳號或密碼錯誤");
                }
                if (!"ACTIVE".equals(rs.getString("status"))) {
                    throw new SQLException("此學生帳號已被停權");
                }
                return new LoginSession(UserType.STUDENT, rs.getInt("user_id"), rs.getString("student_no"),
                        rs.getString("name"), rs.getString("role_level"));
            }
        }
    }

    LoginSession loginAdmin(String username, String password) throws SQLException {
        String sql = "SELECT admin_id, username FROM admins WHERE username = ? AND password_hash = ?";
        try (Connection conn = Db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, Passwords.sha256(password));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("管理員帳號或密碼錯誤");
                }
                return new LoginSession(UserType.ADMIN, rs.getInt("admin_id"), rs.getString("username"),
                        rs.getString("username"), "ADMIN");
            }
        }
    }

    void registerStudent(String studentNo, String name, String password, String roleLevel) throws SQLException {
        if (studentNo.isBlank() || name.isBlank() || password.isBlank()) {
            throw new SQLException("學號、姓名、密碼不可空白");
        }
        String sql = "INSERT INTO users (student_no, name, password_hash, role_level) VALUES (?, ?, ?, ?)";
        try (Connection conn = Db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, studentNo.trim());
            ps.setString(2, name.trim());
            ps.setString(3, Passwords.sha256(password));
            ps.setString(4, roleLevel);
            ps.executeUpdate();
        }
    }
}

final class LibraryService {
    List<Book> searchBooks(String keyword) throws SQLException {
        return searchBooks(keyword, false);
    }

    List<Book> searchBooks(String keyword, boolean includeArchived) throws SQLException {
        String sql = """
                SELECT b.book_id, b.title, b.authors, b.subjects, b.publisher, b.publish_year,
                       b.total_copies, b.available_copies, b.status,
                       COALESCE(GROUP_CONCAT(i.isbn ORDER BY i.isbn SEPARATOR ', '), '') AS isbns
                FROM books b
                LEFT JOIN book_isbns i ON b.book_id = i.book_id
                WHERE (? OR b.status = 'AVAILABLE')
                  AND (? = ''
                   OR b.title LIKE ?
                   OR b.authors LIKE ?
                   OR b.subjects LIKE ?
                   OR b.publisher LIKE ?
                   OR i.isbn LIKE ?)
                GROUP BY b.book_id
                ORDER BY b.title
                """;
        String like = "%" + keyword.trim() + "%";
        try (Connection conn = Db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, includeArchived);
            ps.setString(2, keyword.trim());
            for (int i = 3; i <= 7; i++) {
                ps.setString(i, like);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<Book> books = new ArrayList<>();
                while (rs.next()) {
                    books.add(new Book(rs.getInt("book_id"), rs.getString("title"), rs.getString("authors"),
                            rs.getString("subjects"), rs.getString("publisher"), rs.getString("publish_year"),
                            rs.getString("isbns"), rs.getInt("total_copies"), rs.getInt("available_copies"),
                            rs.getString("status")));
                }
                return books;
            }
        }
    }

    void borrowBook(int userId, int bookId, int days) throws SQLException {
        if (days != 1 && days != 3 && days != 7 && days != 14) {
            throw new SQLException("借閱天數只能選擇 1、3、7、14 天");
        }
        try (Connection conn = Db.connect()) {
            conn.setAutoCommit(false);
            try {
                int available;
                String status;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT available_copies, status FROM books WHERE book_id = ? FOR UPDATE")) {
                    ps.setInt(1, bookId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("找不到書籍");
                        }
                        available = rs.getInt("available_copies");
                        status = rs.getString("status");
                    }
                }
                if (!"AVAILABLE".equals(status)) {
                    throw new SQLException("此書已下架，無法借閱");
                }
                if (available <= 0) {
                    throw new SQLException("此書目前沒有可借複本");
                }
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO borrow_records (user_id, book_id, due_date, borrow_days)
                        VALUES (?, ?, DATE_ADD(NOW(), INTERVAL ? DAY), ?)
                        """)) {
                    ps.setInt(1, userId);
                    ps.setInt(2, bookId);
                    ps.setInt(3, days);
                    ps.setInt(4, days);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE books SET available_copies = available_copies - 1 WHERE book_id = ?")) {
                    ps.setInt(1, bookId);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    void returnBook(int userId, int recordId) throws SQLException {
        try (Connection conn = Db.connect()) {
            conn.setAutoCommit(false);
            try {
                int bookId;
                try (PreparedStatement ps = conn.prepareStatement("""
                        SELECT book_id FROM borrow_records
                        WHERE record_id = ? AND user_id = ? AND return_date IS NULL
                        FOR UPDATE
                        """)) {
                    ps.setInt(1, recordId);
                    ps.setInt(2, userId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("找不到可歸還的借閱紀錄");
                        }
                        bookId = rs.getInt("book_id");
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE borrow_records SET return_date = NOW() WHERE record_id = ?")) {
                    ps.setInt(1, recordId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE books SET available_copies = available_copies + 1 WHERE book_id = ?")) {
                    ps.setInt(1, bookId);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    void returnBookForAdmin(int recordId) throws SQLException {
        try (Connection conn = Db.connect()) {
            conn.setAutoCommit(false);
            try {
                int bookId;
                try (PreparedStatement ps = conn.prepareStatement("""
                        SELECT book_id FROM borrow_records
                        WHERE record_id = ? AND return_date IS NULL
                        FOR UPDATE
                        """)) {
                    ps.setInt(1, recordId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("找不到可歸還的借閱紀錄");
                        }
                        bookId = rs.getInt("book_id");
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE borrow_records SET return_date = NOW() WHERE record_id = ?")) {
                    ps.setInt(1, recordId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("""
                        UPDATE books
                        SET available_copies = CASE
                            WHEN status = 'AVAILABLE' THEN available_copies + 1
                            ELSE available_copies
                        END
                        WHERE book_id = ?
                        """)) {
                    ps.setInt(1, bookId);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    List<BorrowRow> borrowRows(Integer userId) throws SQLException {
        String filter = userId == null ? "" : "WHERE r.user_id = ?";
        String sql = """
                SELECT r.record_id, u.student_no, u.name, b.title, r.borrow_date, r.due_date,
                       r.return_date, r.borrow_days
                FROM borrow_records r
                JOIN users u ON r.user_id = u.user_id
                JOIN books b ON r.book_id = b.book_id
                %s
                ORDER BY r.borrow_date DESC
                """.formatted(filter);
        try (Connection conn = Db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            if (userId != null) {
                ps.setInt(1, userId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<BorrowRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new BorrowRow(rs.getInt("record_id"), rs.getString("student_no"),
                            rs.getString("name"), rs.getString("title"), rs.getTimestamp("borrow_date"),
                            rs.getTimestamp("due_date"), rs.getTimestamp("return_date"), rs.getInt("borrow_days")));
                }
                return rows;
            }
        }
    }

    void addBook(String title, String authors, String subjects, String publisher, String year, String isbn, int copies)
            throws SQLException {
        if (title.isBlank() || authors.isBlank() || copies <= 0) {
            throw new SQLException("書名、作者必填，館藏數量需大於 0");
        }
        try (Connection conn = Db.connect()) {
            conn.setAutoCommit(false);
            try {
                int bookId;
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO books (title, authors, subjects, publisher, publish_year, total_copies, available_copies, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'AVAILABLE')
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, title.trim());
                    ps.setString(2, authors.trim());
                    ps.setString(3, subjects.trim());
                    ps.setString(4, publisher.trim());
                    ps.setString(5, year.trim());
                    ps.setInt(6, copies);
                    ps.setInt(7, copies);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("新增書籍失敗，無法取得書籍 ID");
                        }
                        bookId = keys.getInt(1);
                    }
                }
                insertIsbns(conn, bookId, isbn);
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    void updateBook(int bookId, String title, String authors, String subjects, String publisher, String year,
                    String isbn, int copies) throws SQLException {
        if (title.isBlank() || authors.isBlank() || copies <= 0) {
            throw new SQLException("書名、作者必填，館藏數量需大於 0");
        }
        try (Connection conn = Db.connect()) {
            conn.setAutoCommit(false);
            try {
                String status;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT status FROM books WHERE book_id = ? FOR UPDATE")) {
                    ps.setInt(1, bookId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("找不到書籍");
                        }
                        status = rs.getString("status");
                    }
                }
                int activeLoans = activeLoanCount(conn, bookId);
                if (copies < activeLoans) {
                    throw new SQLException("館藏數量不可小於目前借出中的複本數：" + activeLoans);
                }
                int newAvailable = "ARCHIVED".equals(status) ? 0 : copies - activeLoans;
                try (PreparedStatement ps = conn.prepareStatement("""
                        UPDATE books
                        SET title = ?, authors = ?, subjects = ?, publisher = ?, publish_year = ?,
                            total_copies = ?, available_copies = ?
                        WHERE book_id = ?
                        """)) {
                    ps.setString(1, title.trim());
                    ps.setString(2, authors.trim());
                    ps.setString(3, subjects.trim());
                    ps.setString(4, publisher.trim());
                    ps.setString(5, year.trim());
                    ps.setInt(6, copies);
                    ps.setInt(7, newAvailable);
                    ps.setInt(8, bookId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM book_isbns WHERE book_id = ?")) {
                    ps.setInt(1, bookId);
                    ps.executeUpdate();
                }
                insertIsbns(conn, bookId, isbn);
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    void archiveBook(int bookId) throws SQLException {
        try (Connection conn = Db.connect()) {
            conn.setAutoCommit(false);
            try {
                String status;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT status FROM books WHERE book_id = ? FOR UPDATE")) {
                    ps.setInt(1, bookId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new SQLException("找不到書籍");
                        }
                        status = rs.getString("status");
                    }
                }
                if ("ARCHIVED".equals(status)) {
                    throw new SQLException("此書已經下架");
                }
                int activeLoans = activeLoanCount(conn, bookId);
                if (activeLoans > 0) {
                    throw new SQLException("仍有 " + activeLoans + " 本借出中，無法下架");
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE books SET status = 'ARCHIVED', available_copies = 0 WHERE book_id = ?")) {
                    ps.setInt(1, bookId);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    void restoreBook(int bookId) throws SQLException {
        try (Connection conn = Db.connect()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT status FROM books WHERE book_id = ? FOR UPDATE")) {
                    ps.setInt(1, bookId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || !"ARCHIVED".equals(rs.getString("status"))) {
                            throw new SQLException("找不到已下架的書籍");
                        }
                    }
                }
                int activeLoans = activeLoanCount(conn, bookId);
                try (PreparedStatement ps = conn.prepareStatement("""
                        UPDATE books
                        SET status = 'AVAILABLE', available_copies = total_copies - ?
                        WHERE book_id = ?
                        """)) {
                    ps.setInt(1, activeLoans);
                    ps.setInt(2, bookId);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    void updateUserStatus(String studentNo, String status) throws SQLException {
        if (!"ACTIVE".equals(status) && !"SUSPENDED".equals(status)) {
            throw new SQLException("帳號狀態不正確");
        }
        try (Connection conn = Db.connect(); PreparedStatement ps = conn.prepareStatement(
                "UPDATE users SET status = ? WHERE student_no = ?")) {
            ps.setString(1, status);
            ps.setString(2, studentNo.trim());
            if (ps.executeUpdate() == 0) {
                throw new SQLException("找不到學生帳號");
            }
        }
    }

    List<UserRow> searchUsers(String keyword) throws SQLException {
        String sql = """
                SELECT u.user_id, u.student_no, u.name, u.role_level, u.status, u.created_at,
                       COALESCE(SUM(CASE WHEN r.record_id IS NOT NULL AND r.return_date IS NULL THEN 1 ELSE 0 END), 0) AS active_loans,
                       COUNT(r.record_id) AS total_loans
                FROM users u
                LEFT JOIN borrow_records r ON u.user_id = r.user_id
                WHERE (? = ''
                   OR u.student_no LIKE ?
                   OR u.name LIKE ?
                   OR u.role_level LIKE ?
                   OR u.status LIKE ?)
                GROUP BY u.user_id
                ORDER BY u.student_no
                """;
        String trimmed = keyword.trim();
        String like = "%" + trimmed + "%";
        try (Connection conn = Db.connect(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, trimmed);
            for (int i = 2; i <= 5; i++) {
                ps.setString(i, like);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<UserRow> users = new ArrayList<>();
                while (rs.next()) {
                    users.add(new UserRow(rs.getInt("user_id"), rs.getString("student_no"), rs.getString("name"),
                            rs.getString("role_level"), rs.getString("status"), rs.getTimestamp("created_at"),
                            rs.getInt("active_loans"), rs.getInt("total_loans")));
                }
                return users;
            }
        }
    }

    AdminStats adminStats() throws SQLException {
        String sql = """
                SELECT
                    (SELECT COUNT(*) FROM books) AS total_books,
                    (SELECT COUNT(*) FROM books WHERE status = 'AVAILABLE') AS available_books,
                    (SELECT COUNT(*) FROM books WHERE status = 'ARCHIVED') AS archived_books,
                    (SELECT COUNT(*) FROM borrow_records WHERE return_date IS NULL) AS active_loans,
                    (SELECT COUNT(*) FROM borrow_records WHERE return_date IS NULL AND due_date < NOW()) AS overdue_loans,
                    (SELECT COUNT(*) FROM users WHERE status = 'SUSPENDED') AS suspended_users
                """;
        try (Connection conn = Db.connect(); PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return new AdminStats(rs.getInt("total_books"), rs.getInt("available_books"),
                    rs.getInt("archived_books"), rs.getInt("active_loans"), rs.getInt("overdue_loans"),
                    rs.getInt("suspended_users"));
        }
    }

    private void insertIsbns(Connection conn, int bookId, String isbnText) throws SQLException {
        List<String> isbns = normalizeIsbns(isbnText);
        if (isbns.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO book_isbns (book_id, isbn) VALUES (?, ?)")) {
            for (String isbn : isbns) {
                ps.setInt(1, bookId);
                ps.setString(2, isbn);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private int activeLoanCount(Connection conn, int bookId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM borrow_records WHERE book_id = ? AND return_date IS NULL")) {
            ps.setInt(1, bookId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private List<String> normalizeIsbns(String isbnText) {
        Set<String> unique = new LinkedHashSet<>();
        if (isbnText != null) {
            for (String raw : isbnText.split("[,;\\r\\n]+")) {
                String isbn = raw.trim();
                if (!isbn.isBlank()) {
                    unique.add(isbn);
                }
            }
        }
        return new ArrayList<>(unique);
    }
}

final class MainFrame extends JFrame {
    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private final AuthService authService = new AuthService();
    private final LibraryService libraryService = new LibraryService();

    MainFrame() {
        super("圖書館借還書系統");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(1100, 720);
        setLocationRelativeTo(null);
        root.add(new LoginPanel(this, authService), "login");
        setContentPane(root);
        showLogin();
    }

    void showLogin() {
        cards.show(root, "login");
    }

    void openSession(LoginSession session) {
        if (session.type() == UserType.STUDENT) {
            root.add(new StudentPanel(this, session, libraryService), "student");
            cards.show(root, "student");
        } else {
            root.add(new AdminPanel(this, session, libraryService), "admin");
            cards.show(root, "admin");
        }
    }
}

final class LoginPanel extends JPanel {
    LoginPanel(MainFrame frame, AuthService authService) {
        setLayout(new GridBagLayout());
        setBorder(new EmptyBorder(24, 24, 24, 24));
        JPanel box = new JPanel(new GridBagLayout());
        box.setBorder(BorderFactory.createCompoundBorder(new PixelBorder(Ui.STONE_DARK, Ui.GOLD, 3),
                new EmptyBorder(24, 28, 24, 28)));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(8, 8, 8, 8);
        c.fill = GridBagConstraints.HORIZONTAL;

        JLabel title = new JLabel("圖書館借還書系統", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        box.add(title, c);

        JTextField account = new JTextField(20);
        JPasswordField password = new JPasswordField(20);
        JComboBox<String> type = new JComboBox<>(new String[]{"學生", "管理員"});
        addRow(box, c, 1, "帳號", account);
        addRow(box, c, 2, "密碼", password);
        addRow(box, c, 3, "身分", type);

        JButton login = new JButton("登入");
        JButton register = new JButton("學生註冊");
        JPanel buttons = new JPanel(new GridLayout(1, 2, 8, 0));
        buttons.add(login);
        buttons.add(register);
        c.gridy = 4;
        c.gridx = 0;
        c.gridwidth = 2;
        box.add(buttons, c);

        login.addActionListener(e -> {
            try {
                String pwd = new String(password.getPassword());
                LoginSession session = type.getSelectedIndex() == 0
                        ? authService.loginStudent(account.getText(), pwd)
                        : authService.loginAdmin(account.getText(), pwd);
                frame.openSession(session);
            } catch (SQLException ex) {
                Dialogs.error(this, ex);
            }
        });
        register.addActionListener(e -> new RegisterDialog(frame, authService).setVisible(true));
        add(box);
        Ui.styleTree(this);
    }

    private void addRow(JPanel box, GridBagConstraints c, int row, String label, JComponent input) {
        c.gridwidth = 1;
        c.gridy = row;
        c.gridx = 0;
        c.weightx = 0;
        box.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        box.add(input, c);
    }
}

final class RegisterDialog extends JDialog {
    RegisterDialog(JFrame owner, AuthService authService) {
        super(owner, "學生註冊", true);
        JPanel form = Ui.dialogPanel(5, 2);
        setContentPane(form);
        JTextField studentNo = new JTextField();
        JTextField name = new JTextField();
        JPasswordField password = new JPasswordField();
        JComboBox<String> role = new JComboBox<>(new String[]{"NORMAL", "VIP"});
        form.add(new JLabel("學號"));
        form.add(studentNo);
        form.add(new JLabel("姓名"));
        form.add(name);
        form.add(new JLabel("密碼"));
        form.add(password);
        form.add(new JLabel("等級"));
        form.add(role);
        JButton ok = new JButton("註冊");
        JButton cancel = new JButton("取消");
        form.add(ok);
        form.add(cancel);
        ok.addActionListener(e -> {
            try {
                authService.registerStudent(studentNo.getText(), name.getText(), new String(password.getPassword()),
                        (String) role.getSelectedItem());
                Dialogs.info(this, "註冊成功");
                dispose();
            } catch (SQLException ex) {
                Dialogs.error(this, ex);
            }
        });
        cancel.addActionListener(e -> dispose());
        Ui.styleTree(form);
        pack();
        setLocationRelativeTo(owner);
    }
}

final class StudentPanel extends JPanel {
    private final LoginSession session;
    private final LibraryService service;
    private final DefaultTableModel booksModel = Ui.model("ID", "書名", "作者", "主題", "出版社", "年份", "ISBN", "館藏", "可借");
    private final DefaultTableModel recordsModel = Ui.model("紀錄ID", "書名", "借出時間", "到期時間", "歸還時間", "天數", "狀態");
    private final JTable booksTable = new JTable(booksModel);
    private final JTable recordsTable = new JTable(recordsModel);
    private final JTextField keyword = new JTextField();

    StudentPanel(MainFrame frame, LoginSession session, LibraryService service) {
        this.session = session;
        this.service = service;
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(0, 0, 0, 0));

        CardLayout contentLayout = new CardLayout();
        JPanel content = new JPanel(contentLayout);
        content.add(bookTab(), "books");
        content.add(recordTab(), "records");

        JPanel sidebar = Ui.sidebar("圖書館", "學生模式");
        JLabel player = Ui.sidebarInfo(session.displayName(), session.roleLevel());
        JButton books = Ui.navButton("查詢借書");
        JButton records = Ui.navButton("借閱紀錄");
        JButton logout = Ui.navButton("登出");
        books.addActionListener(e -> contentLayout.show(content, "books"));
        records.addActionListener(e -> {
            refreshRecords();
            contentLayout.show(content, "records");
        });
        logout.addActionListener(e -> frame.showLogin());
        sidebar.add(player);
        sidebar.add(Box.createVerticalStrut(18));
        sidebar.add(books);
        sidebar.add(Box.createVerticalStrut(10));
        sidebar.add(records);
        sidebar.add(Box.createVerticalGlue());
        sidebar.add(logout);

        add(sidebar, BorderLayout.WEST);
        add(content, BorderLayout.CENTER);
        Ui.styleTree(this);
        Ui.styleTable(booksTable);
        Ui.styleTable(recordsTable);
        refreshBooks();
        refreshRecords();
    }

    private JPanel bookTab() {
        JPanel panel = Ui.page("查詢與借書", "搜尋館藏、選擇借閱天數，按下借書即可建立紀錄。");
        JPanel topStack = new JPanel(new BorderLayout(0, 10));
        JPanel top = Ui.toolbar();
        JLabel searchLabel = new JLabel("關鍵字");
        JButton search = new JButton("查詢");
        JButton borrow = new JButton("借書");
        JComboBox<Integer> days = new JComboBox<>(new Integer[]{1, 3, 7, 14});
        JPanel actions = Ui.actionBar();
        actions.add(new JLabel("天數"));
        actions.add(days);
        actions.add(borrow);
        top.add(searchLabel, BorderLayout.WEST);
        top.add(keyword, BorderLayout.CENTER);
        top.add(search, BorderLayout.EAST);
        topStack.add(Ui.pageHeader("查詢與借書", "搜尋館藏、選擇借閱天數，按下借書即可建立紀錄。"), BorderLayout.NORTH);
        topStack.add(top, BorderLayout.SOUTH);
        panel.add(topStack, BorderLayout.NORTH);
        panel.add(new JScrollPane(booksTable), BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        search.addActionListener(e -> refreshBooks());
        borrow.addActionListener(e -> {
            Integer bookId = Ui.selectedInt(booksTable, 0);
            if (bookId == null) {
                return;
            }
            try {
                service.borrowBook(session.id(), bookId, (Integer) days.getSelectedItem());
                refreshBooks();
                refreshRecords();
                Dialogs.info(this, "借書成功");
            } catch (SQLException ex) {
                Dialogs.error(this, ex);
            }
        });
        return panel;
    }

    private JPanel recordTab() {
        JPanel panel = Ui.page("我的借閱紀錄", "查看借出、到期與歸還狀態；即將到期或逾期會在狀態欄標示。");
        panel.add(Ui.pageHeader("我的借閱紀錄", "查看借出、到期與歸還狀態；即將到期或逾期會在狀態欄標示。"), BorderLayout.NORTH);
        JButton refresh = new JButton("重新整理");
        JButton returns = new JButton("還書");
        JPanel actions = Ui.actionBar();
        actions.add(refresh);
        actions.add(returns);
        panel.add(new JScrollPane(recordsTable), BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        refresh.addActionListener(e -> refreshRecords());
        returns.addActionListener(e -> {
            Integer recordId = Ui.selectedInt(recordsTable, 0);
            if (recordId == null) {
                return;
            }
            try {
                service.returnBook(session.id(), recordId);
                refreshBooks();
                refreshRecords();
                Dialogs.info(this, "還書成功");
            } catch (SQLException ex) {
                Dialogs.error(this, ex);
            }
        });
        return panel;
    }

    private void refreshBooks() {
        try {
            booksModel.setRowCount(0);
            for (Book b : service.searchBooks(keyword.getText())) {
                booksModel.addRow(new Object[]{b.id(), b.title(), b.authors(), b.subjects(), b.publisher(),
                        b.publishYear(), b.isbnList(), b.totalCopies(), b.availableCopies()});
            }
        } catch (SQLException ex) {
            Dialogs.error(this, ex);
        }
    }

    private void refreshRecords() {
        try {
            recordsModel.setRowCount(0);
            for (BorrowRow r : service.borrowRows(session.id())) {
                recordsModel.addRow(new Object[]{r.recordId(), r.title(), r.borrowDate(), r.dueDate(),
                        r.returnDate(), r.borrowDays(), r.statusText()});
            }
        } catch (SQLException ex) {
            Dialogs.error(this, ex);
        }
    }
}

final class AdminPanel extends JPanel {
    private final LibraryService service;
    private final DefaultTableModel booksModel = Ui.model("ID", "書名", "作者", "主題", "出版社", "年份", "ISBN", "館藏", "可借", "狀態");
    private final DefaultTableModel recordsModel = Ui.model("紀錄ID", "學號", "姓名", "書名", "借出時間", "到期時間", "歸還時間", "天數", "狀態");
    private final DefaultTableModel usersModel = Ui.model("ID", "學號", "姓名", "身分", "狀態", "借閱中", "歷史借閱", "建立時間");
    private final JTable booksTable = new JTable(booksModel);
    private final JTable recordsTable = new JTable(recordsModel);
    private final JTable usersTable = new JTable(usersModel);
    private final JTextField keyword = new JTextField();
    private final JTextField userKeyword = new JTextField();
    private final List<Book> currentBooks = new ArrayList<>();
    private final JLabel totalBooksValue = new JLabel("-");
    private final JLabel availableBooksValue = new JLabel("-");
    private final JLabel archivedBooksValue = new JLabel("-");
    private final JLabel activeLoansValue = new JLabel("-");
    private final JLabel overdueLoansValue = new JLabel("-");
    private final JLabel suspendedUsersValue = new JLabel("-");

    AdminPanel(MainFrame frame, LoginSession session, LibraryService service) {
        this.service = service;
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(0, 0, 0, 0));

        CardLayout contentLayout = new CardLayout();
        JPanel content = new JPanel(contentLayout);
        content.add(dashboardTab(), "dashboard");
        content.add(booksTab(), "books");
        content.add(recordsTab(), "records");
        content.add(usersTab(), "users");

        JPanel sidebar = Ui.sidebar("管理後台", "館員模式");
        sidebar.add(Ui.sidebarInfo(session.displayName(), "ADMIN"));
        JButton dashboard = Ui.navButton("管理首頁");
        JButton books = Ui.navButton("書籍管理");
        JButton records = Ui.navButton("借閱紀錄");
        JButton users = Ui.navButton("帳號狀態");
        JButton logout = Ui.navButton("登出");
        dashboard.addActionListener(e -> {
            refreshDashboard();
            contentLayout.show(content, "dashboard");
        });
        books.addActionListener(e -> contentLayout.show(content, "books"));
        records.addActionListener(e -> {
            refreshRecords();
            contentLayout.show(content, "records");
        });
        users.addActionListener(e -> {
            refreshUsers();
            contentLayout.show(content, "users");
        });
        logout.addActionListener(e -> frame.showLogin());
        sidebar.add(Box.createVerticalStrut(18));
        sidebar.add(dashboard);
        sidebar.add(Box.createVerticalStrut(10));
        sidebar.add(books);
        sidebar.add(Box.createVerticalStrut(10));
        sidebar.add(records);
        sidebar.add(Box.createVerticalStrut(10));
        sidebar.add(users);
        sidebar.add(Box.createVerticalGlue());
        sidebar.add(logout);

        add(sidebar, BorderLayout.WEST);
        add(content, BorderLayout.CENTER);
        Ui.styleTree(this);
        Ui.styleTable(booksTable);
        Ui.styleTable(recordsTable);
        Ui.styleTable(usersTable);
        refreshDashboard();
        refreshBooks();
        refreshRecords();
        refreshUsers();
    }

    private JPanel dashboardTab() {
        JPanel panel = Ui.page("管理首頁", "查看目前館藏、借閱與帳號狀態。");
        panel.add(Ui.pageHeader("管理首頁", "查看目前館藏、借閱與帳號狀態。"), BorderLayout.NORTH);
        JPanel stats = new JPanel(new GridLayout(2, 3, 14, 14));
        stats.add(statCard("館藏書目", totalBooksValue));
        stats.add(statCard("可借書目", availableBooksValue));
        stats.add(statCard("已下架", archivedBooksValue));
        stats.add(statCard("借閱中", activeLoansValue));
        stats.add(statCard("逾期中", overdueLoansValue));
        stats.add(statCard("停權學生", suspendedUsersValue));
        JButton refresh = new JButton("重新整理");
        JPanel actions = Ui.actionBar();
        actions.add(refresh);
        panel.add(stats, BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        refresh.addActionListener(e -> refreshDashboard());
        return panel;
    }

    private JPanel statCard(String title, JLabel value) {
        JPanel card = new JPanel(new BorderLayout(0, 10));
        card.setBorder(BorderFactory.createCompoundBorder(
                new PixelBorder(Ui.STONE_DARK, Ui.GOLD, 3),
                new EmptyBorder(18, 18, 18, 18)));
        JLabel label = new JLabel(title);
        label.setFont(new Font("Microsoft JhengHei UI", Font.BOLD, 16));
        value.setFont(new Font("Microsoft JhengHei UI", Font.BOLD, 36));
        card.add(label, BorderLayout.NORTH);
        card.add(value, BorderLayout.CENTER);
        return card;
    }

    private JPanel booksTab() {
        JPanel panel = Ui.page("書籍管理", "查詢館藏、新增書籍，或下架沒有借出中的書。");
        JPanel topStack = new JPanel(new BorderLayout(0, 10));
        JPanel searchRow = Ui.toolbar();
        JLabel searchLabel = new JLabel("關鍵字");
        JButton search = new JButton("查詢");
        JButton add = new JButton("新增書籍");
        JButton edit = new JButton("編輯書籍");
        JButton archive = new JButton("下架書籍");
        JButton restore = new JButton("恢復上架");
        JPanel buttons = Ui.actionBar();
        buttons.add(add);
        buttons.add(edit);
        buttons.add(archive);
        buttons.add(restore);
        searchRow.add(searchLabel, BorderLayout.WEST);
        searchRow.add(keyword, BorderLayout.CENTER);
        searchRow.add(search, BorderLayout.EAST);
        topStack.add(Ui.pageHeader("書籍管理", "查詢館藏、新增書籍，或下架沒有借出中的書。"), BorderLayout.NORTH);
        topStack.add(searchRow, BorderLayout.SOUTH);
        panel.add(topStack, BorderLayout.NORTH);
        panel.add(new JScrollPane(booksTable), BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        search.addActionListener(e -> refreshBooks());
        add.addActionListener(e -> new AddBookDialog(SwingUtilities.getWindowAncestor(this), service, this::refreshBookViews).setVisible(true));
        edit.addActionListener(e -> {
            Book book = selectedBook();
            if (book == null) {
                return;
            }
            new EditBookDialog(SwingUtilities.getWindowAncestor(this), service, book, this::refreshBookViews).setVisible(true);
        });
        archive.addActionListener(e -> {
            Book book = selectedBook();
            if (book == null) {
                return;
            }
            try {
                service.archiveBook(book.id());
                refreshBookViews();
                Dialogs.info(this, "下架成功");
            } catch (SQLException ex) {
                Dialogs.error(this, ex);
            }
        });
        restore.addActionListener(e -> {
            Book book = selectedBook();
            if (book == null) {
                return;
            }
            try {
                service.restoreBook(book.id());
                refreshBookViews();
                Dialogs.info(this, "恢復上架成功");
            } catch (SQLException ex) {
                Dialogs.error(this, ex);
            }
        });
        return panel;
    }

    private JPanel recordsTab() {
        JPanel panel = Ui.page("借閱紀錄查詢", "檢視所有學生的借閱、歸還與逾期狀態。");
        panel.add(Ui.pageHeader("借閱紀錄查詢", "檢視所有學生的借閱、歸還與逾期狀態。"), BorderLayout.NORTH);
        JButton refresh = new JButton("重新整理");
        JButton returns = new JButton("登記還書");
        JPanel actions = Ui.actionBar();
        actions.add(refresh);
        actions.add(returns);
        panel.add(new JScrollPane(recordsTable), BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        refresh.addActionListener(e -> refreshRecords());
        returns.addActionListener(e -> {
            Integer recordId = Ui.selectedInt(recordsTable, 0);
            if (recordId == null) {
                return;
            }
            try {
                service.returnBookForAdmin(recordId);
                refreshRecords();
                refreshBookViews();
                Dialogs.info(this, "登記還書成功");
            } catch (SQLException ex) {
                Dialogs.error(this, ex);
            }
        });
        return panel;
    }

    private JPanel usersTab() {
        JPanel panel = Ui.page("帳號狀態管理", "搜尋學生帳號，查看借閱概況並停權或復權。");
        JPanel topStack = new JPanel(new BorderLayout(0, 10));
        JPanel searchRow = Ui.toolbar();
        JLabel searchLabel = new JLabel("關鍵字");
        JButton search = new JButton("查詢");
        JButton suspend = new JButton("停權");
        JButton activate = new JButton("復權");
        JPanel actions = Ui.actionBar();
        actions.add(suspend);
        actions.add(activate);
        searchRow.add(searchLabel, BorderLayout.WEST);
        searchRow.add(userKeyword, BorderLayout.CENTER);
        searchRow.add(search, BorderLayout.EAST);
        topStack.add(Ui.pageHeader("帳號狀態管理", "搜尋學生帳號，查看借閱概況並停權或復權。"), BorderLayout.NORTH);
        topStack.add(searchRow, BorderLayout.SOUTH);
        panel.add(topStack, BorderLayout.NORTH);
        panel.add(new JScrollPane(usersTable), BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        search.addActionListener(e -> refreshUsers());
        suspend.addActionListener(e -> updateSelectedUserStatus("SUSPENDED"));
        activate.addActionListener(e -> updateSelectedUserStatus("ACTIVE"));
        return panel;
    }

    private Book selectedBook() {
        int row = booksTable.getSelectedRow();
        if (row < 0) {
            Dialogs.info(booksTable, "請先選取一筆書籍");
            return null;
        }
        int modelRow = booksTable.convertRowIndexToModel(row);
        return currentBooks.get(modelRow);
    }

    private void updateSelectedUserStatus(String status) {
        String studentNo = Ui.selectedString(usersTable, 1);
        if (studentNo == null) {
            return;
        }
        updateStatus(studentNo, status);
    }

    private void updateStatus(String studentNo, String status) {
        try {
            service.updateUserStatus(studentNo, status);
            refreshUsers();
            refreshDashboard();
            Dialogs.info(this, "更新成功");
        } catch (SQLException ex) {
            Dialogs.error(this, ex);
        }
    }

    private void refreshDashboard() {
        try {
            AdminStats stats = service.adminStats();
            totalBooksValue.setText(String.valueOf(stats.totalBooks()));
            availableBooksValue.setText(String.valueOf(stats.availableBooks()));
            archivedBooksValue.setText(String.valueOf(stats.archivedBooks()));
            activeLoansValue.setText(String.valueOf(stats.activeLoans()));
            overdueLoansValue.setText(String.valueOf(stats.overdueLoans()));
            suspendedUsersValue.setText(String.valueOf(stats.suspendedUsers()));
        } catch (SQLException ex) {
            Dialogs.error(this, ex);
        }
    }

    private void refreshBookViews() {
        refreshBooks();
        refreshDashboard();
    }

    private void refreshBooks() {
        try {
            booksModel.setRowCount(0);
            currentBooks.clear();
            for (Book b : service.searchBooks(keyword.getText(), true)) {
                currentBooks.add(b);
                booksModel.addRow(new Object[]{b.id(), b.title(), b.authors(), b.subjects(), b.publisher(),
                        b.publishYear(), b.isbnList(), b.totalCopies(), b.availableCopies(), bookStatusText(b.status())});
            }
        } catch (SQLException ex) {
            Dialogs.error(this, ex);
        }
    }

    private void refreshRecords() {
        try {
            recordsModel.setRowCount(0);
            for (BorrowRow r : service.borrowRows(null)) {
                recordsModel.addRow(new Object[]{r.recordId(), r.studentNo(), r.studentName(), r.title(),
                        r.borrowDate(), r.dueDate(), r.returnDate(), r.borrowDays(), r.statusText()});
            }
        } catch (SQLException ex) {
            Dialogs.error(this, ex);
        }
    }

    private void refreshUsers() {
        try {
            usersModel.setRowCount(0);
            for (UserRow u : service.searchUsers(userKeyword.getText())) {
                usersModel.addRow(new Object[]{u.id(), u.studentNo(), u.name(), u.roleLevel(),
                        userStatusText(u.status()), u.activeLoans(), u.totalLoans(), u.createdAt()});
            }
        } catch (SQLException ex) {
            Dialogs.error(this, ex);
        }
    }

    private String bookStatusText(String status) {
        return "ARCHIVED".equals(status) ? "已下架" : "可借閱";
    }

    private String userStatusText(String status) {
        return "SUSPENDED".equals(status) ? "停權" : "正常";
    }
}

final class AddBookDialog extends JDialog {
    AddBookDialog(Window owner, LibraryService service, Runnable afterSave) {
        super(owner, "新增書籍", ModalityType.APPLICATION_MODAL);
        JPanel form = Ui.dialogPanel(8, 2);
        setContentPane(form);
        JTextField title = new JTextField();
        JTextField authors = new JTextField();
        JTextField subjects = new JTextField();
        JTextField publisher = new JTextField();
        JTextField year = new JTextField();
        JTextField isbn = new JTextField();
        JSpinner copies = new JSpinner(new SpinnerNumberModel(1, 1, 999, 1));
        form.add(new JLabel("書名"));
        form.add(title);
        form.add(new JLabel("作者"));
        form.add(authors);
        form.add(new JLabel("主題"));
        form.add(subjects);
        form.add(new JLabel("出版社"));
        form.add(publisher);
        form.add(new JLabel("出版年"));
        form.add(year);
        form.add(new JLabel("ISBN（可用逗號分隔）"));
        form.add(isbn);
        form.add(new JLabel("館藏數量"));
        form.add(copies);
        JButton ok = new JButton("儲存");
        JButton cancel = new JButton("取消");
        form.add(ok);
        form.add(cancel);
        ok.addActionListener(e -> {
            try {
                service.addBook(title.getText(), authors.getText(), subjects.getText(), publisher.getText(),
                        year.getText(), isbn.getText(), (Integer) copies.getValue());
                afterSave.run();
                dispose();
            } catch (SQLException ex) {
                Dialogs.error(this, ex);
            }
        });
        cancel.addActionListener(e -> dispose());
        Ui.styleTree(form);
        pack();
        setLocationRelativeTo(owner);
    }
}

final class EditBookDialog extends JDialog {
    EditBookDialog(Window owner, LibraryService service, Book book, Runnable afterSave) {
        super(owner, "編輯書籍", ModalityType.APPLICATION_MODAL);
        JPanel form = Ui.dialogPanel(8, 2);
        setContentPane(form);
        JTextField title = new JTextField(book.title());
        JTextField authors = new JTextField(book.authors());
        JTextField subjects = new JTextField(book.subjects());
        JTextField publisher = new JTextField(book.publisher());
        JTextField year = new JTextField(book.publishYear());
        JTextField isbn = new JTextField(book.isbnList());
        JSpinner copies = new JSpinner(new SpinnerNumberModel(book.totalCopies(), 1, 999, 1));
        form.add(new JLabel("書名"));
        form.add(title);
        form.add(new JLabel("作者"));
        form.add(authors);
        form.add(new JLabel("主題"));
        form.add(subjects);
        form.add(new JLabel("出版社"));
        form.add(publisher);
        form.add(new JLabel("出版年"));
        form.add(year);
        form.add(new JLabel("ISBN（可用逗號分隔）"));
        form.add(isbn);
        form.add(new JLabel("館藏數量"));
        form.add(copies);
        JButton ok = new JButton("儲存");
        JButton cancel = new JButton("取消");
        form.add(ok);
        form.add(cancel);
        ok.addActionListener(e -> {
            try {
                service.updateBook(book.id(), title.getText(), authors.getText(), subjects.getText(),
                        publisher.getText(), year.getText(), isbn.getText(), (Integer) copies.getValue());
                afterSave.run();
                dispose();
            } catch (SQLException ex) {
                Dialogs.error(this, ex);
            }
        });
        cancel.addActionListener(e -> dispose());
        Ui.styleTree(form);
        pack();
        setLocationRelativeTo(owner);
    }
}

final class Dialogs {
    private Dialogs() {
    }

    static void error(Component parent, Exception ex) {
        show(parent, "錯誤", friendly(ex), true);
    }

    static void info(Component parent, String message) {
        show(parent, "提示", message, false);
    }

    private static String friendly(Exception ex) {
        String message = ex.getMessage();
        if (message != null && message.contains("Duplicate entry") && message.contains("student_no")) {
            return "此學號已被註冊，請改用其他學號或直接登入。";
        }
        return message == null || message.isBlank() ? "操作失敗，請稍後再試。" : message;
    }

    private static void show(Component parent, String title, String message, boolean error) {
        Window owner = SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        JPanel panel = new JPanel(new BorderLayout(14, 14));
        panel.setBackground(Ui.PAPER);
        panel.setBorder(BorderFactory.createCompoundBorder(
                new PixelBorder(error ? new Color(114, 37, 36) : Ui.WOOD_DARK, Ui.GOLD, 3),
                new EmptyBorder(16, 18, 16, 18)));

        JLabel heading = new JLabel(title);
        heading.setForeground(error ? new Color(128, 35, 35) : Ui.WOOD_DARK);
        heading.setFont(new Font("Microsoft JhengHei UI", Font.BOLD, 20));

        JTextArea text = new JTextArea(message);
        text.setEditable(false);
        text.setWrapStyleWord(true);
        text.setLineWrap(true);
        text.setOpaque(false);
        text.setForeground(Ui.INK);
        text.setFont(new Font("Microsoft JhengHei UI", Font.BOLD, 16));
        text.setColumns(34);

        JButton ok = new JButton("OK");
        ok.addActionListener(e -> dialog.dispose());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.setOpaque(false);
        actions.add(ok);

        panel.add(heading, BorderLayout.NORTH);
        panel.add(text, BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(panel);
        Ui.styleTree(actions);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
    }
}

final class Ui {
    static final Color NIGHT = new Color(26, 31, 42);
    static final Color STONE = new Color(69, 76, 91);
    static final Color STONE_DARK = new Color(35, 40, 52);
    static final Color STONE_LIGHT = new Color(129, 141, 158);
    static final Color GRASS = new Color(70, 122, 63);
    static final Color WOOD = new Color(126, 74, 40);
    static final Color WOOD_DARK = new Color(78, 45, 29);
    static final Color GOLD = new Color(235, 190, 82);
    static final Color PAPER = new Color(246, 231, 184);
    static final Color INK = new Color(34, 29, 25);
    static final Color CREAM = new Color(255, 241, 202);
    static final Color TABLE_BG = new Color(21, 26, 36);
    static final Color TABLE_ROW_ALT = new Color(30, 36, 48);
    static final Color TABLE_GRID = new Color(88, 98, 116);

    private Ui() {
    }

    static void installTheme() {
        Font base = new Font("Microsoft JhengHei UI", Font.BOLD, 16);
        UIManager.put("Panel.background", NIGHT);
        UIManager.put("Viewport.background", TABLE_BG);
        UIManager.put("Label.font", base);
        UIManager.put("Label.foreground", CREAM);
        UIManager.put("Button.font", base);
        UIManager.put("Button.foreground", CREAM);
        UIManager.put("Button.background", WOOD);
        UIManager.put("TextField.font", base);
        UIManager.put("TextField.foreground", INK);
        UIManager.put("TextField.background", PAPER);
        UIManager.put("PasswordField.font", base);
        UIManager.put("PasswordField.foreground", INK);
        UIManager.put("PasswordField.background", PAPER);
        UIManager.put("ComboBox.font", base);
        UIManager.put("ComboBox.foreground", INK);
        UIManager.put("ComboBox.background", PAPER);
        UIManager.put("Table.font", new Font("Microsoft JhengHei UI", Font.PLAIN, 16));
        UIManager.put("Table.foreground", CREAM);
        UIManager.put("Table.background", TABLE_BG);
        UIManager.put("Table.selectionForeground", Color.WHITE);
        UIManager.put("Table.selectionBackground", GRASS);
        UIManager.put("TableHeader.font", base);
        UIManager.put("TableHeader.foreground", CREAM);
        UIManager.put("TableHeader.background", WOOD_DARK);
        UIManager.put("OptionPane.background", PAPER);
        UIManager.put("OptionPane.foreground", INK);
        UIManager.put("OptionPane.messageForeground", INK);
        UIManager.put("OptionPane.messageFont", base);
        UIManager.put("OptionPane.buttonFont", base);
    }

    static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    static JPanel sidebar(String title, String subtitle) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setPreferredSize(new Dimension(220, 1));
        panel.setBorder(BorderFactory.createCompoundBorder(
                new PixelBorder(WOOD_DARK, GOLD, 4),
                new EmptyBorder(18, 16, 18, 16)));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleLabel.setFont(new Font("Microsoft JhengHei UI", Font.BOLD, 28));
        JLabel subtitleLabel = new JLabel(subtitle);
        subtitleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        subtitleLabel.setFont(new Font("Microsoft JhengHei UI", Font.BOLD, 15));

        panel.add(titleLabel);
        panel.add(Box.createVerticalStrut(4));
        panel.add(subtitleLabel);
        panel.add(Box.createVerticalStrut(20));
        return panel;
    }

    static JLabel sidebarInfo(String name, String role) {
        JLabel label = new JLabel("<html><b>" + name + "</b><br/>" + role + "</html>");
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createCompoundBorder(
                new PixelBorder(STONE_DARK, STONE_LIGHT, 2),
                new EmptyBorder(10, 10, 10, 10)));
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, 68));
        return label;
    }

    static JButton navButton(String text) {
        JButton button = new JButton(text);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        return button;
    }

    static JPanel page(String title, String subtitle) {
        JPanel panel = new JPanel(new BorderLayout(14, 14));
        panel.setBorder(new EmptyBorder(18, 18, 18, 18));
        return panel;
    }

    static JPanel pageHeader(String title, String subtitle) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(
                new PixelBorder(STONE_DARK, STONE_LIGHT, 3),
                new EmptyBorder(14, 16, 14, 16)));
        JLabel heading = new JLabel(title);
        heading.setFont(new Font("Microsoft JhengHei UI", Font.BOLD, 26));
        JLabel sub = new JLabel(subtitle);
        sub.setFont(new Font("Microsoft JhengHei UI", Font.BOLD, 15));
        panel.add(heading, BorderLayout.NORTH);
        panel.add(sub, BorderLayout.SOUTH);
        return panel;
    }

    static JPanel toolbar() {
        JPanel panel = new JPanel(new BorderLayout(10, 0));
        panel.setBorder(BorderFactory.createCompoundBorder(
                new PixelBorder(WOOD_DARK, GOLD, 3),
                new EmptyBorder(10, 10, 10, 10)));
        return panel;
    }

    static JPanel actionBar() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        panel.setBorder(BorderFactory.createCompoundBorder(
                new PixelBorder(STONE_DARK, STONE_LIGHT, 3),
                new EmptyBorder(2, 8, 2, 8)));
        return panel;
    }

    static JPanel dialogPanel(int rows, int columns) {
        JPanel panel = new JPanel(new GridLayout(rows, columns, 10, 10));
        panel.setBorder(BorderFactory.createCompoundBorder(
                new PixelBorder(STONE_DARK, GOLD, 3),
                new EmptyBorder(18, 18, 18, 18)));
        return panel;
    }

    static Integer selectedInt(JTable table, int column) {
        int row = table.getSelectedRow();
        if (row < 0) {
            Dialogs.info(table, "請先選取一列");
            return null;
        }
        int modelRow = table.convertRowIndexToModel(row);
        return (Integer) table.getModel().getValueAt(modelRow, column);
    }

    static String selectedString(JTable table, int column) {
        int row = table.getSelectedRow();
        if (row < 0) {
            Dialogs.info(table, "請先選取一列");
            return null;
        }
        int modelRow = table.convertRowIndexToModel(row);
        Object value = table.getModel().getValueAt(modelRow, column);
        return value == null ? "" : value.toString();
    }

    static void styleTree(Component component) {
        if (component instanceof JPanel panel) {
            panel.setOpaque(true);
            panel.setBackground(NIGHT);
        }
        if (component instanceof JLabel label) {
            label.setForeground(CREAM);
        }
        if (component instanceof JButton button) {
            button.setUI(new PixelButtonUi());
            button.setFocusPainted(false);
            button.setOpaque(true);
            button.setContentAreaFilled(true);
            button.setForeground(CREAM);
            button.setBackground(WOOD);
            button.setFont(new Font("Microsoft JhengHei UI", Font.BOLD, 16));
            button.setBorder(new PixelBorder(WOOD_DARK, GOLD, 3));
            button.setMargin(new Insets(8, 16, 8, 16));
            button.setPreferredSize(new Dimension(Math.max(116, button.getPreferredSize().width), 44));
        }
        if (component instanceof JTextField field) {
            field.setForeground(INK);
            field.setBackground(PAPER);
            field.setCaretColor(INK);
            field.setBorder(new PixelBorder(STONE_DARK, GOLD, 2));
            field.setMargin(new Insets(8, 10, 8, 10));
            field.setPreferredSize(new Dimension(field.getPreferredSize().width, 42));
        }
        if (component instanceof JPasswordField field) {
            field.setForeground(INK);
            field.setBackground(PAPER);
            field.setCaretColor(INK);
            field.setBorder(new PixelBorder(STONE_DARK, GOLD, 2));
            field.setMargin(new Insets(8, 10, 8, 10));
            field.setPreferredSize(new Dimension(field.getPreferredSize().width, 42));
        }
        if (component instanceof JComboBox<?> combo) {
            combo.setOpaque(true);
            combo.setForeground(INK);
            combo.setBackground(PAPER);
            combo.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                              boolean isSelected, boolean cellHasFocus) {
                    JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                    label.setFont(new Font("Microsoft JhengHei UI", Font.BOLD, 16));
                    label.setForeground(isSelected ? Color.WHITE : INK);
                    label.setBackground(isSelected ? GRASS : PAPER);
                    label.setBorder(new EmptyBorder(6, 8, 6, 8));
                    return label;
                }
            });
            combo.setBorder(new PixelBorder(STONE_DARK, GOLD, 2));
            combo.setPreferredSize(new Dimension(combo.getPreferredSize().width, 42));
        }
        if (component instanceof JSpinner spinner) {
            spinner.setBorder(new PixelBorder(STONE_DARK, GOLD, 2));
            spinner.setPreferredSize(new Dimension(spinner.getPreferredSize().width, 42));
            JComponent editor = spinner.getEditor();
            if (editor instanceof JSpinner.DefaultEditor defaultEditor) {
                JTextField textField = defaultEditor.getTextField();
                textField.setForeground(INK);
                textField.setBackground(PAPER);
                textField.setCaretColor(INK);
                textField.setFont(new Font("Microsoft JhengHei UI", Font.BOLD, 16));
            }
        }
        if (component instanceof JScrollPane scrollPane) {
            scrollPane.setBorder(new PixelBorder(STONE_DARK, STONE_LIGHT, 3));
            scrollPane.setBackground(TABLE_BG);
            scrollPane.getViewport().setBackground(TABLE_BG);
        }
        if (component instanceof JTable table) {
            styleTable(table);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                styleTree(child);
            }
        }
    }

    static void styleTable(JTable table) {
        table.setOpaque(true);
        table.setRowHeight(36);
        table.setFont(new Font("Microsoft JhengHei UI", Font.PLAIN, 16));
        table.setForeground(CREAM);
        table.setBackground(TABLE_BG);
        table.setGridColor(TABLE_GRID);
        table.setSelectionBackground(GRASS);
        table.setSelectionForeground(Color.WHITE);
        table.setShowGrid(true);
        table.setIntercellSpacing(new Dimension(2, 2));
        table.setFillsViewportHeight(true);
        table.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                           boolean hasFocus, int row, int column) {
                Component cell = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (isSelected) {
                    cell.setForeground(Color.WHITE);
                    cell.setBackground(GRASS);
                } else {
                    cell.setForeground(CREAM);
                    cell.setBackground(row % 2 == 0 ? TABLE_BG : TABLE_ROW_ALT);
                }
                if (cell instanceof JComponent component) {
                    component.setBorder(new EmptyBorder(0, 6, 0, 6));
                }
                return cell;
            }
        });
        JTableHeader header = table.getTableHeader();
        header.setOpaque(true);
        header.setReorderingAllowed(false);
        header.setResizingAllowed(false);
        header.setFont(new Font("Microsoft JhengHei UI", Font.BOLD, 16));
        header.setForeground(CREAM);
        header.setBackground(STONE_DARK);
        header.setPreferredSize(new Dimension(header.getPreferredSize().width, 38));
        header.setBorder(new PixelBorder(STONE_DARK, GOLD, 2));
        header.setDefaultRenderer((sourceTable, value, isSelected, hasFocus, row, column) -> {
            JLabel label = new JLabel(value == null ? "" : value.toString());
            label.setOpaque(true);
            label.setFont(new Font("Microsoft JhengHei UI", Font.BOLD, 16));
            label.setForeground(CREAM);
            label.setBackground(STONE_DARK);
            label.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 1, TABLE_GRID),
                    new EmptyBorder(0, 8, 0, 8)));
            label.setHorizontalAlignment(SwingConstants.LEFT);
            return label;
        });
    }
}

final class PixelBorder extends AbstractBorder {
    private final Color shadow;
    private final Color highlight;
    private final int size;

    PixelBorder(Color shadow, Color highlight, int size) {
        this.shadow = shadow;
        this.highlight = highlight;
        this.size = size;
    }

    @Override
    public Insets getBorderInsets(Component c) {
        return new Insets(size + 2, size + 2, size + 3, size + 3);
    }

    @Override
    public Insets getBorderInsets(Component c, Insets insets) {
        insets.set(size + 2, size + 2, size + 3, size + 3);
        return insets;
    }

    @Override
    public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setColor(highlight);
        for (int i = 0; i < size; i++) {
            g2.drawLine(x + i, y + i, x + width - i - 2, y + i);
            g2.drawLine(x + i, y + i, x + i, y + height - i - 2);
        }
        g2.setColor(shadow);
        for (int i = 0; i < size; i++) {
            g2.drawLine(x + i, y + height - i - 1, x + width - i - 1, y + height - i - 1);
            g2.drawLine(x + width - i - 1, y + i, x + width - i - 1, y + height - i - 1);
        }
        g2.dispose();
    }
}

final class PixelButtonUi extends javax.swing.plaf.basic.BasicButtonUI {
    @Override
    public void paint(Graphics g, JComponent c) {
        AbstractButton button = (AbstractButton) c;
        ButtonModel model = button.getModel();
        Graphics2D g2 = (Graphics2D) g.create();
        Color base = button.getBackground();
        if (model.isPressed()) {
            base = Ui.WOOD_DARK;
        } else if (model.isRollover()) {
            base = Ui.WOOD.brighter();
        }
        g2.setColor(base);
        g2.fillRect(0, 0, c.getWidth(), c.getHeight());
        g2.dispose();
        super.paint(g, c);
    }
}

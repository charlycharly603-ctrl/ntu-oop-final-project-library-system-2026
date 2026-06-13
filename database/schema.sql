CREATE DATABASE IF NOT EXISTS library_system
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'library_app'@'localhost' IDENTIFIED BY '';
GRANT ALL PRIVILEGES ON library_system.* TO 'library_app'@'localhost';

USE library_system;

CREATE TABLE IF NOT EXISTS users (
  user_id INT AUTO_INCREMENT PRIMARY KEY,
  student_no VARCHAR(20) NOT NULL UNIQUE,
  name VARCHAR(50) NOT NULL,
  password_hash VARCHAR(64) NOT NULL,
  role_level ENUM('NORMAL', 'VIP') NOT NULL DEFAULT 'NORMAL',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  status ENUM('ACTIVE', 'SUSPENDED') NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE IF NOT EXISTS admins (
  admin_id INT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(50) NOT NULL UNIQUE,
  password_hash VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS books (
  book_id INT AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(255) NOT NULL,
  authors VARCHAR(255) NOT NULL,
  subjects VARCHAR(255) DEFAULT '',
  publisher VARCHAR(255) DEFAULT '',
  publish_year VARCHAR(10) DEFAULT '',
  edition VARCHAR(50) DEFAULT '',
  format_desc VARCHAR(100) DEFAULT '',
  source VARCHAR(100) DEFAULT '',
  note TEXT,
  total_copies INT NOT NULL DEFAULT 1,
  available_copies INT NOT NULL DEFAULT 1,
  status ENUM('AVAILABLE', 'ARCHIVED') NOT NULL DEFAULT 'AVAILABLE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

SET @add_books_status := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE books ADD COLUMN status ENUM(''AVAILABLE'', ''ARCHIVED'') NOT NULL DEFAULT ''AVAILABLE'' AFTER available_copies',
    'SELECT 1'
  )
  FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'books'
    AND COLUMN_NAME = 'status'
);
PREPARE add_books_status_stmt FROM @add_books_status;
EXECUTE add_books_status_stmt;
DEALLOCATE PREPARE add_books_status_stmt;

CREATE TABLE IF NOT EXISTS book_isbns (
  isbn_id INT AUTO_INCREMENT PRIMARY KEY,
  book_id INT NOT NULL,
  isbn VARCHAR(20) NOT NULL,
  UNIQUE KEY uq_book_isbn (book_id, isbn),
  CONSTRAINT fk_isbn_book FOREIGN KEY (book_id) REFERENCES books(book_id)
    ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS borrow_records (
  record_id INT AUTO_INCREMENT PRIMARY KEY,
  user_id INT NOT NULL,
  book_id INT NOT NULL,
  borrow_date DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  due_date DATETIME NOT NULL,
  return_date DATETIME NULL,
  borrow_days INT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_record_user FOREIGN KEY (user_id) REFERENCES users(user_id),
  CONSTRAINT fk_record_book FOREIGN KEY (book_id) REFERENCES books(book_id)
);

INSERT INTO admins (username, password_hash)
SELECT 'admin', SHA2('admin123', 256)
WHERE NOT EXISTS (SELECT 1 FROM admins WHERE username = 'admin');

INSERT INTO users (student_no, name, password_hash, role_level)
SELECT 'B001', 'Demo Student', SHA2('1234', 256), 'NORMAL'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE student_no = 'B001');

INSERT INTO users (student_no, name, password_hash, role_level)
SELECT 'B002', 'VIP Student', SHA2('1234', 256), 'VIP'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE student_no = 'B002');

INSERT INTO users (student_no, name, password_hash, role_level, status)
SELECT 'B003', 'Suspended Student', SHA2('1234', 256), 'NORMAL', 'SUSPENDED'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE student_no = 'B003');

INSERT INTO books (title, authors, subjects, publisher, publish_year, edition, format_desc, source, note, total_copies, available_copies)
SELECT 'Effective Java', 'Joshua Bloch', 'Java, Programming', 'Addison-Wesley', '2018', '3rd', 'Paperback', 'Seed', 'Java programming classic', 3, 3
WHERE NOT EXISTS (SELECT 1 FROM books WHERE title = 'Effective Java');

INSERT INTO books (title, authors, subjects, publisher, publish_year, edition, format_desc, source, note, total_copies, available_copies)
SELECT '資料庫系統概論', 'Abraham Silberschatz', 'Database', 'McGraw-Hill', '2020', '7th', 'Textbook', 'Seed', 'Database fundamentals', 2, 2
WHERE NOT EXISTS (SELECT 1 FROM books WHERE title = '資料庫系統概論');

INSERT INTO book_isbns (book_id, isbn)
SELECT book_id, '9780134685991' FROM books
WHERE title = 'Effective Java'
  AND NOT EXISTS (SELECT 1 FROM book_isbns WHERE isbn = '9780134685991');

INSERT INTO books (
  title,
  authors,
  subjects,
  publisher,
  publish_year,
  total_copies,
  available_copies
)
SELECT seed.title, seed.authors, seed.subjects, seed.publisher, seed.publish_year,
       seed.total_copies, seed.available_copies
FROM (
  SELECT '資料庫系統導論' AS title, '林建宏' AS authors, 'Database, SQL, Computer Science' AS subjects, '知識工坊' AS publisher, '2023' AS publish_year, 6 AS total_copies, 6 AS available_copies
  UNION ALL SELECT 'Python 自動化實務', '陳怡君', 'Python, Automation, Programming', '科技人出版社', '2024', 4, 4
  UNION ALL SELECT '人工智慧基礎', '張家豪', 'Artificial Intelligence, Machine Learning', '未來科技出版', '2025', 5, 5
  UNION ALL SELECT '網頁前端開發入門', '黃柏翰', 'HTML, CSS, JavaScript, Web Development', '程式設計書房', '2022', 7, 7
  UNION ALL SELECT '作業系統概論', '吳承恩', 'Operating System, Computer Science', '大學資訊出版', '2021', 3, 3
  UNION ALL SELECT '現代經濟學', '李雅婷', 'Economics, Finance', '商學出版社', '2020', 5, 5
  UNION ALL SELECT '行銷管理實務', '許志明', 'Marketing, Management, Business', '企業管理出版', '2023', 6, 6
  UNION ALL SELECT '心理學與生活', '周佩珊', 'Psychology, Life Science', '人文知識館', '2022', 4, 4
  UNION ALL SELECT '世界歷史概覽', '鄭文凱', 'History, World History', '博雅文化', '2019', 5, 5
  UNION ALL SELECT '台灣地理與文化', '蔡明哲', 'Geography, Taiwan, Culture', '島嶼出版社', '2021', 6, 6
  UNION ALL SELECT '基礎會計學', '郭佳穎', 'Accounting, Business', '財經教育出版', '2024', 4, 4
  UNION ALL SELECT '英文閱讀技巧', '羅美玲', 'English, Reading, Language Learning', '語文學習出版社', '2020', 8, 8
  UNION ALL SELECT '日語初級文法', '森田健一', 'Japanese, Grammar, Language Learning', '東亞語言出版', '2023', 5, 5
  UNION ALL SELECT '健康飲食指南', '林佳蓉', 'Health, Nutrition, Food', '生活健康出版', '2022', 6, 6
  UNION ALL SELECT '運動科學入門', '高志遠', 'Sports Science, Fitness, Health', '體育知識館', '2021', 3, 3
  UNION ALL SELECT '環境保護與永續發展', '蘇冠宇', 'Environment, Sustainability', '綠色地球出版', '2024', 5, 5
  UNION ALL SELECT '法律常識入門', '范庭萱', 'Law, Society', '公民教育出版', '2020', 4, 4
  UNION ALL SELECT '攝影構圖技巧', '何俊毅', 'Photography, Art, Design', '影像藝術出版', '2023', 6, 6
  UNION ALL SELECT '小說創作方法', '楊子晴', 'Writing, Literature, Novel', '文學創作坊', '2022', 5, 5
  UNION ALL SELECT '投資理財基礎', '劉宗翰', 'Investment, Finance, Personal Finance', '財富管理出版', '2025', 4, 4
) AS seed
WHERE NOT EXISTS (SELECT 1 FROM books WHERE books.title = seed.title);

INSERT INTO borrow_records (user_id, book_id, borrow_date, due_date, return_date, borrow_days, created_at)
SELECT u.user_id, b.book_id, '2026-05-10 10:00:00', '2026-05-17 10:00:00',
       '2026-05-16 15:30:00', 7, '2026-05-10 10:00:00'
FROM users u
JOIN books b ON b.title = '資料庫系統導論'
WHERE u.student_no = 'B002'
  AND NOT EXISTS (
    SELECT 1 FROM borrow_records r
    WHERE r.user_id = u.user_id
      AND r.book_id = b.book_id
      AND r.borrow_date = '2026-05-10 10:00:00'
  );

INSERT INTO borrow_records (user_id, book_id, borrow_date, due_date, return_date, borrow_days, created_at)
SELECT u.user_id, b.book_id, '2026-05-20 09:00:00', '2026-05-27 09:00:00',
       '2026-05-29 11:00:00', 7, '2026-05-20 09:00:00'
FROM users u
JOIN books b ON b.title = '作業系統概論'
WHERE u.student_no = 'B003'
  AND NOT EXISTS (
    SELECT 1 FROM borrow_records r
    WHERE r.user_id = u.user_id
      AND r.book_id = b.book_id
      AND r.borrow_date = '2026-05-20 09:00:00'
  );

INSERT INTO borrow_records (user_id, book_id, borrow_date, due_date, return_date, borrow_days, created_at)
SELECT u.user_id, b.book_id, '2026-06-01 14:00:00', '2026-06-08 14:00:00',
       NULL, 7, '2026-06-01 14:00:00'
FROM users u
JOIN books b ON b.title = 'Effective Java'
WHERE u.student_no = 'B001'
  AND NOT EXISTS (
    SELECT 1 FROM borrow_records r
    WHERE r.user_id = u.user_id
      AND r.book_id = b.book_id
      AND r.borrow_date = '2026-06-01 14:00:00'
  );

INSERT INTO borrow_records (user_id, book_id, borrow_date, due_date, return_date, borrow_days, created_at)
SELECT u.user_id, b.book_id, '2026-06-10 16:00:00', '2026-06-24 16:00:00',
       NULL, 14, '2026-06-10 16:00:00'
FROM users u
JOIN books b ON b.title = 'Python 自動化實務'
WHERE u.student_no = 'B002'
  AND NOT EXISTS (
    SELECT 1 FROM borrow_records r
    WHERE r.user_id = u.user_id
      AND r.book_id = b.book_id
      AND r.borrow_date = '2026-06-10 16:00:00'
  );

UPDATE books b
LEFT JOIN (
  SELECT book_id, COUNT(*) AS active_loans
  FROM borrow_records
  WHERE return_date IS NULL
  GROUP BY book_id
) r ON b.book_id = r.book_id
SET b.available_copies = CASE
  WHEN b.status = 'ARCHIVED' THEN 0
  ELSE GREATEST(b.total_copies - COALESCE(r.active_loans, 0), 0)
END;

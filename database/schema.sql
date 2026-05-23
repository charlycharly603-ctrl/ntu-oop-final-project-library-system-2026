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
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

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

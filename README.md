# Java Final Project - Library Borrowing System

這個專案依照 `專題簡報.pdf` 的指定題目製作：圖書館借還書系統。

## 功能

- 學生註冊與登入
- 管理員登入
- 書籍查詢：可依書名、作者、主題、出版社、ISBN 搜尋
- 學生借書、還書、查看個人借閱紀錄
- 逾期與即將到期提醒
- 管理員查看所有借閱紀錄
- 管理員新增書籍、下架書籍
- 管理員停權與復權使用者

## 資料庫

1. 安裝 MySQL。
2. 執行 `database/schema.sql` 建立資料庫與示範資料。
3. 下載 MySQL Connector/J，將 jar 放到 `lib/`。

預設連線資訊：

- URL: `jdbc:mysql://localhost:3306/library_system?useSSL=false&serverTimezone=Asia/Taipei&allowPublicKeyRetrieval=true`
- 使用者：`library_app`
- 密碼：空字串

可用環境變數覆蓋：

- `LIB_DB_URL`
- `LIB_DB_USER`
- `LIB_DB_PASSWORD`

## 編譯與執行

PowerShell:

```powershell
javac -encoding UTF-8 -d out src\library\LibraryApp.java
java -cp "out;lib\mysql-connector-j-*.jar" library.LibraryApp
```

或直接執行：

```powershell
.\run.ps1
```

它會直接編譯並啟動程式。

示範帳號：

- 學生：`B001` / `1234`
- 管理員：`admin` / `admin123`

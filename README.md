# Java Final Project - Library Borrowing System

這個專案依照 `專題簡報.pdf` 的指定題目製作：圖書館借還書系統。

## 功能

- 學生註冊與登入
- 管理員登入
- 書籍查詢：可依書名、作者、主題、出版社、ISBN 搜尋
- 查看完整書目資訊與單本書籍近期借還紀錄
- 學生借書、還書、查看個人借閱紀錄
- 學生側邊欄顯示借閱狀態：借閱中數量、借閱上限、即將到期、逾期與模擬罰款
- 借閱數量限制：NORMAL 最多同時借閱 3 本，VIP 最多同時借閱 6 本
- 借閱期限限制：NORMAL 最長借閱 7 天，VIP 最長借閱 14 天
- 逾期與即將到期提醒，並即時計算逾期天數
- 模擬罰款：逾期每日 5 元，還書時會顯示罰款提示
- 管理員查看所有借閱紀錄，並可依學號或姓名查詢
- 管理員新增、編輯、下架與恢復上架書籍
- 管理員調整館藏數量，且會防止館藏小於借出中數量
- 管理員首頁統計館藏、借閱、逾期與停權狀態
- 管理員可替學生登記還書
- 管理員搜尋學生、查看借閱概況、停權與復權使用者

## 本次更新（2026-06-14）

- 實作 NORMAL 與 VIP 借閱權限差異：NORMAL 最長 7 天，VIP 最長 14 天。
- 新增完整書目資料檢視，支援版本、格式、資料來源與附註。
- 新增單本書籍最近 50 筆借出與歸還紀錄。
- 管理員可依學生學號或姓名查詢完整借閱歷史。
- 擴充書籍新增與編輯畫面，使其支援完整書目欄位。
- 新增一般、VIP、停權學生及已歸還、借閱中、逾期等示範資料。
- Java 程式已完成編譯驗證；`database/schema.sql` 仍需在 MySQL 環境實際匯入測試。

## 資料庫

1. 安裝 MySQL。
2. 執行 `database/schema.sql` 建立資料庫、書籍與歷史借還示範資料。
3. 確認 `lib/` 內有 MySQL Connector/J。本專案已附 `lib/mysql-connector-j-9.7.0.jar`，若遺失再重新下載放回 `lib/`。

如果已經建立過舊版資料庫，請重新執行 `database/schema.sql`。它會替 `books` 補上 `status` 欄位，讓下架書籍可以保留歷史借閱紀錄。

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
java -Dfile.encoding=UTF-8 -cp "out;lib\*" library.LibraryApp
```

或直接執行：

```powershell
.\run.ps1
```

Windows Command Prompt 也可以執行：

```bat
run.bat
```

它會直接編譯並啟動程式。

示範帳號：

- 一般學生：`B001` / `1234`
- VIP 學生：`B002` / `1234`
- 停權學生：`B003` / `1234`
- 管理員：`admin` / `admin123`

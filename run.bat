@echo off
set LIB_DB_USER=library_app
set LIB_DB_PASSWORD=

javac -encoding UTF-8 -d out src\library\LibraryApp.java
if errorlevel 1 (
    echo Compile failed.
    pause
    exit /b 1
)

java -Dfile.encoding=UTF-8 -cp "out;lib\*" library.LibraryApp

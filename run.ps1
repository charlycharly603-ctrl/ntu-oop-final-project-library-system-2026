$ErrorActionPreference = "Stop"

$env:LIB_DB_USER = "library_app"
$env:LIB_DB_PASSWORD = ""

javac -encoding UTF-8 -d out src\library\LibraryApp.java
java -Dfile.encoding=UTF-8 -cp "out;lib\*" library.LibraryApp

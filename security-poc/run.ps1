# security-poc 실행 스크립트. DBAgent-Java 실제 소스(src/)는 전혀 건드리지 않는 독립 프로토타입.
$JavaHome = Join-Path (Split-Path $PSScriptRoot -Parent) "jdk17"
$javac = Join-Path $JavaHome "bin\javac.exe"
$java = Join-Path $JavaHome "bin\java.exe"

Set-Location $PSScriptRoot
& $javac --% -encoding UTF-8 -source 8 -target 8 MasterKeyProvider.java CredentialCipher.java Demo.java
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $java --% -Dfile.encoding=UTF-8 Demo

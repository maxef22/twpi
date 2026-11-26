# Runs the formula self-test against build\classes (run build.ps1 first).
# ASCII-only on purpose: Windows PowerShell 5.1 reads UTF-8 without BOM as ANSI.

param(
    [string]$Jdk = ""
)

$ErrorActionPreference = "Stop"

$root = Split-Path $PSScriptRoot -Parent
$classes = Join-Path $root "build\classes"
if (-not (Test-Path -LiteralPath $classes)) {
    throw "build\classes not found. Run build.ps1 first."
}

if ($Jdk) {
    $javac = Join-Path $Jdk "bin\javac.exe"
    $java = Join-Path $Jdk "bin\java.exe"
} else {
    $javac = (Get-Command javac -ErrorAction SilentlyContinue).Source
    $java = (Get-Command java -ErrorAction SilentlyContinue).Source
}

$testDir = Join-Path $root "build\test-classes"
New-Item -ItemType Directory -Force -Path $testDir | Out-Null

& $javac -encoding UTF-8 -cp $classes -d $testDir (Join-Path $PSScriptRoot "SelfTest.java")
if ($LASTEXITCODE -ne 0) { throw "SelfTest.java failed to compile" }

& $java -cp "$classes;$testDir" SelfTest
if ($LASTEXITCODE -ne 0) { throw "Self-test failed" }

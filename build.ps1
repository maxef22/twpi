# Build the TWPI add-on for WebCTRL.
#
#   .\build.ps1
#   .\build.ps1 -Tomcat "D:\WebCTRL\tomcat" -Jdk "C:\Program Files\Java\jdk-11"
#
# Output: dist\twpi.addon -- the file to install into WebCTRL.
# NetBeans/Ant are not required: plain javac + zip.
#
# Keep this file ASCII-only: Windows PowerShell 5.1 reads UTF-8 without BOM as ANSI
# and fails to parse non-Latin text.

param(
    [string]$Tomcat = "C:\Program Files\Apache Software Foundation\Tomcat 9.0",
    [string]$Jdk = "",
    [string]$OutFile = "",
    [string]$Release = "8"
)

$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
if (-not $OutFile) { $OutFile = Join-Path $root "dist\twpi.addon" }

$apiJar = Join-Path (Split-Path $root -Parent) "directaccess-api-addon-1.7.0.jar"
$jsonJar = Join-Path (Split-Path $root -Parent) "json-20251224.jar"
$servletJar = Join-Path $Tomcat "lib\servlet-api.jar"

foreach ($jar in @($apiJar, $jsonJar, $servletJar)) {
    if (-not (Test-Path -LiteralPath $jar)) {
        throw "Jar not found: $jar. Pass -Tomcat or put the jars next to the project."
    }
}

if ($Jdk) {
    $javac = Join-Path $Jdk "bin\javac.exe"
} else {
    $javac = (Get-Command javac -ErrorAction SilentlyContinue).Source
}
if (-not $javac -or -not (Test-Path -LiteralPath $javac)) {
    throw "javac not found. Pass -Jdk or add a JDK to PATH."
}

$buildDir = Join-Path $root "build"
$classesDir = Join-Path $buildDir "classes"
$stageDir = Join-Path $buildDir "stage"

Write-Host "== Clean" -ForegroundColor Cyan
foreach ($dir in @($classesDir, $stageDir)) {
    if (Test-Path -LiteralPath $dir) { Remove-Item -LiteralPath $dir -Recurse -Force }
}
New-Item -ItemType Directory -Force -Path $classesDir, $stageDir | Out-Null

$classpath = "$apiJar;$jsonJar;$servletJar"
$sources = @(Get-ChildItem -Path (Join-Path $root "src\java") -Recurse -Filter "*.java" |
    ForEach-Object { $_.FullName })

$versionText = (& $javac -version 2>&1 | Out-String).Trim()
Write-Host ("== Compiling {0} files ({1})" -f $sources.Count, $versionText) -ForegroundColor Cyan

$javacArgs = @("-encoding", "UTF-8", "-g", "-Xlint:-options", "-nowarn",
    "-d", $classesDir, "-classpath", $classpath)
if ($versionText -match 'javac 1\.8|javac 8') {
    if ($Release -ne "8") {
        Write-Host "== WARNING: this is JDK 8, cannot target $Release; building for 8" -ForegroundColor Yellow
    }
    $javacArgs += @("-source", "8", "-target", "8")
} else {
    $javacArgs += @("--release", $Release)
}

# 2>&1 is required: in Windows PowerShell 5.1 javac's stderr becomes a terminating
# error when $ErrorActionPreference is Stop.
$ErrorActionPreference = "Continue"
& $javac @javacArgs @sources 2>&1 | ForEach-Object { Write-Host $_ }
$javacExit = $LASTEXITCODE
$ErrorActionPreference = "Stop"
if ($javacExit -ne 0) { throw "javac failed (exit code $javacExit)" }

Write-Host "== Packaging" -ForegroundColor Cyan
$webappDir = Join-Path $stageDir "webapp"
$webinfDir = Join-Path $webappDir "WEB-INF"
New-Item -ItemType Directory -Force -Path (Join-Path $webinfDir "classes"), (Join-Path $webinfDir "lib") | Out-Null

Copy-Item -LiteralPath (Join-Path $root "web\info.xml") -Destination (Join-Path $stageDir "info.xml")
Copy-Item -LiteralPath (Join-Path $root "web\META-INF") -Destination $stageDir -Recurse
# index.html + status.jsp и любые другие страницы из web/
Get-ChildItem -Path (Join-Path $root "web") -File |
    Where-Object { $_.Extension -eq ".html" -or $_.Extension -eq ".jsp" } |
    ForEach-Object { Copy-Item -LiteralPath $_.FullName -Destination $webappDir }
Copy-Item -LiteralPath (Join-Path $root "web\WEB-INF\web.xml") -Destination $webinfDir
Copy-Item -Path (Join-Path $classesDir "*") -Destination (Join-Path $webinfDir "classes") -Recurse
Copy-Item -LiteralPath $jsonJar -Destination (Join-Path $webinfDir "lib")

# MANIFEST.MF inside META-INF, like the other add-ons
Copy-Item -LiteralPath (Join-Path $root "src\conf\MANIFEST.MF") -Destination (Join-Path $stageDir "META-INF\MANIFEST.MF")

$outDir = Split-Path $OutFile -Parent
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
if (Test-Path -LiteralPath $OutFile) { Remove-Item -LiteralPath $OutFile -Force }

try { Add-Type -AssemblyName System.IO.Compression } catch { }
try { Add-Type -AssemblyName System.IO.Compression.FileSystem } catch { }

$zip = [System.IO.Compression.ZipFile]::Open($OutFile, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    Get-ChildItem -Path $stageDir -Recurse -File | ForEach-Object {
        $name = $_.FullName.Substring($stageDir.Length + 1).Replace("\", "/")
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $name) | Out-Null
    }
} finally {
    $zip.Dispose()
}

$size = (Get-Item -LiteralPath $OutFile).Length
Write-Host ("== Done: {0} ({1:N0} bytes)" -f $OutFile, $size) -ForegroundColor Green

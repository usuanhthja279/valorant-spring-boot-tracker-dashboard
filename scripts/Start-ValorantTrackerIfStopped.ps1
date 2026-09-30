$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$logPath = Join-Path $projectRoot 'tracker-startup.log'
$port = 8080

$listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
    Select-Object -First 1

if ($listener) {
    Add-Content -LiteralPath $logPath -Value "$(Get-Date -Format o) Port $port is already in use by PID $($listener.OwningProcess); tracker start skipped."
    exit 0
}

$jdk = Get-ChildItem -Path 'C:\Program Files\JetBrains' -Directory -Filter 'IntelliJ IDEA*' -ErrorAction SilentlyContinue |
    Sort-Object -Property LastWriteTime -Descending |
    ForEach-Object { Join-Path $_.FullName 'jbr' } |
    Where-Object { Test-Path (Join-Path $_ 'bin\java.exe') } |
    Select-Object -First 1

if (-not $jdk) {
    throw 'Could not find an IntelliJ bundled JDK under C:\Program Files\JetBrains.'
}

$wrapper = Join-Path $projectRoot 'gradlew.bat'
if (-not (Test-Path $wrapper)) {
    throw "Gradle wrapper not found: $wrapper"
}

$env:JAVA_HOME = $jdk
$env:Path = "$(Join-Path $jdk 'bin');$env:Path"
$errorLogPath = Join-Path $projectRoot 'tracker-startup-error.log'
$arguments = "/d /s /c `"`"$wrapper`" bootRun --args=--server.port=$port`""

$process = Start-Process `
    -FilePath $env:ComSpec `
    -ArgumentList $arguments `
    -WorkingDirectory $projectRoot `
    -WindowStyle Hidden `
    -RedirectStandardOutput $logPath `
    -RedirectStandardError $errorLogPath `
    -PassThru

Add-Content -LiteralPath $logPath -Value "$(Get-Date -Format o) Started Gradle bootRun launcher (PID $($process.Id)) for port $port."

param([switch]$NoBrowser)

$root = Resolve-Path "$PSScriptRoot\..\.."
$vendor = "$root\vector-web\vendor"
$devDir = "$root\_dev"

if (-not (Test-Path $devDir)) { New-Item -ItemType Directory -Path $devDir -Force | Out-Null }

function Write-Step($n, $msg) {
    Write-Host "[$n] $msg" -ForegroundColor Yellow
}
function Write-OK($msg) {
    Write-Host "  OK $msg" -ForegroundColor Green
}
function LogCleanup {
    Get-ChildItem "$root\vector-web\scripts\*.log" -ErrorAction SilentlyContinue | Remove-Item -Force
}

$pids = @{}
function Start-Engine($name, $workdir, $pythonpath, $module, $extraEnv, $args) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = "cmd.exe"
    $title = "vector-$name"
    $envBlock = "title $title && set PYTHONPATH=$pythonpath"
    if ($extraEnv) {
        foreach ($kv in $extraEnv.GetEnumerator()) {
            $envBlock += " && set $($kv.Key)=$($kv.Value)"
        }
    }
    $psi.Arguments = "/c $envBlock && uv run --no-sync python -m $module $args"
    $psi.WorkingDirectory = $workdir
    $psi.UseShellExecute = $true
    $psi.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Normal
    $p = [System.Diagnostics.Process]::Start($psi)
    $pids[$name] = $p.Id
    return $p
}

function Stop-All {
    Write-Host "`nShutting down..." -ForegroundColor Cyan
    foreach ($entry in $pids.GetEnumerator()) {
        $name = $entry.Key
        $pid = $entry.Value
        Write-Host "  Stopping $name (PID $pid)..."
        try { taskkill /PID $pid /T /F 2>&1 | Out-Null } catch {}
    }
    $pids.Clear()
    LogCleanup
    Write-Host "All engines stopped." -ForegroundColor Green
}

function Test-Health($port) {
    try {
        $r = [System.Net.HttpWebRequest]::Create("http://127.0.0.1:$port/healthz")
        $r.Timeout = 2000
        $resp = $r.GetResponse()
        $code = $resp.StatusCode -as [int]
        $resp.Close()
        return $code -eq 200
    } catch {
        return $false
    }
}

function Wait-Healthy($name, $port, $timeoutSeconds) {
    $elapsed = 0
    while ($elapsed -lt $timeoutSeconds) {
        if (Test-Health $port) {
            Write-OK "$name healthy (port $port)"
            return $true
        }
        Start-Sleep 1
        $elapsed++
    }
    Write-Host "  WARN $name not healthy after ${timeoutSeconds}s" -ForegroundColor DarkYellow
    return $false
}

LogCleanup

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "       Vector Web - Dev Up              " -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# 0. Generate geocoder sample index
Write-Step "0/6" "Generating geocoder sample index..."
$gen = Start-Process -FilePath "python" -ArgumentList "`"$root\vector-web\scripts\berlin_index.py`" --output `"$devDir\berlin_places.geojson`"" -NoNewWindow -Wait -PassThru
if ($gen.ExitCode -ne 0) {
    Write-Host "ERROR: geocoder index generation failed" -ForegroundColor Red
    exit 1
}
Write-OK "$devDir\berlin_places.geojson"

# 1. Tile server (port 3000)
Write-Step "1/6" "Starting tile server (:3000)..."
Start-Engine "tile-server" "$root\vector-tile-server" "src;$vendor" "vector_tile_server.serve" @{} "--port 3000"
Start-Sleep 1

# 2. Routing engine (port 8091)
Write-Step "2/6" "Starting routing engine (:8091)..."
Start-Engine "routing" "$root\vector-routing" "src;$vendor" "vector_routing.serve" @{} "--port 8091"
Start-Sleep 1

# 3. Traffic engine (port 8094)
Write-Step "3/6" "Starting traffic engine (:8094)..."
Start-Engine "traffic" "$root\vector-traffic" "src;$vendor" "vector_traffic.serve" @{} "--port 8094"
Start-Sleep 1

# 4. Geocoder (port 8095)
Write-Step "4/6" "Starting geocoder (:8095)..."
Start-Engine "geocoder" "$root\vector-geocoder" "src;..\vector-web\vendor" "vector_geocoder.serve" @{} "--port 8095 --index `"$devDir\berlin_places.geojson`""
Start-Sleep 1

# 5. Web proxy (port 8080)
Write-Step "5/6" "Starting web proxy (:8080)..."

$webEnv = @{
    "VECTOR_ROUTING_URL" = "http://localhost:8091"
    "VECTOR_TRAFFIC_URL" = "http://localhost:8094"
    "VECTOR_TILE_SERVER_URL" = "http://localhost:3000"
    "VECTOR_GEOCODER_URL" = "http://localhost:8095"
    "VECTOR_WEB_TOKEN" = "secret"
    "VECTOR_ROUTING_TRAFFIC_URL" = "http://localhost:8091"
}
Start-Engine "web" "$root\vector-web" "src;vendor" "vector_web" $webEnv "--port 8080"
Start-Sleep 2

# 6. Health checks
Write-Step "6/6" "Verifying health..."
Write-Host ""
$healthy = @{}
$healthy["tile-server"] = Wait-Healthy "tile-server" 3000 10
$healthy["routing"] = Wait-Healthy "routing" 8091 10
$healthy["traffic"] = Wait-Healthy "traffic" 8094 10
$healthy["geocoder"] = Wait-Healthy "geocoder" 8095 10
$healthy["web"] = Wait-Healthy "web" 8080 10

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "              DEV SERVERS                " -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Tile Server  -> http://localhost:3000" -ForegroundColor White
Write-Host "  Routing      -> http://localhost:8091" -ForegroundColor White
Write-Host "  Traffic      -> http://localhost:8094" -ForegroundColor White
Write-Host "  Geocoder     -> http://localhost:8095" -ForegroundColor White
Write-Host "  Web Proxy    -> http://localhost:8080 (OPEN THIS)" -ForegroundColor Green
Write-Host "----------------------------------------"
Write-Host "  Token: secret (auto-appended in URL)" -ForegroundColor DarkGray
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

if ($healthy.Values -notcontains $true) {
    Write-Host "WARNING: No engines appear healthy. Check individual windows." -ForegroundColor DarkYellow
}

if (-not $NoBrowser) {
    Start-Process "http://localhost:8080?token=secret"
}

Write-Host "Press Ctrl+C to stop all engines." -ForegroundColor Cyan

try {
    while ($true) { Start-Sleep 1 }
} finally {
    Stop-All
}

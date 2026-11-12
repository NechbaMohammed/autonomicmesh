# ==============================================================================
# AutonomicMesh -- Execution continue (Daemon)
# Lance les 3 services gRPC, le gestionnaire (Monitor :9090) et les 3 sondes.
# ==============================================================================
$ErrorActionPreference = "Stop"

$script:AsciiRoot = "C:\Users\hp\am2"
$script:LogDir    = Join-Path $AsciiRoot "logs"
New-Item -ItemType Directory -Force $LogDir | Out-Null
Set-Location $AsciiRoot

& "$PSScriptRoot\stop-all.ps1" | Out-Null

$svcJar = Join-Path $AsciiRoot "mesh-services\target\mesh-services-1.0.0-all.jar"
$mkJar  = Join-Path $AsciiRoot "mape-k\target\mape-k-1.0.0-all.jar"

function Test-Port($port) {
    try {
        $c = New-Object System.Net.Sockets.TcpClient
        $iar = $c.BeginConnect("127.0.0.1", $port, $null, $null)
        $ok = $iar.AsyncWaitHandle.WaitOne(400, $false)
        if ($ok -and $c.Connected) {
            $c.EndConnect($iar)
            $c.Close()
            return $true
        }
        $c.Close()
        return $false
    } catch {
        return $false
    }
}

Write-Host "[1/3] Demarrage des micro-services gRPC..." -ForegroundColor Cyan
$calcJob = Start-Job -ScriptBlock {
    param($jar, $log)
    & java -jar $jar calc 50051 calc-1 2>&1 | Out-File -FilePath $log -Encoding utf8
} -ArgumentList $svcJar, (Join-Path $LogDir "calc.log")

$storageJob = Start-Job -ScriptBlock {
    param($jar, $log)
    & java -jar $jar storage 50052 storage-1 2>&1 | Out-File -FilePath $log -Encoding utf8
} -ArgumentList $svcJar, (Join-Path $LogDir "storage.log")

$notifJob = Start-Job -ScriptBlock {
    param($jar, $log)
    & java -jar $jar notif 50053 notif-1 2>&1 | Out-File -FilePath $log -Encoding utf8
} -ArgumentList $svcJar, (Join-Path $LogDir "notif.log")

for ($i = 0; $i -lt 15 -and (-not (Test-Port 50051) -or -not (Test-Port 50052) -or -not (Test-Port 50053)); $i++) {
    Start-Sleep -Milliseconds 400
}
Write-Host "Services gRPC prets : :50051, :50052, :50053" -ForegroundColor Green

Write-Host "[2/3] Demarrage du Gestionnaire autonome (Monitor IPC :9090, Super Dashboard :8088)..." -ForegroundColor Cyan
$mgrJob = Start-Job -ScriptBlock {
    param($root, $jar, $log)
    Set-Location $root
    & java -jar $jar 9090 8088 2>&1 | Out-File -FilePath $log -Encoding utf8
} -ArgumentList $AsciiRoot, $mkJar, (Join-Path $LogDir "gestionnaire.log")

for ($i = 0; $i -lt 20 -and (-not (Test-Port 9090) -or -not (Test-Port 8088)); $i++) {
    Start-Sleep -Milliseconds 400
}
Write-Host "Monitor IPC pret sur :9090 | Super Dashboard Web pret sur http://localhost:8088" -ForegroundColor Green

Write-Host "[3/3] Demarrage des sondes gRPC -> IPC..." -ForegroundColor Cyan
$probesJob = Start-Job -ScriptBlock {
    param($jar, $log)
    & java -cp $jar ca.usherbrooke.ift605.mesh.mapek.ProbesMain 9090 2>&1 | Out-File -FilePath $log -Encoding utf8
} -ArgumentList $mkJar, (Join-Path $LogDir "sondes.log")

Write-Host "AutonomicMesh en ligne et operationnel !" -ForegroundColor Green

while ($true) {
    Start-Sleep -Seconds 10
}

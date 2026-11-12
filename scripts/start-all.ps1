# Demarre le mesh : 3 services gRPC, le gestionnaire (Monitor IPC :9090) et les sondes.
# Usage :  powershell -ExecutionPolicy Bypass -File .\scripts\start-all.ps1
. "$PSScriptRoot\common.ps1"
Set-Location $Root

$svcJar = Get-ServiceJar
$mkJar  = Get-MapeKJar
if (-not (Test-Path $svcJar) -or -not (Test-Path $mkJar)) { Fail "Jars absents : lancer scripts\build.ps1 d'abord." }
if (Test-Path $PidFile) { Say "Un mesh semble deja demarre : stop-all.ps1 d'abord." "Yellow"; & "$PSScriptRoot\stop-all.ps1" }

$pids = @{}
Say "[1/3] Services gRPC" "Cyan"
$pids["calc"]    = Start-JavaProcess "calc"    @("-jar", $svcJar, "calc",    "50051", "calc-1")
$pids["storage"] = Start-JavaProcess "storage" @("-jar", $svcJar, "storage", "50052", "storage-1")
$pids["notif"]   = Start-JavaProcess "notif"   @("-jar", $svcJar, "notif",   "50053", "notif-1")
Start-Sleep -Seconds 2

Say "[2/3] Gestionnaire (Monitor IPC :9090)" "Cyan"
$pids["gestionnaire"] = Start-JavaProcess "gestionnaire" @("-jar", $mkJar, "9090")
Start-Sleep -Seconds 1

Say "[3/3] Sondes" "Cyan"
$pids["sondes"] = Start-JavaProcess "sondes" @("-cp", $mkJar, "ca.usherbrooke.ift605.mesh.mapek.ProbesMain", "9090")

Save-Pids $pids
Say "Mesh demarre. PIDs : $(($pids.GetEnumerator() | ForEach-Object { $_.Key + '=' + $_.Value }) -join ', ')" "Green"
Say "Logs : $LogDir   (gestionnaire.log = etat KB, sondes.log = mesures)" "Gray"


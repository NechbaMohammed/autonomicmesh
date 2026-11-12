# Arrete tous les processus du mesh (PIDs enregistres + ports 50051, 50052, 50053, 9090).
. "$PSScriptRoot\common.ps1"

# 1. PIDs enregistres
$pids = Read-Pids
foreach ($k in $pids.Keys) {
    $id = [int]$pids[$k]
    $p = Get-Process -Id $id -ErrorAction SilentlyContinue
    if ($p -and $p.ProcessName -like "java*") {
        Stop-Process -Id $id -Force -ErrorAction SilentlyContinue
        Say "arrete : $k (PID $id)" "Gray"
    }
}
Remove-Item $PidFile -ErrorAction SilentlyContinue

# 2. Nettoyage des ports au besoin (primaires, secondaires, IPC, Dashboard HTTP)
$ports = @(50051, 50052, 50053, 50061, 50062, 50063, 9090, 8088)
foreach ($port in $ports) {
    try {
        $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
        foreach ($c in $conns) {
            $p = Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue
            if ($p -and $p.ProcessName -like "java*") {
                Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
                Say "libere port $port (PID $($p.Id))" "Gray"
            }
        }
    } catch {}
}

# 3. Arret des Jobs PowerShell d'arriere-plan eventuels
Get-Job | Stop-Job -ErrorAction SilentlyContinue
Get-Job | Remove-Job -Force -ErrorAction SilentlyContinue

Say "Mesh arrete." "Green"

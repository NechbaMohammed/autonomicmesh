# Fonctions communes aux scripts AutonomicMesh.
$ErrorActionPreference = "Stop"

$script:Root    = Split-Path -Parent $PSScriptRoot
$script:RunDir  = Join-Path $Root "run"
$script:LogDir  = Join-Path $Root "logs"
$script:PidFile = Join-Path $RunDir "pids.json"

# Jonction ASCII pour eviter tout probleme d'espace ou d'accent dans les chemins sous Windows
$script:AsciiRoot = "C:\Users\hp\am2"
if (-not (Test-Path $AsciiRoot)) {
    New-Item -ItemType Junction -Path $AsciiRoot -Target $Root | Out-Null
}

$mavenDefault = "C:\Users\hp\maven\apache-maven-3.9.6\bin"
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    if (Test-Path "$mavenDefault\mvn.cmd") { $env:Path = "$mavenDefault;$env:Path" }
}

function Say($msg, $color = "Cyan") { Write-Host $msg -ForegroundColor $color }
function Fail($msg) { Write-Host "ERREUR : $msg" -ForegroundColor Red; exit 1 }

function Get-ServiceJar  { Join-Path $AsciiRoot "mesh-services\target\mesh-services-1.0.0-all.jar" }
function Get-MapeKJar    { Join-Path $AsciiRoot "mape-k\target\mape-k-1.0.0-all.jar" }

# Demarre un processus Java detache, journalise dans logs\<nom>.log, retourne le PID.
function Start-JavaProcess($name, [string[]]$javaArgs) {
    New-Item -ItemType Directory -Force -Path $RunDir, $LogDir | Out-Null
    $out = Join-Path $LogDir "$name.log"
    $err = Join-Path $LogDir "$name.err.log"
    $p = Start-Process -FilePath "java" -ArgumentList $javaArgs -WindowStyle Hidden -PassThru `
         -RedirectStandardOutput $out -RedirectStandardError $err
    return $p.Id
}

function Read-Pids {
    $h = @{}
    if (Test-Path $PidFile) {
        $o = Get-Content $PidFile -Raw | ConvertFrom-Json
        $o.PSObject.Properties | ForEach-Object { $h[$_.Name] = $_.Value }
    }
    return $h
}
function Save-Pids($table) {
    New-Item -ItemType Directory -Force -Path $RunDir | Out-Null
    ($table | ConvertTo-Json) | Set-Content $PidFile -Encoding UTF8
}


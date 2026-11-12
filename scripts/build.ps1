# Compile et teste tout le projet (3 modules), produit les jars executables.
# Usage :  powershell -ExecutionPolicy Bypass -File .\scripts\build.ps1 [-SkipTests]
#
# NOTE : protoc.exe (Windows) deforme les chemins contenant des accents (ex. "Systemes" avec e accent grave).
# Le build passe donc par une jonction ASCII (%USERPROFILE%\am2) qui pointe vers ce dossier.
param([switch]$SkipTests)
. "$PSScriptRoot\common.ps1"
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { Fail "Maven introuvable." }

$ascii = Join-Path $env:USERPROFILE "am2"
if (-not (Test-Path $ascii)) { New-Item -ItemType Junction -Path $ascii -Target $Root | Out-Null }
Set-Location $ascii

# On supprime target\ a la main : "mvn clean" laisse parfois un repertoire protoc-dependencies incomplet.
Get-ChildItem -Path $ascii -Directory -Filter "mesh-*" | ForEach-Object { Remove-Item (Join-Path $_.FullName "target") -Recurse -Force -ErrorAction SilentlyContinue }
Remove-Item (Join-Path $ascii "mape-k\target") -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $ascii "chaos\target") -Recurse -Force -ErrorAction SilentlyContinue

$args2 = @("-B", "-ntp", "install")
if ($SkipTests) { $args2 += "-DskipTests" }
Say "[build] mvn $($args2 -join ' ')  (dans $ascii)" "Yellow"
& mvn @args2
if ($LASTEXITCODE -ne 0) { Fail "Build en echec." }
Say "[build] OK" "Green"

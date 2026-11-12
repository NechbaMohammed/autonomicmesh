# Lance la campagne complete d'experiences de chaos et de mesure de MTTR (N=40 essais au total).
# Produit logs/experiments_results.csv et logs/summary_metrics.json.
. "$PSScriptRoot\common.ps1"

$chaosJar = Join-Path $AsciiRoot "chaos\target\chaos-1.0.0-all.jar"
if (-not (Test-Path $chaosJar)) {
    Fail "Jar introuvable : $chaosJar. Executez d'abord .\scripts\build.ps1"
}

Say "[Experiences] Demarrage de la campagne experimentale AutonomicMesh..." "Cyan"
& java -jar $chaosJar
if ($LASTEXITCODE -ne 0) {
    Fail "La campagne d'experiences a echoue."
}
Say "[Experiences] Termine avec succes ! Fichiers generes dans logs\" "Green"


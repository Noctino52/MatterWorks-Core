# --- CONFIGURAZIONE ---
$outFile = "context_matterworks.txt"
$dbUser = "Noctino52"
$dbPass = "Yy72s7mRnVs3"
$dbHost = "dev.matterworks.org"
$dbName = "matterworks_core"

# 1. Esporta il Database (Schema + Dati)
Write-Host "Esportazione database in corso..." -ForegroundColor Cyan
# Il comando mariadb-dump crea tutto il codice SQL necessario per ricostruire il DB
& mysqldump -h $dbHost -u $dbUser -p"$dbPass" --databases $dbName > $outFile

# 2. Aggiungi i file Java in coda allo stesso file
Write-Host "Aggiunta file sorgente Java..." -ForegroundColor Cyan
Add-Content $outFile "`n`n================================================"
Add-Content $outFile "SOURCE CODE SECTION"
Add-Content $outFile "================================================`n"

Get-ChildItem -Path src -Recurse -Filter *.java | ForEach-Object {
    $header = "`n`n--------------------------------`nFILE: $($_.FullName)`n--------------------------------"
    Write-Host "Aggiunta: $($_.Name)"
    Add-Content $outFile $header
    Get-Content $_.FullName | Add-Content $outFile
}

Write-Host "Operazione completata! File generato: $outFile" -ForegroundColor Green
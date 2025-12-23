<#
export_code.ps1 — MatterWorks Context Pack (bundles testuali)
NON copia il progetto. Genera file .txt/.md che contengono codice concatenato e settorizzato.

ESEMPIO:
  powershell -ExecutionPolicy Bypass -File .\export_code.ps1

OPZIONI:
  -DumpDatabase:$false     -> disabilita dump DB
  -NoResources             -> non includere src/main/resources
  -MaxBundleKB 700         -> dimensione max per bundle (split automatico)
  -DumpExe "C:\...\mysqldump.exe" -> percorso esplicito se non è nel PATH
#>

[CmdletBinding()]
param(
# --- PATH ---
    [string]$ProjectRoot = (Get-Location).Path,
    [string]$OutputRoot  = (Join-Path (Get-Location).Path "_mw_context_packs"),

# --- DB ---
    [switch]$DumpDatabase = $true,
    [string]$DbUser = "Noctino52",
    [string]$DbPass = "Yy72s7mRnVs3",
    [string]$DbHost = "dev.matterworks.org",
    [string]$DbName = "matterworks_core",
    [string]$DumpExe = "mysqldump",

# --- BUNDLE SIZE ---
    [int]$MaxBundleKB = 700,

# --- INCLUDE RESOURCES ---
    [switch]$NoResources
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# -------------------------
# Helpers
# -------------------------
function New-Dir([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Path $Path | Out-Null
    }
}

function Get-RelativePath([string]$Base, [string]$Full) {
    $basePath = (Resolve-Path -LiteralPath $Base).Path.TrimEnd('\','/')
    $fullPath = (Resolve-Path -LiteralPath $Full).Path
    if ($fullPath.StartsWith($basePath, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $fullPath.Substring($basePath.Length).TrimStart('\','/')
    }
    return (Split-Path -Path $fullPath -Leaf)
}

function Should-ExcludePath([string]$FullPath) {
    # esclude solo cartelle di build/cache/IDE comuni
    $bad = @(
        "\.git\","\.idea\","\.gradle\","\.vs\",
        "\build\","\out\","\target\","\bin\",
        "\node_modules\"
    )
    foreach ($p in $bad) {
        if ($FullPath -match [Regex]::Escape($p)) { return $true }
    }
    return $false
}

function Safe-Name([string]$s) {
    $s = $s -replace '[<>:"/\\|?*\x00-\x1F]', '_'
    $s = $s -replace '\s+', '_'
    return $s
}

function Require-Command([string]$CmdName) {
    $cmd = Get-Command $CmdName -ErrorAction SilentlyContinue
    if (-not $cmd) {
        throw "Comando non trovato: '$CmdName'. Mettilo nel PATH o passa -DumpExe 'C:\path\mysqldump.exe'"
    }
}

function Write-TreeFile([string]$Root, [string]$OutFile) {
    $items = Get-ChildItem -Path $Root -Recurse -Force | Sort-Object FullName
    $lines = foreach ($i in $items) {
        $rel = Get-RelativePath $Root $i.FullName
        if ($i.PSIsContainer) { "DIR  $rel" } else { "FILE $rel" }
    }
    $lines | Out-File -FilePath $OutFile -Encoding utf8
}

# -------------------------
# Create pack folder
# -------------------------
$ts = Get-Date -Format "yyyy-MM-dd_HH-mm-ss"
$packRoot = Join-Path $OutputRoot ("mw_context_pack_{0}" -f $ts)
New-Dir $OutputRoot
New-Dir $packRoot

$maxBytes = $MaxBundleKB * 1024

Write-Host "Context pack: $packRoot" -ForegroundColor Green

# -------------------------
# 1) DB dump (optional)
# -------------------------
$dbOut = Join-Path $packRoot "02_DB.sql"
if ($DumpDatabase) {
    Write-Host "Dump database..." -ForegroundColor Cyan
    Require-Command $DumpExe

    & $DumpExe `
        -h $DbHost `
        -u $DbUser `
        -p"$DbPass" `
        --databases $DbName `
        --single-transaction `
        --routines --triggers --events `
        --default-character-set=utf8mb4 `
        --skip-comments `
        > $dbOut

    Write-Host "DB dump OK: 02_DB.sql" -ForegroundColor DarkGreen
}

# -------------------------
# 2) Build/config “core”
# -------------------------
Write-Host "Build/config..." -ForegroundColor Cyan
$buildOut = Join-Path $packRoot "01_BUILD_AND_CONFIG.txt"
if (Test-Path -LiteralPath $buildOut) { Remove-Item -LiteralPath $buildOut -Force }

$rootConfigCandidates = @(
    "build.gradle","build.gradle.kts","settings.gradle","settings.gradle.kts",
    "gradle.properties","gradlew","gradlew.bat",
    "pom.xml",
    ".editorconfig",".gitignore",".gitattributes",
    "README.md","README.txt"
)

Add-Content -LiteralPath $buildOut -Encoding utf8 ("=== BUILD & CONFIG SNAPSHOT ({0}) ===`nProjectRoot: {1}`n" -f (Get-Date -Format "o"), $ProjectRoot)

foreach ($name in $rootConfigCandidates) {
    $p = Join-Path $ProjectRoot $name
    if (Test-Path -LiteralPath $p) {
        Add-Content -LiteralPath $buildOut -Encoding utf8 "`n`n--------------------------------"
        Add-Content -LiteralPath $buildOut -Encoding utf8 ("FILE: {0}" -f $name)
        Add-Content -LiteralPath $buildOut -Encoding utf8 "--------------------------------"
        Get-Content -LiteralPath $p | Add-Content -LiteralPath $buildOut -Encoding utf8
    }
}

# -------------------------
# 3) Diagrammi (mmd/uml/er) se presenti
# -------------------------
Write-Host "Diagrams..." -ForegroundColor Cyan
$diagOut = Join-Path $packRoot "03_DIAGRAMS.txt"
if (Test-Path -LiteralPath $diagOut) { Remove-Item -LiteralPath $diagOut -Force }

Add-Content -LiteralPath $diagOut -Encoding utf8 ("=== DIAGRAMS ({0}) ===" -f (Get-Date -Format "o"))

$diagFiles = Get-ChildItem -Path $ProjectRoot -Recurse -File -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.Extension -in @(".mmd",".uml",".plantuml") } |
        Where-Object { -not (Should-ExcludePath $_.FullName) } |
        Sort-Object FullName

foreach ($f in $diagFiles) {
    $rel = Get-RelativePath $ProjectRoot $f.FullName
    Add-Content -LiteralPath $diagOut -Encoding utf8 "`n`n--------------------------------"
    Add-Content -LiteralPath $diagOut -Encoding utf8 ("FILE: {0}" -f $rel)
    Add-Content -LiteralPath $diagOut -Encoding utf8 "--------------------------------"
    Get-Content -LiteralPath $f.FullName | Add-Content -LiteralPath $diagOut -Encoding utf8
}

# -------------------------
# 4) Collect files (src main/test + resources optional)
# -------------------------
Write-Host "Collecting source files (bundles)..." -ForegroundColor Cyan

$srcMainJava = Join-Path $ProjectRoot "src\main\java"
$srcTestJava = Join-Path $ProjectRoot "src\test\java"
$srcMainRes  = Join-Path $ProjectRoot "src\main\resources"

# Java/Kotlin
$codeExt = @(".java",".kt")

$files = @()

if (Test-Path -LiteralPath $srcMainJava) {
    $files += Get-ChildItem -Path $srcMainJava -Recurse -File -Force -ErrorAction SilentlyContinue |
            Where-Object { $_.Extension -in $codeExt }
}
if (Test-Path -LiteralPath $srcTestJava) {
    $files += Get-ChildItem -Path $srcTestJava -Recurse -File -Force -ErrorAction SilentlyContinue |
            Where-Object { $_.Extension -in $codeExt }
}

# Resources (solo config/testi utili)
if (-not $NoResources -and (Test-Path -LiteralPath $srcMainRes)) {
    $resExt = @(".yml",".yaml",".properties",".xml",".json",".txt",".md")
    $files += Get-ChildItem -Path $srcMainRes -Recurse -File -Force -ErrorAction SilentlyContinue |
            Where-Object { $_.Extension -in $resExt }
}

# Filtra esclusioni e dedup
$files = $files |
        Where-Object { -not (Should-ExcludePath $_.FullName) } |
        Sort-Object FullName -Unique

# -------------------------
# 5) Grouping logic
# -------------------------
function Get-GroupKey([System.IO.FileInfo]$f) {
    $full = $f.FullName

    if ($full -like "$srcMainJava*") {
        $rel = Get-RelativePath $srcMainJava $full
        $dir = Split-Path -Path $rel -Parent
        if (-not $dir) { return "java__root" }
        $parts = $dir -split '[\\/]' | Where-Object { $_ -ne "" }
        if ($parts.Count -gt 6) { $parts = $parts[0..5] }
        return "java__" + ($parts -join ".")
    }

    if ($full -like "$srcTestJava*") {
        $rel = Get-RelativePath $srcTestJava $full
        $dir = Split-Path -Path $rel -Parent
        if (-not $dir) { return "test__root" }
        $parts = $dir -split '[\\/]' | Where-Object { $_ -ne "" }
        if ($parts.Count -gt 6) { $parts = $parts[0..5] }
        return "test__" + ($parts -join ".")
    }

    if (-not $NoResources -and $full -like "$srcMainRes*") {
        $rel = Get-RelativePath $srcMainRes $full
        $parts = $rel -split '[\\/]' | Where-Object { $_ -ne "" }
        $bucket = if ($parts.Count -ge 1) { $parts[0] } else { "root" }
        return "resources__" + $bucket
    }

    return "misc__other"
}

# Group map: key -> array of files
$groups = @{}
foreach ($f in $files) {
    $k = Get-GroupKey $f
    if (-not $groups.ContainsKey($k)) { $groups[$k] = @() }
    $groups[$k] += $f
}

# -------------------------
# 6) Write bundles with splitting
# -------------------------
$manifest = [ordered]@{
    createdLocal = (Get-Date).ToString("o")
    projectRoot  = $ProjectRoot
    bundleMaxKB  = $MaxBundleKB
    bundles      = [ordered]@{}
}

function Write-BundlePart(
        [string]$bundleBaseName,
        [int]$partIndex,
        [System.IO.FileInfo[]]$partFiles
) {
    $partSuffix = ("_part{0:d2}" -f $partIndex)
    $fileName = Safe-Name ($bundleBaseName + $partSuffix + ".txt")
    $outPath = Join-Path $packRoot $fileName

    if (Test-Path -LiteralPath $outPath) { Remove-Item -LiteralPath $outPath -Force }

    Add-Content -LiteralPath $outPath -Encoding utf8 ("=== BUNDLE: {0}{1} ===`nCreated: {2}`n" -f $bundleBaseName, $partSuffix, (Get-Date -Format "o"))

    $listed = @()

    foreach ($sf in $partFiles) {
        $rel = Get-RelativePath $ProjectRoot $sf.FullName
        $listed += $rel

        Add-Content -LiteralPath $outPath -Encoding utf8 "`n`n--------------------------------"
        Add-Content -LiteralPath $outPath -Encoding utf8 ("FILE: {0}" -f $rel)
        Add-Content -LiteralPath $outPath -Encoding utf8 "--------------------------------"
        Get-Content -LiteralPath $sf.FullName | Add-Content -LiteralPath $outPath -Encoding utf8
    }

    return [ordered]@{
        file  = $fileName
        part  = $partIndex
        files = $listed
        bytes = (Get-Item -LiteralPath $outPath).Length
    }
}

Write-Host "Writing bundles..." -ForegroundColor Cyan

$bundleInfos = @()

foreach ($key in ($groups.Keys | Sort-Object)) {
    $bundleBase = $key
    $bundleFiles = $groups[$key] | Sort-Object FullName

    $partIndex = 1
    $current = @()
    $currentSize = 0

    foreach ($sf in $bundleFiles) {
        $estimated = (Get-Item -LiteralPath $sf.FullName).Length + 250

        if ($current.Count -gt 0 -and ($currentSize + $estimated) -gt $maxBytes) {
            $info = Write-BundlePart -bundleBaseName $bundleBase -partIndex $partIndex -partFiles $current
            $bundleInfos += $info
            $partIndex++
            $current = @()
            $currentSize = 0
        }

        $current += $sf
        $currentSize += $estimated
    }

    if ($current.Count -gt 0) {
        $info = Write-BundlePart -bundleBaseName $bundleBase -partIndex $partIndex -partFiles $current
        $bundleInfos += $info
    }
}

foreach ($b in ($bundleInfos | Sort-Object file)) {
    $manifest.bundles[$b.file] = [ordered]@{
        part  = $b.part
        bytes = $b.bytes
        files = $b.files
    }
}

$manifestPath = Join-Path $packRoot "00_MANIFEST.json"
($manifest | ConvertTo-Json -Depth 10) | Out-File -FilePath $manifestPath -Encoding utf8

# -------------------------
# 7) INDEX.md
# -------------------------
$indexPath = Join-Path $packRoot "00_INDEX.md"
if (Test-Path -LiteralPath $indexPath) { Remove-Item -LiteralPath $indexPath -Force }

$bundleCount = $bundleInfos.Count
$totalFiles  = $files.Count

@"
# MatterWorks Context Pack

Created: $(Get-Date -Format "o")

## Allegami sempre questi file
- 00_INDEX.md
- 00_MANIFEST.json
- 01_BUILD_AND_CONFIG.txt
- (se serve DB) 02_DB.sql

## Contenuto
- Build/config: 01_BUILD_AND_CONFIG.txt
- DB dump presente: $DumpDatabase
- Diagrammi: 03_DIAGRAMS.txt
- File inclusi (src + res): $totalFiles
- Bundle generati: $bundleCount

## Lista bundle
"@ | Out-File -FilePath $indexPath -Encoding utf8

foreach ($b in ($bundleInfos | Sort-Object file)) {
    ("- {0}  ({1} bytes)" -f $b.file, $b.bytes) | Out-File -FilePath $indexPath -Append -Encoding utf8
}

Write-Host "DONE." -ForegroundColor Green
Write-Host "Pack folder: $packRoot" -ForegroundColor Green

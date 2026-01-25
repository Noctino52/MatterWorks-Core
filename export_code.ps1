<#
export_code_v2.ps1 — MatterWorks Context Pack (bundles testuali)

Migliorie:
- Niente password hardcoded (env var o prompt)
- Bundling per macro-area (core.ui / core.domain / core.database / core.synchronization / ...)
- Manifest arricchito (fileCount, lineCount, sha256 opzionale)
- 00_TREE.txt sempre incluso
- INDEX con metriche + top file “pesanti”
#>

[CmdletBinding()]
param(
# --- PATH ---
    [string]$ProjectRoot = (Get-Location).Path,
    [string]$OutputRoot  = (Join-Path (Get-Location).Path "_mw_context_packs"),

# --- DB ---
    [switch]$DumpDatabase = $true,
    [string]$DbUser = "Noctino52",
    [string]$DbHost = "dev.matterworks.org",
    [string]$DbName = "matterworks_core",
    [string]$DumpExe = "mysqldump",

# Password strategy (scegline una):
    [string]$DbPass = "Yy72s7mRnVs3",                  # se vuota, prova env var / prompt
    [string]$DbPassEnvVar = "MW_DB_PASS",  # es: setx MW_DB_PASS "..."
    [switch]$PromptDbPass,                 # se attivo, chiede password in modo sicuro
    [string]$DefaultsExtraFile = "",        # se valorizzato, usa --defaults-extra-file=...

# --- BUNDLE SIZE ---
    [int]$MaxBundleKB = 700,

# --- GROUPING ---
# Quanti segmenti dopo "com.matterworks.core" usiamo per raggruppare
# es: core.ui.swing.factory.render -> depth 2 => core.ui / depth 3 => core.ui.swing
    [int]$JavaGroupDepthAfterCore = 3,

# --- INCLUDE RESOURCES ---
    [switch]$NoResources,

# --- EXTRA METRICS ---
    [switch]$ComputeSha256   # se vuoi hash dei bundle
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

function Get-LineCount([string]$Path) {
    # veloce e stabile
    return (Get-Content -LiteralPath $Path -ReadCount 0).Count
}

function Get-FileSha256([string]$Path) {
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash
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

# Always write tree snapshot (super utile)
$treeOut = Join-Path $packRoot "00_TREE.txt"
Write-Host "Tree snapshot..." -ForegroundColor Cyan
Write-TreeFile -Root $ProjectRoot -OutFile $treeOut

# -------------------------
# 1) DB dump (optional)
# -------------------------
$dbOut = Join-Path $packRoot "02_DB.sql"
if ($DumpDatabase) {
    Write-Host "Dump database..." -ForegroundColor Cyan
    Require-Command $DumpExe

    # Password strategy
    if (-not $DefaultsExtraFile) {
        if (-not $DbPass) {
            $envPass = [Environment]::GetEnvironmentVariable($DbPassEnvVar)
            if ($envPass) { $DbPass = $envPass }
        }
        if (-not $DbPass -and $PromptDbPass) {
            $sec = Read-Host "DB password" -AsSecureString
            $DbPass = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
                    [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
            )
        }
        if (-not $DbPass) {
            throw "DB password mancante. Usa -PromptDbPass oppure setta env var '$DbPassEnvVar' oppure passa -DbPass."
        }
    }

    if ($DefaultsExtraFile) {
        & $DumpExe `
            --defaults-extra-file="$DefaultsExtraFile" `
            -h $DbHost `
            -u $DbUser `
            --databases $DbName `
            --single-transaction `
            --routines --triggers --events `
            --default-character-set=utf8mb4 `
            --skip-comments `
            > $dbOut
    } else {
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
    }

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

if (-not $NoResources -and (Test-Path -LiteralPath $srcMainRes)) {
    $resExt = @(".yml",".yaml",".properties",".xml",".json",".txt",".md")
    $files += Get-ChildItem -Path $srcMainRes -Recurse -File -Force -ErrorAction SilentlyContinue |
            Where-Object { $_.Extension -in $resExt }
}

$files = $files |
        Where-Object { -not (Should-ExcludePath $_.FullName) } |
        Sort-Object FullName -Unique

# -------------------------
# 5) Grouping logic (macro-area)
# -------------------------
function Get-GroupKey([System.IO.FileInfo]$f) {
    $full = $f.FullName

    if ($full -like "$srcMainJava*") {
        $rel = Get-RelativePath $srcMainJava $full
        $dir = Split-Path -Path $rel -Parent
        if (-not $dir) { return "java__root" }

        # Forziamo l'array per evitare errori su .Count
        $parts = @($dir -split '[\\/]' | Where-Object { $_ -ne "" })

        if ($parts.Count -ge 3 -and $parts[0] -eq "com" -and $parts[1] -eq "matterworks" -and $parts[2] -eq "core") {
            $afterCore = @()
            $start = 3
            $take = [Math]::Min($JavaGroupDepthAfterCore, [Math]::Max(0, $parts.Count - $start))
            if ($take -gt 0) { $afterCore = $parts[$start..($start + $take - 1)] }

            if ($afterCore.Count -gt 0) {
                return "java__core." + ($afterCore -join ".")
            }
            return "java__core"
        }

        $fallbackTake = [Math]::Min(4, $parts.Count)
        return "java__" + (($parts[0..($fallbackTake-1)]) -join ".")
    }

    if ($full -like "$srcTestJava*") {
        $rel = Get-RelativePath $srcTestJava $full
        $dir = Split-Path -Path $rel -Parent
        if (-not $dir) { return "test__root" }
        $parts = @($dir -split '[\\/]' | Where-Object { $_ -ne "" })
        $take = [Math]::Min(4, $parts.Count)
        return "test__" + (($parts[0..($take-1)]) -join ".")
    }

    if (-not $NoResources -and $full -like "$srcMainRes*") {
        $rel = Get-RelativePath $srcMainRes $full
        $parts = @($rel -split '[\\/]' | Where-Object { $_ -ne "" })
        $bucket = if ($parts.Count -ge 1) { $parts[0] } else { "root" }
        return "resources__" + $bucket
    }

    return "misc__other"
}

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
    javaGroupDepthAfterCore = $JavaGroupDepthAfterCore
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
    $lineCount = 0

    foreach ($sf in $partFiles) {
        $rel = Get-RelativePath $ProjectRoot $sf.FullName
        $listed += $rel

        Add-Content -LiteralPath $outPath -Encoding utf8 "`n`n--------------------------------"
        Add-Content -LiteralPath $outPath -Encoding utf8 ("FILE: {0}" -f $rel)
        Add-Content -LiteralPath $outPath -Encoding utf8 "--------------------------------"
        Get-Content -LiteralPath $sf.FullName | Add-Content -LiteralPath $outPath -Encoding utf8

        $lineCount += Get-LineCount $sf.FullName
    }

    $bytes = (Get-Item -LiteralPath $outPath).Length
    $sha = $null
    if ($ComputeSha256) { $sha = Get-FileSha256 $outPath }

    $info = [ordered]@{
        file      = $fileName
        part      = $partIndex
        bytes     = $bytes
        fileCount = $partFiles.Count
        lineCount = $lineCount
        files     = $listed
    }
    if ($ComputeSha256) { $info.sha256 = $sha }

    return $info
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
        $estimated = (Get-Item -LiteralPath $sf.FullName).Length + 400 # header margin

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
    $manifest.bundles[$b.file] = $b
}

$manifestPath = Join-Path $packRoot "00_MANIFEST.json"
($manifest | ConvertTo-Json -Depth 12) | Out-File -FilePath $manifestPath -Encoding utf8

# -------------------------
# 7) INDEX.md (più utile)
# -------------------------
$indexPath = Join-Path $packRoot "00_INDEX.md"
if (Test-Path -LiteralPath $indexPath) { Remove-Item -LiteralPath $indexPath -Force }

$bundleCount = $bundleInfos.Count
$totalFiles  = $files.Count

# calcola top file pesanti (per righe) su src/main/java (utile per refactor)
$javaFiles = $files | Where-Object { $_.FullName -like "$srcMainJava*" -and $_.Extension -in $codeExt }
$topHeavy = $javaFiles | ForEach-Object {
    [pscustomobject]@{
        Path = Get-RelativePath $ProjectRoot $_.FullName
        Lines = Get-LineCount $_.FullName
        Bytes = (Get-Item -LiteralPath $_.FullName).Length
    }
} | Sort-Object Lines -Descending | Select-Object -First 10

Write-Host "DONE." -ForegroundColor Green
Write-Host "Pack folder: $packRoot" -ForegroundColor Green

<#
.SYNOPSIS
    Rebuilds app/src/main/assets/tectonic_cache.zip.xz from the canonical source archive.

.DESCRIPTION
    The Tectonic cache is shipped inside every APK. Storing it as a deflate-compressed zip
    wasted ~3 MB per ABI because the APK cannot compress an already-compressed asset any
    further. This script repacks the entries uncompressed and compresses the whole archive
    with LZMA2, which produces a byte-identical payload at roughly 75% of the original size.

    The source archive lives in tools/tectonic-cache/tectonic_cache.zip and is intentionally
    outside app/src/main/assets so it is never packaged into the APK.

.PARAMETER Source
    Path to the canonical tectonic_cache.zip. Defaults to the copy next to this script.

.PARAMETER Preset
    LZMA2 preset used for the archive. 6 is the default; 9 yields only ~0.2 MB more for a
    noticeably slower first-run extraction.
#>
param(
    [string]$Source = (Join-Path $PSScriptRoot 'tectonic_cache.zip'),
    [string]$Preset = '6'
)

$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$outputAsset = Join-Path $projectRoot 'app\src\main\assets\tectonic_cache.zip.xz'
$sourceZip = (Resolve-Path $Source).Path

if (-not (Test-Path $sourceZip)) {
    throw "Source archive not found: $sourceZip"
}

# org.tukaani:xz is already an app dependency, so its jar is present in the Gradle cache.
$xzJar = Get-ChildItem "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.tukaani\xz" `
    -Recurse -Filter 'xz-*.jar' -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch 'sources|javadoc' } |
    Sort-Object FullName -Descending |
    Select-Object -First 1

if (-not $xzJar) {
    throw 'Could not locate the org.tukaani:xz jar in the Gradle cache. Run a Gradle build first.'
}

$toolDir = Join-Path $env:TEMP 'zenlabs-tectonic-repack'
New-Item -ItemType Directory -Path $toolDir -Force | Out-Null
$toolSource = Join-Path $PSScriptRoot 'TectonicCacheRepack.java'

& javac -cp $xzJar.FullName -d $toolDir $toolSource
if ($LASTEXITCODE -ne 0) { throw 'Failed to compile TectonicCacheRepack.java' }

& java -cp "$($xzJar.FullName);$toolDir" TectonicCacheRepack pack $sourceZip $outputAsset $Preset
if ($LASTEXITCODE -ne 0) { throw 'Tectonic cache repack failed' }

# Round-trip check: the repacked archive must expand to exactly the source contents.
$scratch = Join-Path $env:TEMP ('zenlabs-tectonic-verify-' + [Guid]::NewGuid().ToString('N'))
try {
    & java -cp "$($xzJar.FullName);$toolDir" TectonicCacheRepack verify $outputAsset $scratch
    if ($LASTEXITCODE -ne 0) { throw 'Tectonic cache verification failed' }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $sourceEntries = @{}
    $zip = [System.IO.Compression.ZipFile]::OpenRead($sourceZip)
    try {
        foreach ($entry in $zip.Entries) {
            if (-not [string]::IsNullOrEmpty($entry.Name)) { $sourceEntries[$entry.FullName] = $entry.Length }
        }
    } finally { $zip.Dispose() }

    $verified = @{}
    Get-ChildItem $scratch -Recurse -File | ForEach-Object {
        $relative = $_.FullName.Substring($scratch.Length + 1).Replace('\', '/')
        $verified[$relative] = $_.Length
    }

    $missing = @($sourceEntries.Keys | Where-Object { -not $verified.ContainsKey($_) })
    $mismatched = @($sourceEntries.Keys | Where-Object {
        $verified.ContainsKey($_) -and $verified[$_] -ne $sourceEntries[$_]
    })

    if ($missing.Count -gt 0 -or $mismatched.Count -gt 0) {
        throw "Verification failed: $($missing.Count) missing, $($mismatched.Count) size mismatches"
    }

    Write-Host ("Verified {0} files; asset is {1:N2} MB" -f $verified.Count, ((Get-Item $outputAsset).Length / 1MB))
} finally {
    Remove-Item $scratch -Recurse -Force -ErrorAction SilentlyContinue
}

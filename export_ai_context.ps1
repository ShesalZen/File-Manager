#Requires -Version 5.1
<#
.SYNOPSIS
    Exports the ZenLabs File Explorer project into a self-contained context bundle that can be
    handed to an external AI which has no filesystem access and no API connectivity.

.DESCRIPTION
    Reads the repository READ-ONLY and writes a directory of plain-text files:

        PROJECT_OVERVIEW.txt        Architecture, technologies, frameworks, build configuration
        PROJECT_STRUCTURE.txt       Directory tree of the project
        SOURCE_CODE.txt             Source files with exact paths and complete contents
        RESOURCES_AND_ASSETS.txt    Resource/asset inventory (names, formats, dimensions)
        DEPENDENCIES.txt            Dependency + version catalogue and Gradle configuration
        DOCUMENTATION.txt           Project documentation
        GIT_STATUS.txt              Branch, HEAD, working-tree state
        MANIFEST.txt                Every exported/excluded file, with reasons
        README.txt                  How to use this bundle
        EXPORT_SUMMARY.txt          Counts and sizes

    The application is never modified: the script only reads from the repository and writes to
    its own output directory.

    Secrets are never exported. Signing material, local.properties and credential-bearing files
    are excluded outright, and any credential-shaped literal found inside exported text is
    redacted and logged in MANIFEST.txt.

    Large sections are split into numbered parts (SOURCE_CODE.part001.txt, ...). No file content
    is ever truncated: a single oversized file always occupies a whole part.

.PARAMETER OutputDirectory
    Directory to write the export into. Defaults to <repo>\ai-context-export.

.PARAMETER MaxChunkSizeMB
    Soft size limit for one exported text file, in MiB. Larger sections are split into numbered
    parts. Default 4.

.PARAMETER ExcludeTests
    Skip unit-test and instrumented-test sources.

.PARAMETER ExcludeVendoredSources
    Skip third-party vendored sources (LibreOfficeKit bindings, AndroidX print helper).

.PARAMETER DryRun
    Report what would be exported and write nothing.

.EXAMPLE
    .\export_ai_context.ps1
.EXAMPLE
    .\export_ai_context.ps1 -OutputDirectory C:\Temp\zenlabs-context -MaxChunkSizeMB 8
.EXAMPLE
    .\export_ai_context.ps1 -ExcludeTests -ExcludeVendoredSources -DryRun
#>
[CmdletBinding()]
param(
    [string] $OutputDirectory,
    [int]    $MaxChunkSizeMB = 4,
    [switch] $ExcludeTests,
    [switch] $ExcludeVendoredSources,
    [switch] $DryRun
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

$Script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

$Script:RepoRoot = (Resolve-Path -LiteralPath $PSScriptRoot).Path

# Name of the default export directory. Any directory with this prefix at the
# repository root is pruned, so that a previous export is never fed back into a
# new one (which would recursively nest the export inside itself).
$Script:ExportDirName = 'ai-context-export'

# Directories that never contain hand-written source worth exporting.
$Script:ExcludedDirNames = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
foreach ($d in @(
    'build', '.gradle', '.git', '.kotlin', '.cxx', '.externalNativeBuild', '.idea', '.vs',
    'node_modules', 'out', 'captures', '__pycache__', '.mypy_cache', '.pytest_cache',
    'DerivedData', 'Pods'
)) { [void]$Script:ExcludedDirNames.Add($d) }

# File names (or extensions) that may hold credentials and are never exported.
$Script:SecretFileNames = @(
    'local.properties', '.env', 'keystore.properties', 'signing.properties', 'secrets.properties',
    'gradle.properties.local', 'gradle-local.properties'
)
$Script:SecretFileExtensions = @(
    '.jks', '.keystore', '.p12', '.pfx', '.pem', '.key', '.asc', '.ppk', '.der', '.gpg', '.kdbx'
)

# Extensions treated as binary: content is never exported, only inventoried.
$Script:BinaryExtensions = @(
    '.so', '.dylib', '.dll', '.a', '.o', '.jar', '.aar', '.apk', '.aab', '.apks', '.xapk',
    '.dex', '.odex', '.vdex', '.class', '.onnx', '.tflite', '.pb', '.pt', '.pth',
    '.zip', '.xz', '.7z', '.rar', '.tar', '.gz', '.bz2', '.lzma', '.zst', '.bundle',
    '.png', '.jpg', '.jpeg', '.webp', '.gif', '.bmp', '.ico', '.cur',
    '.ttf', '.otf', '.woff', '.woff2', '.eot',
    '.mp3', '.mp4', '.wav', '.flac', '.ogg', '.avi', '.mkv', '.webm',
    '.pdf', '.bin', '.dat', '.db', '.sqlite', '.pfb', '.unity3d', '.exp', '.pak'
)

# Extensions always treated as text.
$Script:TextExtensions = @(
    '.kt', '.java', '.kts', '.gradle', '.pro', '.r8', '.rules', '.xml', '.c', '.h', '.cpp', '.cc',
    '.cxx', '.hpp', '.hh', '.hxx', '.mm', '.m', '.sh', '.bash', '.zsh', '.bat', '.cmd', '.ps1',
    '.psm1', '.json', '.toml', '.properties', '.md', '.markdown', '.mdx', '.txt', '.text',
    '.html', '.htm', '.css', '.js', '.mjs', '.cjs', '.ts', '.cmake', '.list', '.d', '.s', '.asm',
    '.yml', '.yaml', '.cfg', '.ini', '.conf', '.editorconfig', '.sql', '.csv', '.svg',
    '.gitignore', '.gitattributes', '.proguard', '.template', '.tmpl'
)

# Generated profiling artefacts: large, machine produced, no editorial value.
$Script:GeneratedArtefactNames = @('baseline-prof.txt', 'startup-prof.txt', 'baseline-prof.jar')

# Third-party source trees that can be skipped with -ExcludeVendoredSources.
$Script:VendoredRoots = @(
    'app/src/main/java/org/libreoffice',
    'app/src/main/java/android',
    'app/src/main/java/com/ahmadullahpk',
    'app/src/main/java/com/techflyers'
)

# Test source roots that can be skipped with -ExcludeTests.
$Script:TestRoots = @(
    'app/src/test', 'app/src/androidTest', 'app/src/testFixtures',
    'baselineProfile/src', 'terminal-emulator/src/test', 'terminal-view/src/test'
)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

function Get-RelativePath {
    param([string] $Base, [string] $Full)
    $b = $Base.TrimEnd('\', '/')
    if ($Full.StartsWith($b, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $Full.Substring($b.Length).TrimStart('\', '/').Replace('\', '/')
    }
    return $Full.Replace('\', '/')
}

function Test-IsUnder {
    param([string] $Root, [string] $Path)
    $r = $Root.TrimEnd('\', '/')
    return $Path.StartsWith($r + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)
}

function Get-RelativeExtensions {
    param([string] $RelativePath)
    $name = [System.IO.Path]::GetFileName($RelativePath)
    if ($name -match '^\.[A-Za-z0-9_]+$') { return $name.ToLowerInvariant() }
    $ext = [System.IO.Path]::GetExtension($name)
    if ([string]::IsNullOrEmpty($ext)) { return '' }
    return $ext.ToLowerInvariant()
}

function Test-IsTextFile {
    <# Sniffs the first 4 KiB for NUL bytes. Used for files with unknown or no extension. #>
    param([string] $Path)
    try {
        $fs = [System.IO.File]::OpenRead($Path)
        try {
            $len = [Math]::Min(4096, $fs.Length)
            if ($len -le 0) { return $true }
            $buf = New-Object byte[] $len
            [void]$fs.Read($buf, 0, $len)
            foreach ($b in $buf) { if ($b -eq 0) { return $false } }
            return $true
        } finally { $fs.Dispose() }
    } catch { return $false }
}

function Get-FileSha256 {
    param([string] $Path)
    try { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
    catch { return 'unavailable' }
}

function Get-LineCount {
    param([string] $Content)
    if ([string]::IsNullOrEmpty($Content)) { return 0 }
    return ($Content -split "`n").Count
}

function Format-Bytes {
    param([long] $Bytes)
    if ($Bytes -lt 1024) { return "$Bytes B" }
    if ($Bytes -lt 1MB) { return ('{0:N1} KiB' -f ($Bytes / 1KB)) }
    if ($Bytes -lt 1GB) { return ('{0:N2} MiB' -f ($Bytes / 1MB)) }
    return ('{0:N2} GiB' -f ($Bytes / 1GB))
}

function Invoke-ProjectScan {
    <#
        Walks the repository, pruning generated/cache directories as it goes.
        Returns the discovered files plus the directories that were pruned.
    #>
    param([string] $Root, [string] $ExcludePath)

    $files        = New-Object 'System.Collections.Generic.List[object]'
    $excludedDirs = New-Object 'System.Collections.Generic.List[object]'
    $stack        = New-Object 'System.Collections.Generic.Stack[string]'
    $stack.Push($Root)

    while ($stack.Count -gt 0) {
        $dir = $stack.Pop()
        $subdirs = @()
        try { $subdirs = @(Get-ChildItem -LiteralPath $dir -Directory -Force -ErrorAction SilentlyContinue) } catch { $subdirs = @() }
        foreach ($sub in ($subdirs | Sort-Object Name)) {
            $rel = Get-RelativePath -Base $Root -Full $sub.FullName
            if ($Script:ExcludedDirNames.Contains($sub.Name)) {
                $excludedDirs.Add([pscustomobject]@{ Path = $rel; Reason = 'generated build output, cache or VCS internal directory' })
                continue
            }
            if ($ExcludePath -and $sub.FullName.TrimEnd('\') -eq $ExcludePath.TrimEnd('\')) {
                $excludedDirs.Add([pscustomobject]@{ Path = $rel; Reason = 'the AI context export output directory itself' })
                continue
            }
            if ($sub.Parent.FullName.TrimEnd('\') -eq $Root.TrimEnd('\') -and
                $sub.Name.StartsWith($Script:ExportDirName, [System.StringComparison]::OrdinalIgnoreCase)) {
                $excludedDirs.Add([pscustomobject]@{ Path = $rel; Reason = 'a previous AI context export directory (never exported into another export)' })
                continue
            }
            $stack.Push($sub.FullName)
        }

        $subfiles = @()
        try { $subfiles = @(Get-ChildItem -LiteralPath $dir -File -Force -ErrorAction SilentlyContinue) } catch { $subfiles = @() }
        foreach ($f in ($subfiles | Sort-Object Name)) { $files.Add($f) }
    }

    return [pscustomobject]@{ Files = $files; ExcludedDirs = $excludedDirs }
}

function Get-ImageDimensions {
    <# Returns "WxH" for common raster formats, or $null when it cannot be determined. #>
    param([string] $Path, [string] $Extension)

    try {
        switch ($Extension) {
            '.png' {
                $fs = [System.IO.File]::OpenRead($Path)
                try {
                    $buf = New-Object byte[] 24
                    if ($fs.Read($buf, 0, 24) -lt 24) { return $null }
                    $w = [int]$buf[16] * 16777216 + [int]$buf[17] * 65536 + [int]$buf[18] * 256 + [int]$buf[19]
                    $h = [int]$buf[20] * 16777216 + [int]$buf[21] * 65536 + [int]$buf[22] * 256 + [int]$buf[23]
                    return "${w}x${h}"
                } finally { $fs.Dispose() }
            }
            '.gif' {
                $b = [System.IO.File]::ReadAllBytes($Path)
                if ($b.Length -lt 10) { return $null }
                $w = [int]$b[6] + ([int]$b[7] * 256)
                $h = [int]$b[8] + ([int]$b[9] * 256)
                return "${w}x${h}"
            }
            '.bmp' {
                $b = [System.IO.File]::ReadAllBytes($Path)
                if ($b.Length -lt 26) { return $null }
                $w = [System.BitConverter]::ToInt32($b, 18)
                $h = [System.BitConverter]::ToInt32($b, 22)
                return "${w}x${[Math]::Abs($h)}"
            }
            '.ico' {
                $b = [System.IO.File]::ReadAllBytes($Path)
                if ($b.Length -lt 8) { return $null }
                $w = if ($b[6] -eq 0) { 256 } else { [int]$b[6] }
                $h = if ($b[7] -eq 0) { 256 } else { [int]$b[7] }
                return "${w}x${h}"
            }
            '.jpg'  { return (Get-JpegDimensions -Path $Path) }
            '.webp' { return (Get-WebPDimensions -Path $Path) }
            default { return $null }
        }
    } catch { return $null }
}

function Get-JpegDimensions {
    param([string] $Path)
    $b = [System.IO.File]::ReadAllBytes($Path)
    $i = 2
    while ($i -lt $b.Length - 9) {
        if ($b[$i] -ne 0xFF) { $i++; continue }
        $marker = $b[$i + 1]
        if ($marker -ge 0xC0 -and $marker -le 0xCF -and $marker -notin @(0xC4, 0xC8, 0xCC)) {
            $h = ([int]$b[$i + 5] * 256) + [int]$b[$i + 6]
            $w = ([int]$b[$i + 7] * 256) + [int]$b[$i + 8]
            return "${w}x${h}"
        }
        $seg = ([int]$b[$i + 2] * 256) + [int]$b[$i + 3]
        $i += 2 + $seg
    }
    return $null
}

function Get-WebPDimensions {
    param([string] $Path)
    $b = [System.IO.File]::ReadAllBytes($Path)
    if ($b.Length -lt 30) { return $null }
    $fourcc = [System.Text.Encoding]::ASCII.GetString($b, 12, 4)
    switch ($fourcc) {
        'VP8X' {
            $w = 1 + [int]$b[24] + ([int]$b[25] * 256) + ([int]$b[26] * 65536)
            $h = 1 + [int]$b[27] + ([int]$b[28] * 256) + ([int]$b[29] * 65536)
            return "${w}x${h}"
        }
        'VP8 ' {
            $w = (([int]$b[26] + ([int]$b[27] * 256)) -band 0x3FFF)
            $h = (([int]$b[28] + ([int]$b[29] * 256)) -band 0x3FFF)
            return "${w}x${h}"
        }
        'VP8L' {
            $bits = [int]$b[21] + ([int]$b[22] * 256) + ([int]$b[23] * 65536) + ([int]$b[24] * 16777216)
            $w = ($bits -band 0x3FFF) + 1
            $h = (($bits -shr 14) -band 0x3FFF) + 1
            return "${w}x${h}"
        }
        default { return $null }
    }
}

function Get-VectorDrawableDimensions {
    <# Reads android:width/height or viewportWidth/viewportHeight from a VectorDrawable. #>
    param([string] $Path)
    try {
        $head = Get-Content -LiteralPath $Path -Raw -ErrorAction Stop
        $w = [regex]::Match($head, 'android:width\s*=\s*"([^"]+)"')
        $h = [regex]::Match($head, 'android:height\s*=\s*"([^"]+)"')
        if ($w.Success -and $h.Success) { return ("{0} x {1}" -f $w.Groups[1].Value, $h.Groups[1].Value) }
        $vw = [regex]::Match($head, 'android:viewportWidth\s*=\s*"([^"]+)"')
        $vh = [regex]::Match($head, 'android:viewportHeight\s*=\s*"([^"]+)"')
        if ($vw.Success -and $vh.Success) { return ("viewport {0} x {1}" -f $vw.Groups[1].Value, $vh.Groups[1].Value) }
    } catch { }
    return $null
}

# ---------------------------------------------------------------------------
# Secret detection and redaction
# ---------------------------------------------------------------------------

# Credential shapes that are unambiguous. These are redacted wherever they appear.
$Script:HighConfidenceRules = @(
    @{ Name = 'PEM private key block'
       Pattern = '(?s)-----BEGIN[A-Z ]*PRIVATE KEY-----.*?-----END[A-Z ]*PRIVATE KEY-----'
       Replacement = '<<REDACTED:PRIVATE_KEY_BLOCK>>' },
    @{ Name = 'AWS access key id'
       Pattern = '\b(?:AKIA|ASIA|ABIA|ACCA)[0-9A-Z]{16}\b'
       Replacement = '<<REDACTED:AWS_ACCESS_KEY_ID>>' },
    @{ Name = 'Google API key'
       Pattern = '\bAIza[0-9A-Za-z\-_]{30,}\b'
       Replacement = '<<REDACTED:GOOGLE_API_KEY>>' },
    @{ Name = 'GitHub access token'
       Pattern = '\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36,}\b'
       Replacement = '<<REDACTED:GITHUB_TOKEN>>' },
    @{ Name = 'GitHub fine-grained token'
       Pattern = '\bgithub_pat_[A-Za-z0-9_]{20,}\b'
       Replacement = '<<REDACTED:GITHUB_TOKEN>>' },
    @{ Name = 'OpenAI-style secret key'
       Pattern = '\bsk-[A-Za-z0-9]{20,}\b'
       Replacement = '<<REDACTED:API_SECRET_KEY>>' },
    @{ Name = 'Slack token'
       Pattern = '\bxox[baprs]-[A-Za-z0-9-]{10,}\b'
       Replacement = '<<REDACTED:SLACK_TOKEN>>' },
    @{ Name = 'JSON Web Token'
       Pattern = '\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b'
       Replacement = '<<REDACTED:JWT>>' },
    @{ Name = 'Bearer authorization value'
       Pattern = '(?i)\bBearer[ \t]+[A-Za-z0-9._~+/=-]{20,}'
       Replacement = 'Bearer <<REDACTED:BEARER_TOKEN>>' },
    @{ Name = 'Credential embedded in URL'
       Pattern = '(?i)\b([a-z][a-z0-9+.-]{2,12})://([^\s/:@]{1,64}):([^\s/@]{3,128})@'
       Replacement = '$1://$2:<<REDACTED:URL_PASSWORD>>@' },
    @{ Name = 'Npm access token'
       Pattern = '\bnpm_[A-Za-z0-9]{30,}\b'
       Replacement = '<<REDACTED:NPM_TOKEN>>' },
    @{ Name = 'SendGrid API key'
       Pattern = '\bSG\.[A-Za-z0-9_\-]{16,}\.[A-Za-z0-9_\-]{16,}\b'
       Replacement = '<<REDACTED:SENDGRID_API_KEY>>' }
)

# Key=value / key: value assignments whose key names a credential.
$Script:SecretAssignmentPattern = @'
(?i)^(?<pre>[ \t]*(?:(?:export|set|val|var|let|const|object|companion|public|private|protected|internal|static|final|lateinit|open|override|suspend|inline|operator|infix)[ \t]+)*["']?(?<key>[A-Za-z0-9_.\-\[\]:]*(?:password|passwd|pwd|secret|api[_-]?key|apikey|access[_-]?key|private[_-]?key|auth[_-]?token|access[_-]?token|client[_-]?secret|credential|storepassword|keypassword)[A-Za-z0-9_.\-\[\]:]*)["']?[ \t]*[:=][ \t]*)(?<value>"[^"\r\n]*"|'[^'\r\n]*'|[^\s,;#\)\]\}]+)
'@

$Script:PlaceholderValues = @(
    'null', 'none', 'nil', 'true', 'false', 'empty', 'blank', 'changeme', 'change_me',
    'change-me', 'placeholder', 'redacted', 'redact', 'todo', 'fixme', 'xxx', 'xxxx',
    'example', 'sample', 'dummy', 'test', 'default', 'password', 'passwd', 'secret',
    'apikey', 'api_key', 'token', 'mypassword', 'yourpassword', 'your_password',
    'your_api_key', 'your-secret', 'your_api_key_here', '...'
)

# Values that are obviously stand-ins rather than real credentials.
$Script:PlaceholderWordPattern = '(?i)(^|_)(your|replace|example|sample|placeholder|insert|fill_?in|fillme|todo|fixme|dummy|fake|not_?real|test_?only|unset|undefined|no_?value)($|_)'

# File types where a credential-named key is treated strictly: any non-placeholder
# literal is redacted, because these files exist to hold configuration values.
$Script:StrictExtensions = @('.properties', '.env', '.ini', '.cfg', '.conf', '.toml')

function Get-ShannonEntropy {
    param([string] $Text)
    if ([string]::IsNullOrEmpty($Text)) { return 0.0 }
    $counts = @{}
    foreach ($c in $Text.ToCharArray()) {
        $k = [string]$c
        if ($counts.ContainsKey($k)) { $counts[$k] = $counts[$k] + 1 } else { $counts[$k] = 1 }
    }
    $entropy = 0.0
    foreach ($v in $counts.Values) {
        $p = $v / [double]$Text.Length
        $entropy -= $p * [Math]::Log($p, 2)
    }
    return $entropy
}

function Test-IsLikelyCredential {
    <#
        Decides whether a captured value is a real credential rather than a code
        expression, an identifier reference or a placeholder.

        The distinction that matters: a credential is a literal that was written down,
        while "parameters?.password" or "globalClass.apiKey" are expressions that merely
        mention a credential. Confusing the two would either leak a secret or corrupt
        otherwise readable source code, so both directions are tested explicitly.
    #>
    param([string] $RawValue, [switch] $Strict)

    $v = $RawValue.Trim()
    $wasQuoted = $false
    if ($v.Length -ge 2) {
        $first = $v[0]; $last = $v[$v.Length - 1]
        if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
            $inner = $v.Substring(1, $v.Length - 2)
            $wasQuoted = $true
        } else { $inner = $v }
    } else { $inner = $v }

    if ([string]::IsNullOrWhiteSpace($inner)) { return $false }
    if ($Script:PlaceholderValues -contains $inner.ToLowerInvariant()) { return $false }
    if ($inner -match $Script:PlaceholderWordPattern) { return $false }
    if ($inner -match '^\$\{?.*\}?$') { return $false }
    if ($inner -match '^[<\[].*[>\]]$') { return $false }
    if ($inner -eq '*' -or $inner -eq '***') { return $false }

    if ($Strict) {
        # Configuration files: redact any literal of plausible length.
        return ($inner.Length -ge 4)
    }

    if (-not $wasQuoted) {
        # An unquoted token containing operator characters is a code expression
        # (Kotlin safe calls "?.", the Elvis operator "?:", "!!", method calls, ...).
        if ($inner -match '[?!\(\)\[\]\{\}<>=+\-*/&|^~;,@`]') { return $false }
    }

    # Code references are not credentials.
    if ($inner -match '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$') { return $false }
    if ($inner -match '^(R|android|androidx|com|org|java|javax|kotlin|kotlinx)\.') { return $false }
    if ($inner.Contains('(')) { return $false }

    # A bare identifier is a reference, not a written-down secret - unless it is long
    # enough that it is implausible as an identifier.
    if ($inner -match '^[A-Za-z_][A-Za-z_]*$' -and $inner.Length -lt 16) { return $false }

    if ($inner.Length -lt 8) { return $false }

    $distinct = @($inner.ToCharArray() | Select-Object -Unique).Count
    if ($distinct -lt 5) { return $false }

    if ((Get-ShannonEntropy -Text $inner) -ge 3.0) { return $true }

    # Mixed letters and digits is a strong credential signal even at lower entropy.
    if ($inner -match '[A-Za-z]' -and $inner -match '[0-9]') { return $true }

    return $false
}

function Test-ValueEchoesKey {
    <#
        True when the captured value is simply the key name repeated, e.g.
        const val EXTRA_ARCHIVE_PASSWORD = "extra_archive_password".
        Such a value is an Intent extra / preference KEY, not a credential.
    #>
    param([string] $Key, [string] $RawValue)

    $v = $RawValue.Trim()
    if ($v.Length -ge 2) {
        $first = $v[0]; $last = $v[$v.Length - 1]
        if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
            $v = $v.Substring(1, $v.Length - 2)
        }
    }
    $normalKey = ($Key    -replace '[^A-Za-z0-9]', '').ToUpperInvariant()
    $normalVal = ($v.Trim() -replace '[^A-Za-z0-9]', '').ToUpperInvariant()
    if ($normalKey.Length -eq 0 -or $normalVal.Length -eq 0) { return $false }
    return ($normalKey -eq $normalVal)
}

function Protect-Secrets {
    <#
        Redacts credentials from a text blob. Returns the cleaned text and records
        every redaction in the supplied log list.
    #>
    param(
        [string] $Content,
        [string] $RelativePath,
        [string] $Extension,
        [System.Collections.Generic.List[object]] $Log
    )

    if ([string]::IsNullOrEmpty($Content)) { return $Content }
    $result = $Content

    foreach ($rule in $Script:HighConfidenceRules) {
        $rx = New-Object System.Text.RegularExpressions.Regex($rule.Pattern)
        $found = $rx.Matches($result)
        if ($found.Count -gt 0) {
            $result = $rx.Replace($result, $rule.Replacement)
            $Log.Add([pscustomobject]@{
                File = $RelativePath; Rule = $rule.Name; Count = $found.Count; Kind = 'literal credential'
            })
        }
    }

    $strict = $Script:StrictExtensions -contains $Extension
    $rxAssign = New-Object System.Text.RegularExpressions.Regex($Script:SecretAssignmentPattern)
    $lines = $result -split "`r?`n", 0
    $out = New-Object 'System.Collections.Generic.List[string]'
    $assignmentHits = 0

    foreach ($line in $lines) {
        $m = $rxAssign.Match($line)
        if ($m.Success) {
            $raw = $m.Groups['value'].Value
            $keyName = $m.Groups['key'].Value
            $isCredential = (-not (Test-ValueEchoesKey -Key $keyName -RawValue $raw)) -and
                            (Test-IsLikelyCredential -RawValue $raw -Strict:$strict)
            if ($isCredential) {
                $quote = ''
                if ($raw.Length -ge 2) {
                    $first = $raw[0]; $last = $raw[$raw.Length - 1]
                    if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
                        $quote = $first
                    }
                }
                $replacement = "$quote<<REDACTED:CREDENTIAL_VALUE>>$quote"
                $idx = $m.Groups['value'].Index
                $len = $m.Groups['value'].Length
                $line = $line.Substring(0, $idx) + $replacement + $line.Substring($idx + $len)
                $assignmentHits++
            }
        }
        $out.Add($line)
    }

    if ($assignmentHits -gt 0) {
        $result = ($out -join "`n")
        $Log.Add([pscustomobject]@{
            File = $RelativePath; Rule = 'credential-named assignment'; Count = $assignmentHits; Kind = 'assigned value'
        })
    }

    return $result
}

# ---------------------------------------------------------------------------
# Git, Gradle catalogue and manifest inspection
# ---------------------------------------------------------------------------

function Test-GitAvailable {
    return ($null -ne (Get-Command git -ErrorAction SilentlyContinue))
}

function Invoke-GitText {
    param([string] $RepoRoot, [string[]] $GitArgs)
    if (-not (Test-GitAvailable)) { return '' }
    try {
        $out = & git -C $RepoRoot @GitArgs 2>&1
        if ($LASTEXITCODE -ne 0) { return '' }
        return (($out | Out-String).TrimEnd())
    } catch { return '' }
}

function Get-GitFacts {
    <# Collects branch, HEAD, working-tree state and recent history. #>
    param([string] $RepoRoot)

    $facts = [pscustomobject]@{
        Available = (Test-GitAvailable)
        Branch = 'unknown'
        Head = 'unknown'
        Status = ''
        StatusBranch = ''
        DiffStat = ''
        DiffCachedStat = ''
        RecentLog = ''
        Remotes = ''
        TrackedCount = 0
        Untracked = @()
        Modified = @()
        Staged = @()
        Deleted = @()
    }
    if (-not $facts.Available) { return $facts }

    $facts.Branch         = (Invoke-GitText -RepoRoot $RepoRoot -GitArgs @('rev-parse', '--abbrev-ref', 'HEAD'))
    $facts.Head           = (Invoke-GitText -RepoRoot $RepoRoot -GitArgs @('log', '-1', '--format=%H%n%h%n%an <%ae>%n%ad%n%s', '--date=iso'))
    $facts.Status         = (Invoke-GitText -RepoRoot $RepoRoot -GitArgs @('status', '--porcelain=v1', '-uall'))
    $facts.StatusBranch   = (Invoke-GitText -RepoRoot $RepoRoot -GitArgs @('status', '--short', '--branch'))
    $facts.DiffStat       = (Invoke-GitText -RepoRoot $RepoRoot -GitArgs @('diff', '--stat'))
    $facts.DiffCachedStat = (Invoke-GitText -RepoRoot $RepoRoot -GitArgs @('diff', '--cached', '--stat'))
    $facts.RecentLog      = (Invoke-GitText -RepoRoot $RepoRoot -GitArgs @('log', '--oneline', '--decorate', '-30'))
    $facts.Remotes        = (Invoke-GitText -RepoRoot $RepoRoot -GitArgs @('remote', '-v'))

    $tracked = (Invoke-GitText -RepoRoot $RepoRoot -GitArgs @('ls-files'))
    if ($tracked) { $facts.TrackedCount = @($tracked -split "`n" | Where-Object { $_ -ne '' }).Count }

    foreach ($line in @($facts.Status -split "`n")) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $code = $line.Substring(0, 2)
        $path = $line.Substring(3).Trim()
        if ($code -eq '??') { $facts.Untracked += $path }
        elseif ($code.Contains('D')) { $facts.Deleted += $path }
        elseif ($code.Contains('M')) { $facts.Modified += $path }
        if ($code[0] -ne ' ' -and $code[0] -ne '?') { $facts.Staged += $path }
    }
    return $facts
}

function Get-VersionCatalog {
    <# Parses gradle/libs.versions.toml into versions, libraries and plugins. #>
    param([string] $Path)

    $result = [pscustomobject]@{ Versions = @{}; Libraries = @{}; Plugins = @{} }
    if (-not (Test-Path -LiteralPath $Path)) { return $result }

    $section = ''
    foreach ($raw in (Get-Content -LiteralPath $Path)) {
        $line = $raw.Trim()
        if ($line -match '^\[(.+)\]$') { $section = $matches[1].ToLowerInvariant(); continue }
        if ($line -eq '' -or $line.StartsWith('#')) { continue }

        if ($section -eq 'versions') {
            if ($line -match '^([A-Za-z0-9_.\-]+)\s*=\s*"?([^"#]+?)"?$') {
                $result.Versions[$matches[1]] = $matches[2].Trim()
            }
        }
        elseif ($section -eq 'libraries') {
            if ($line -match '^([A-Za-z0-9_.\-]+)\s*=\s*\{(.+)\}') {
                $alias = $matches[1]; $body = $matches[2]
                $group = ''; $name = ''; $ver = ''; $versionSource = ''
                if ($body -match 'group\s*=\s*"([^"]+)"') { $group = $matches[1] }
                if ($body -match 'name\s*=\s*"([^"]+)"') { $name = $matches[1] }
                if ($body -match 'module\s*=\s*"([^"]+)"') {
                    $module = $matches[1]
                    if ($module -match '^(.+):(.+)$') { $group = $matches[1]; $name = $matches[2] }
                }
                if ($body -match 'version\.ref\s*=\s*"([^"]+)"') {
                    $ref = $matches[1]; $versionSource = "catalog:$ref"
                    if ($result.Versions.ContainsKey($ref)) { $ver = $result.Versions[$ref] } else { $ver = $ref }
                }
                elseif ($body -match 'version\s*=\s*"([^"]+)"') {
                    $ver = $matches[1]; $versionSource = 'literal'
                }
                else { $versionSource = 'versionless (managed by BOM/platform)' }
                $result.Libraries[$alias] = [pscustomobject]@{
                    Alias = $alias; Group = $group; Name = $name; Version = $ver; VersionSource = $versionSource
                }
            }
        }
        elseif ($section -eq 'plugins') {
            if ($line -match '^([A-Za-z0-9_.\-]+)\s*=\s*\{(.+)\}') {
                $alias = $matches[1]; $body = $matches[2]
                $id = ''; $ver = ''
                if ($body -match 'id\s*=\s*"([^"]+)"') { $id = $matches[1] }
                if ($body -match 'version\.ref\s*=\s*"([^"]+)"') {
                    $ref = $matches[1]
                    if ($result.Versions.ContainsKey($ref)) { $ver = $result.Versions[$ref] } else { $ver = $ref }
                }
                elseif ($body -match 'version\s*=\s*"([^"]+)"') { $ver = $matches[1] }
                $result.Plugins[$alias] = [pscustomobject]@{ Alias = $alias; Id = $id; Version = $ver }
            }
        }
    }
    return $result
}

function Get-VersionCatalogUsage {
    <# Maps each catalog alias to the modules that reference it. #>
    param([string] $RepoRoot, [string[]] $BuildFiles)

    $usage = @{}
    foreach ($bf in $BuildFiles) {
        if (-not (Test-Path -LiteralPath $bf)) { continue }
        $module = Get-RelativePath -Base $RepoRoot -Full $bf
        $content = Get-Content -LiteralPath $bf -Raw
        foreach ($m in [regex]::Matches($content, 'libs\.([A-Za-z0-9_.\-]+)')) {
            $alias = $m.Groups[1].Value.Replace('.', '-')
            if (-not $usage.ContainsKey($alias)) { $usage[$alias] = New-Object 'System.Collections.Generic.List[string]' }
            if (-not $usage[$alias].Contains($module)) { $usage[$alias].Add($module) }
        }
    }
    return $usage
}

function Get-ManifestFacts {
    <# Extracts permissions and component declarations from an AndroidManifest. #>
    param([string] $Path)

    $facts = [pscustomobject]@{
        Permissions = @(); Activities = @(); Services = @(); Receivers = @(); Providers = @()
        Features = @(); Package = ''
    }
    if (-not (Test-Path -LiteralPath $Path)) { return $facts }

    $xml = Get-Content -LiteralPath $Path -Raw
    foreach ($m in [regex]::Matches($xml, '<uses-permission[^>]*android:name\s*=\s*"([^"]+)"')) {
        $facts.Permissions += $m.Groups[1].Value
    }
    foreach ($m in [regex]::Matches($xml, '<uses-feature[^>]*android:name\s*=\s*"([^"]+)"')) {
        $facts.Features += $m.Groups[1].Value
    }
    foreach ($pair in @(@('activity', 'Activities'), @('activity-alias', 'Activities'), @('service', 'Services'), @('receiver', 'Receivers'), @('provider', 'Providers'))) {
        $tag = $pair[0]; $prop = $pair[1]
        foreach ($m in [regex]::Matches($xml, ('<' + $tag + '\b[^>]*>'))) {
            $n = [regex]::Match($m.Value, 'android:name\s*=\s*"([^"]+)"')
            if ($n.Success) {
                $target = @($facts.PSObject.Properties[$prop].Value) + $n.Groups[1].Value
                $facts.PSObject.Properties[$prop].Value = $target
            }
        }
    }
    $pkg = [regex]::Match($xml, '<manifest[^>]*package\s*=\s*"([^"]+)"')
    if ($pkg.Success) { $facts.Package = $pkg.Groups[1].Value }
    return $facts
}

function Get-GradleModules {
    <# Reads the module list from settings.gradle(.kts). #>
    param([string] $RepoRoot)
    $modules = @()
    $settings = Join-Path $RepoRoot 'settings.gradle.kts'
    if (-not (Test-Path -LiteralPath $settings)) { $settings = Join-Path $RepoRoot 'settings.gradle' }
    if (Test-Path -LiteralPath $settings) {
        foreach ($m in [regex]::Matches((Get-Content -LiteralPath $settings -Raw), 'include\s*\(?\s*":([A-Za-z0-9_\-]+)"')) {
            $modules += $m.Groups[1].Value
        }
    }
    if ($modules.Count -eq 0) { $modules = @('app') }
    return $modules
}

function Get-CallArguments {
    <#
        Extracts the string literals passed to a call such as proguardFiles(...).
        Nested parentheses are balanced, so a call like
            proguardFiles(getDefaultProguardFile("a.txt"), "b.pro")
        yields both "a.txt" and "b.pro" instead of stopping at the first ")".
    #>
    param([string] $Content, [string] $Call)

    $results = @()
    $rx = [regex]::new([regex]::Escape($Call) + '\s*\(')
    foreach ($m in $rx.Matches($Content)) {
        $start = $m.Index + $m.Length
        $depth = 1
        $i = $start
        while ($i -lt $Content.Length -and $depth -gt 0) {
            $ch = $Content[$i]
            if ($ch -eq '(') { $depth++ }
            elseif ($ch -eq ')') { $depth-- }
            $i++
        }
        $args = $Content.Substring($start, $i - $start - 1)
        foreach ($s in [regex]::Matches($args, '"([^"]+)"')) { $results += $s.Groups[1].Value }
    }
    return $results
}

function Get-GradleFacts {
    <# Reads key build settings out of a module's build.gradle(.kts) without a Gradle run. #>
    param([string] $RepoRoot, [string] $Module)

    $facts = [pscustomobject]@{
        Namespace = ''; ApplicationId = ''; CompileSdk = ''; MinSdk = ''; TargetSdk = ''
        VersionCode = ''; VersionName = ''; NdkVersion = ''; Abis = @(); PluginType = ''
        Minify = ''; ShrinkResources = ''; ProguardFiles = @(); JavaVersion = ''
        KotlinApi = ''; UniversalApk = ''; DirectDependencies = @()
    }

    $moduleDir = Join-Path $RepoRoot $Module
    $buildFile = Join-Path $moduleDir 'build.gradle.kts'
    if (-not (Test-Path -LiteralPath $buildFile)) { $buildFile = Join-Path $moduleDir 'build.gradle' }
    if (-not (Test-Path -LiteralPath $buildFile)) { return $facts }

    $content = Get-Content -LiteralPath $buildFile -Raw

    foreach ($pair in @(
        @('Namespace', 'namespace\s*=\s*"([^"]+)"'),
        @('ApplicationId', 'applicationId\s*=\s*"([^"]+)"'),
        @('CompileSdk', 'compileSdk(?:Version)?\s*=\s*"?([0-9]+)"?'),
        @('MinSdk', 'minSdk(?:Version)?\s*=\s*"?([0-9]+)"?'),
        @('TargetSdk', 'targetSdk(?:Version)?\s*=\s*"?([0-9]+)"?'),
        @('VersionCode', 'versionCode\s*=\s*"?([0-9]+)"?'),
        @('VersionName', 'versionName\s*=\s*"([^"]+)"'),
        @('NdkVersion', 'ndkVersion\s*=\s*"([^"]+)"'),
        @('JavaVersion', 'sourceCompatibility\s*=\s*JavaVersion\.VERSION_([0-9_]+)'),
        @('KotlinApi', 'apiVersion\s*=\s*"([^"]+)"')
    )) {
        $m = [regex]::Match($content, $pair[1])
        if ($m.Success) { $facts.($pair[0]) = $m.Groups[1].Value }
    }

    $abiBlock = [regex]::Match($content, 'abiFilters\s*(?:\+=|=)\s*(?:listOf\()?\s*((?:"[a-z0-9_\-]+"\s*,?\s*)+)')
    if ($abiBlock.Success) {
        foreach ($m in [regex]::Matches($abiBlock.Groups[1].Value, '"([^"]+)"')) { $facts.Abis += $m.Groups[1].Value }
    }
    $splitBlock = [regex]::Match($content, 'splits\s*\{.*?abi\s*\{(.*?)\n\s*\}', 'Singleline')
    if ($splitBlock.Success) {
        foreach ($m in [regex]::Matches($splitBlock.Groups[1].Value, 'include\s*\(([^)]*)\)')) {
            foreach ($a in [regex]::Matches($m.Groups[1].Value, '"([^"]+)"')) { $facts.Abis += $a.Groups[1].Value }
        }
        $u = [regex]::Match($splitBlock.Groups[1].Value, 'isUniversalApk\s*=\s*(true|false)')
        if ($u.Success) { $facts.UniversalApk = $u.Groups[1].Value }
    }
    if ($content -match 'isMinifyEnabled\s*=\s*true' -or $content -match 'minifyEnabled\s+true') { $facts.Minify = 'true' }
    if ($content -match 'isShrinkResources\s*=\s*true') { $facts.ShrinkResources = 'true' }
    if ($content -match 'com\.android\.application') { $facts.PluginType = 'com.android.application' }
    elseif ($content -match 'com\.android\.library') { $facts.PluginType = 'com.android.library' }
    elseif ($content -match 'com\.android\.test') { $facts.PluginType = 'com.android.test' }

    foreach ($p in (Get-CallArguments -Content $content -Call 'proguardFiles')) {
        $facts.ProguardFiles += $p
    }
    foreach ($m in [regex]::Matches($content, '(?m)^\s*(implementation|api|compileOnly|runtimeOnly|testImplementation|androidTestImplementation)\s*\(\s*"([^"]+)"\s*\)')) {
        $facts.DirectDependencies += ("{0} {1}" -f $m.Groups[1].Value, $m.Groups[2].Value)
    }
    return $facts
}

# ---------------------------------------------------------------------------
# Section writers
# ---------------------------------------------------------------------------

function New-SectionBuilder {
    return (New-Object 'System.Text.StringBuilder')
}

function Add-Line {
    param([System.Text.StringBuilder] $Builder, [string] $Text = '')
    # LF is used explicitly so the whole bundle has one consistent line ending
    # regardless of the platform that generated it.
    [void]$Builder.Append($Text)
    [void]$Builder.Append("`n")
}

function Add-Heading {
    param([System.Text.StringBuilder] $Builder, [string] $Text)
    Add-Line -Builder $Builder
    Add-Line -Builder $Builder ('=' * 100)
    Add-Line -Builder $Builder $Text
    Add-Line -Builder $Builder ('=' * 100)
}

function Add-FileBlock {
    <#
        Emits one file with an explicit, unambiguous header and footer so that a
        reader can always tell where a file starts and ends.
    #>
    param(
        [System.Text.StringBuilder] $Builder,
        [string] $RelativePath,
        [string] $Content,
        [string] $Kind,
        [long] $OriginalBytes,
        [string] $Sha256,
        [int] $Redactions
    )

    $lineCount = Get-LineCount -Content $Content
    Add-Line -Builder $Builder ('-' * 100)
    Add-Line -Builder $Builder ("FILE  : $RelativePath")
    Add-Line -Builder $Builder ("KIND  : $Kind")
    Add-Line -Builder $Builder ("SIZE  : $OriginalBytes bytes on disk   LINES: $lineCount   SHA-256: $Sha256")
    if ($Redactions -gt 0) { Add-Line -Builder $Builder ("NOTE  : $Redactions secret value(s) in this file were redacted.") }
    Add-Line -Builder $Builder ('-' * 100)
    Add-Line -Builder $Builder $Content
    Add-Line -Builder $Builder ('-' * 100)
    Add-Line -Builder $Builder ("END OF FILE: $RelativePath")
    Add-Line -Builder $Builder ('-' * 100)
}

function Write-ChunkedSection {
    <#
        Writes one logical section to disk, splitting it into numbered parts when it
        exceeds the size limit. Files are never truncated: an individual file that is
        larger than the limit always occupies a whole part on its own.
    #>
    param(
        [string] $BaseName,
        [System.Collections.Generic.List[object]] $Entries,
        [string] $OutDir,
        [long] $MaxBytes,
        [System.Collections.Generic.List[object]] $Manifest,
        [string] $Description
    )

    $chunks = New-Object 'System.Collections.Generic.List[object]'
    $current = New-Object 'System.Collections.Generic.List[object]'
    $currentBytes = 0L

    foreach ($entry in $Entries) {
        $entryBytes = [System.Text.Encoding]::UTF8.GetByteCount([string]$entry.Block)
        if ($current.Count -gt 0 -and ($currentBytes + $entryBytes) -gt $MaxBytes) {
            $chunks.Add($current)
            $current = New-Object 'System.Collections.Generic.List[object]'
            $currentBytes = 0L
        }
        $current.Add($entry)
        $currentBytes += $entryBytes
    }
    if ($current.Count -gt 0) { $chunks.Add($current) }
    if ($chunks.Count -eq 0) {
        $chunks.Add((New-Object 'System.Collections.Generic.List[object]'))
    }

    $partNames = New-Object 'System.Collections.Generic.List[string]'

    for ($i = 0; $i -lt $chunks.Count; $i++) {
        $partNumber = $i + 1
        $partEntries = $chunks[$i]
        $sb = New-SectionBuilder

        Add-Heading -Builder $sb -Text ("{0} - PART {1} OF {2}" -f $BaseName, $partNumber, $chunks.Count)
        Add-Line -Builder $sb $Description
        Add-Line -Builder $sb ''
        Add-Line -Builder $sb ("This part contains {0} file(s)." -f $partEntries.Count)
        if ($partEntries.Count -eq 1) {
            $entryBytes = [System.Text.Encoding]::UTF8.GetByteCount([string]$partEntries[0].Block)
            if ($entryBytes -gt $MaxBytes) {
                Add-Line -Builder $sb ''
                Add-Line -Builder $sb ("NOTE: this single entry is {0}, which exceeds the per-file limit of {1}." -f (Format-Bytes $entryBytes), (Format-Bytes $MaxBytes))
                Add-Line -Builder $sb '      It is written in full rather than truncated.'
            }
        }
        if ($partEntries.Count -gt 0) {
            Add-Line -Builder $sb ("First file in this part: {0}" -f $partEntries[0].Path)
            Add-Line -Builder $sb ("Last  file in this part: {0}" -f $partEntries[$partEntries.Count - 1].Path)
        }
        if ($chunks.Count -gt 1) {
            Add-Line -Builder $sb ''
            Add-Line -Builder $sb ("NOTE: this section is split across {0} numbered parts. Read them in" -f $chunks.Count)
            Add-Line -Builder $sb '      order (001, 002, ...) to obtain the complete section.'
            Add-Line -Builder $sb '      No file content has been truncated or omitted.'
        }
        Add-Line -Builder $sb ('=' * 100)
        Add-Line -Builder $sb ''

        foreach ($entry in $partEntries) { Add-Line -Builder $sb ([string]$entry.Block) }

        $name = if ($chunks.Count -gt 1) { "{0}.part{1:d3}.txt" -f $BaseName, $partNumber } else { "$BaseName.txt" }
        $path = Join-Path $OutDir $name
        $text = $sb.ToString()
        [System.IO.File]::WriteAllText($path, $text, $Script:Utf8NoBom)
        $partNames.Add($name)
        $Manifest.Add([pscustomobject]@{
            File = $name; Section = $BaseName; Bytes = (Get-Item -LiteralPath $path).Length
            Note = if ($chunks.Count -gt 1) { "section part $partNumber of $($chunks.Count)" } else { 'complete section' }
        })
    }

    if ($chunks.Count -gt 1) {
        # The canonical section name becomes an index so that the expected file always
        # exists and points the reader at the numbered parts.
        $idx = New-SectionBuilder
        Add-Heading -Builder $idx -Text ("{0} (index)" -f $BaseName)
        Add-Line -Builder $idx $Description
        Add-Line -Builder $idx ''
        Add-Line -Builder $idx ("This section was split into {0} numbered parts because it exceeds the" -f $chunks.Count)
        Add-Line -Builder $idx ("per-file limit of {0}. No content was truncated or omitted." -f (Format-Bytes $MaxBytes))
        Add-Line -Builder $idx ''
        Add-Line -Builder $idx 'Read the following files in order to get the complete section:'
        Add-Line -Builder $idx ''
        for ($i = 0; $i -lt $partNames.Count; $i++) {
            $partEntries = $chunks[$i]
            $range = if ($partEntries.Count -gt 0) { "{0}  ->  {1}" -f $partEntries[0].Path, $partEntries[$partEntries.Count - 1].Path } else { '(empty)' }
            Add-Line -Builder $idx ("  {0}   ({1} file(s))  {2}" -f $partNames[$i], $partEntries.Count, $range)
        }
        Add-Line -Builder $idx ''
        Add-Line -Builder $idx ('=' * 100)
        $idxPath = Join-Path $OutDir "$BaseName.txt"
        [System.IO.File]::WriteAllText($idxPath, $idx.ToString(), $Script:Utf8NoBom)
        $Manifest.Add([pscustomobject]@{
            File = "$BaseName.txt"; Section = $BaseName; Bytes = (Get-Item -LiteralPath $idxPath).Length
            Note = "index pointing at $($chunks.Count) numbered parts"
        })
    }
}

# ---------------------------------------------------------------------------
# File classification
# ---------------------------------------------------------------------------

function Get-FileKind {
    param([string] $RelativePath, [string] $Extension)
    $name = [System.IO.Path]::GetFileName($RelativePath)
    if ($name -eq 'AndroidManifest.xml') { return 'Android manifest' }
    if ($RelativePath -match '/res/.+\.xml$') { return 'Android resource XML' }
    switch ($Extension) {
        '.kt'   { return 'Kotlin source' }
        '.java' { return 'Java source' }
        '.c'    { return 'C source' }
        '.h'    { return 'C header' }
        '.cpp'  { return 'C++ source' }
        '.cc'   { return 'C++ source' }
        '.kts'  { return 'Kotlin build script' }
        '.gradle' { return 'Gradle build script' }
        '.pro'  { return 'R8 / ProGuard rules' }
        '.toml' { return 'TOML configuration' }
        '.properties' { return 'Properties configuration' }
        '.xml'  { return 'XML' }
        '.json' { return 'JSON' }
        '.sh'   { return 'Shell script' }
        '.bash' { return 'Shell script' }
        '.bat'  { return 'Batch script' }
        '.ps1'  { return 'PowerShell script' }
        '.html' { return 'HTML' }
        '.htm'  { return 'HTML' }
        '.js'   { return 'JavaScript' }
        '.md'   { return 'Markdown' }
        '.markdown' { return 'Markdown' }
        '.txt'  { return 'Plain text' }
        '.cmake' { return 'CMake script' }
        '.list' { return 'Plain text' }
        '.d'    { return 'Make dependency list' }
        '.s'    { return 'Assembly' }
        '.gitignore' { return 'Git configuration' }
        '.gitattributes' { return 'Git configuration' }
        default { if ($Extension) { return $Extension.TrimStart('.') + ' file' } else { return 'File' } }
    }
}

function Test-IsDocumentationPath {
    param([string] $RelativePath, [string] $Extension, [string] $Name)
    if ($Extension -in @('.md', '.markdown', '.mdx')) { return $true }
    if ($Name -in @('LICENSE', 'LICENCE', 'NOTICE', 'COPYING', 'CHANGELOG', 'AUTHORS', 'CONTRIBUTORS')) { return $true }
    if ($RelativePath -match '^fastlane/.+\.txt$') { return $true }
    return $false
}

function Test-IsDependencyPath {
    param([string] $RelativePath, [string] $Name)
    if ($Name -match '^build\.gradle(\.kts)?$') { return $true }
    if ($Name -match '^settings\.gradle(\.kts)?$') { return $true }
    if ($Name -eq 'gradle.properties') { return $true }
    if ($RelativePath -eq 'gradle/libs.versions.toml') { return $true }
    if ($Name -eq 'gradle-wrapper.properties') { return $true }
    return $false
}

function Test-IsResourcePath {
    param([string] $RelativePath)
    if ($RelativePath -match '/res/') { return $true }
    if ($RelativePath -match '/assets/') { return $true }
    if ($RelativePath -match '/jniLibs/') { return $true }
    if ($RelativePath -match '^assets/') { return $true }
    if ($RelativePath -match '^fastlane/.+/images/') { return $true }
    if ($RelativePath -match '^app/libs/') { return $true }
    return $false
}

function Get-FilePlan {
    <#
        Classifies every discovered file into one of: source, documentation,
        dependencies, resources (inventory only) or excluded.
    #>
    param(
        [object[]] $Files,
        [string] $RepoRoot,
        [switch] $SkipTests,
        [switch] $SkipVendored
    )

    $plan = New-Object 'System.Collections.Generic.List[object]'

    foreach ($f in $Files) {
        $rel  = Get-RelativePath -Base $RepoRoot -Full $f.FullName
        $ext  = Get-RelativeExtensions -RelativePath $rel
        $name = [System.IO.Path]::GetFileName($rel)

        $section = 'source'
        $reason  = ''
        $kind    = Get-FileKind -RelativePath $rel -Extension $ext

        $isSecret = ($Script:SecretFileNames -contains $name) -or
                    ($Script:SecretFileExtensions -contains $ext) -or
                    ($name -match '(?i)^(secrets|credentials)\.') -or
                    ($name -match '(?i)\.env(\..+)?$')

        $isGenerated = ($Script:GeneratedArtefactNames -contains $name)
        $isTest = $false
        foreach ($tr in $Script:TestRoots) { if ($rel.StartsWith($tr, [System.StringComparison]::OrdinalIgnoreCase)) { $isTest = $true; break } }
        $isVendored = $false
        foreach ($vr in $Script:VendoredRoots) { if ($rel.StartsWith($vr, [System.StringComparison]::OrdinalIgnoreCase)) { $isVendored = $true; break } }

        $isBinary = ($Script:BinaryExtensions -contains $ext)
        if (-not $isBinary -and $ext -eq '' -and $f.Length -gt 0) {
            $isBinary = -not (Test-IsTextFile -Path $f.FullName)
        }

        if ($isSecret) {
            $section = 'excluded'; $reason = 'credential / signing material - never exported'; $kind = 'secret-bearing file'
        }
        elseif ($isGenerated) {
            $section = 'excluded'; $reason = 'generated build artefact (machine-produced profile dump)'; $kind = 'generated artefact'
        }
        elseif ($isTest -and $SkipTests) {
            $section = 'excluded'; $reason = 'test source (excluded by -ExcludeTests)'
        }
        elseif ($isVendored -and $SkipVendored) {
            $section = 'excluded'; $reason = 'third-party vendored source (excluded by -ExcludeVendoredSources)'
        }
        elseif ($isBinary) {
            $section = 'resources'; $reason = 'binary file - content not exported, inventoried in RESOURCES_AND_ASSETS.txt'
        }
        elseif (Test-IsDocumentationPath -RelativePath $rel -Extension $ext -Name $name) {
            $section = 'documentation'
        }
        elseif (Test-IsDependencyPath -RelativePath $rel -Name $name) {
            $section = 'dependencies'
        }

        $plan.Add([pscustomobject]@{
            Path = $rel; FullName = $f.FullName; Length = [long]$f.Length; Extension = $ext
            Section = $section; Reason = $reason; Kind = $kind; IsBinary = $isBinary
        })
    }
    # Return the list as a single object so PowerShell does not unroll it.
    return ,$plan
}

# ---------------------------------------------------------------------------
# Section builders
# ---------------------------------------------------------------------------

function Build-StructureTree {
    param([object[]] $Plan, [object[]] $ExcludedDirs, [string] $RepoRoot)

    $sb = New-SectionBuilder
    Add-Heading -Builder $sb -Text 'PROJECT STRUCTURE'
    Add-Line -Builder $sb 'Directory tree of the project as exported. Directories that were pruned'
    Add-Line -Builder $sb '(build output, caches, VCS internals) are listed at the end.'
    Add-Line -Builder $sb ''
    Add-Line -Builder $sb ("Project root: {0}" -f $RepoRoot)
    Add-Line -Builder $sb ''
    Add-Line -Builder $sb ('=' * 100)

    $tree = New-Object 'System.Collections.Generic.List[object]'
    foreach ($p in $Plan) { $tree.Add($p) }

    # Group files by directory, preserving a stable order.
    $byDir = @{}
    foreach ($p in $tree) {
        $dir = ''
        $idx = $p.Path.LastIndexOf('/')
        if ($idx -ge 0) { $dir = $p.Path.Substring(0, $idx) }
        if (-not $byDir.ContainsKey($dir)) { $byDir[$dir] = New-Object 'System.Collections.Generic.List[object]' }
        $byDir[$dir].Add($p)
    }

    $allDirs = @($byDir.Keys) | Sort-Object
    foreach ($dir in $allDirs) {
        if ($dir -eq '') { continue }
        $depth = ($dir -split '/').Count
        Add-Line -Builder $sb ((' ' * ($depth * 2)) + [System.IO.Path]::GetFileName($dir) + '/')
    }

    Add-Line -Builder $sb ''
    Add-Line -Builder $sb 'ROOT-LEVEL FILES'
    Add-Line -Builder $sb ('-' * 100)
    if ($byDir.ContainsKey('')) {
        foreach ($p in ($byDir[''] | Sort-Object Path)) {
            $mark = if ($p.Section -eq 'excluded') { ' [EXCLUDED]' } elseif ($p.IsBinary) { ' [BINARY]' } else { '' }
            Add-Line -Builder $sb ("  {0,-46} {1,12}{2}" -f $p.Path, (Format-Bytes $p.Length), $mark)
        }
    }

    foreach ($dir in $allDirs) {
        if ($dir -eq '') { continue }
        $depth = ($dir -split '/').Count
        Add-Line -Builder $sb ''
        Add-Line -Builder $sb ((' ' * ($depth * 2)) + [System.IO.Path]::GetFileName($dir) + '/')
        foreach ($p in ($byDir[$dir] | Sort-Object Path)) {
            $mark = if ($p.Section -eq 'excluded') { ' [EXCLUDED]' } elseif ($p.IsBinary) { ' [BINARY]' } else { '' }
            Add-Line -Builder $sb ((' ' * (($depth + 1) * 2)) + ("{0,-44} {1,12}{2}" -f [System.IO.Path]::GetFileName($p.Path), (Format-Bytes $p.Length), $mark))
        }
    }

    Add-Line -Builder $sb ''
    Add-Line -Builder $sb ('=' * 100)
    Add-Line -Builder $sb 'PRUNED DIRECTORIES (not exported)'
    Add-Line -Builder $sb ('-' * 100)
    foreach ($d in ($ExcludedDirs | Sort-Object Path)) {
        Add-Line -Builder $sb ("  {0,-46} {1}" -f $d.Path, $d.Reason)
    }
    return $sb.ToString()
}

function Add-OverviewIdentity {
    param([System.Text.StringBuilder] $sb, [object] $AppFacts)

    Add-Heading -Builder $sb -Text '1. APPLICATION IDENTITY'
    Add-Line -Builder $sb 'Project name          : File Explorer (ZenLabs File Explorer)'
    Add-Line -Builder $sb ("Application ID        : {0}" -f $AppFacts.ApplicationId)
    Add-Line -Builder $sb ("Kotlin namespace      : {0}" -f $AppFacts.Namespace)
    Add-Line -Builder $sb ("Version               : {0} (versionCode {1})" -f $AppFacts.VersionName, $AppFacts.VersionCode)
    Add-Line -Builder $sb ("minSdk / targetSdk    : {0} / {1}" -f $AppFacts.MinSdk, $AppFacts.TargetSdk)
    Add-Line -Builder $sb ("compileSdk            : {0}" -f $AppFacts.CompileSdk)
    Add-Line -Builder $sb ("Java / Kotlin target  : Java {0}, Kotlin api {1}" -f $AppFacts.JavaVersion, $AppFacts.KotlinApi)
    Add-Line -Builder $sb ("Android NDK           : {0}" -f $AppFacts.NdkVersion)
    Add-Line -Builder $sb 'License               : GNU General Public License v3.0'
    Add-Line -Builder $sb 'Upstream base         : Prism File Explorer (techflyers), extended with NFile features'
    Add-Line -Builder $sb 'Distribution          : GitHub Releases, sideloaded APK (three per-ABI APKs)'
}

function Add-OverviewModules {
    param([System.Text.StringBuilder] $sb, [string] $RepoRoot, [string[]] $Modules)

    Add-Heading -Builder $sb -Text '2. GRADLE MODULES'
    Add-Line -Builder $sb ("Modules declared in settings.gradle: {0}" -f ($Modules -join ', '))
    Add-Line -Builder $sb ''
    Add-Line -Builder $sb ("{0,-18} {1,-38} {2,-9} {3}" -f 'MODULE', 'NAMESPACE / PLUGIN', 'MIN SDK', 'PURPOSE')
    Add-Line -Builder $sb ('-' * 110)
    $modulePurpose = @{
        'app'               = 'Main application module (Compose UI, features, resources, assets)'
        'baselineProfile'   = 'Macrobenchmark module that generates the startup baseline profile'
        'proot'             = 'PRoot user-space Linux engine (CMake/NDK native library)'
        'terminal-emulator' = 'Terminal emulator core (ported from Termux, CMake native JNI)'
        'terminal-view'     = 'Terminal view/renderer widget (ported from Termux)'
        'link2symlink'      = 'Native helper creating symlinks on filesystems without them'
    }
    foreach ($m in $Modules) {
        $mf = Get-GradleFacts -RepoRoot $RepoRoot -Module $m
        $purpose = if ($modulePurpose.ContainsKey($m)) { $modulePurpose[$m] } else { 'library module' }
        $ns = if ($mf.Namespace) { $mf.Namespace } else { '(shares the app namespace)' }
        Add-Line -Builder $sb ("{0,-18} {1,-38} {2,-9} {3}" -f $m, $ns, $mf.MinSdk, $purpose)
    }
}

function Add-OverviewBuildConfig {
    param([System.Text.StringBuilder] $sb, [string] $RepoRoot, [object] $Catalog, [object] $AppFacts)

    Add-Heading -Builder $sb -Text '3. BUILD CONFIGURATION'
    $wrapperProps = Join-Path $RepoRoot 'gradle\wrapper\gradle-wrapper.properties'
    $gradleVersion = 'unknown'
    if (Test-Path -LiteralPath $wrapperProps) {
        $m = [regex]::Match((Get-Content -LiteralPath $wrapperProps -Raw), 'distributionUrl\s*=\s*.*gradle-([0-9.]+)')
        if ($m.Success) { $gradleVersion = $m.Groups[1].Value }
    }
    Add-Line -Builder $sb ("Gradle wrapper        : {0}" -f $gradleVersion)
    Add-Line -Builder $sb ("Android Gradle Plugin : {0}" -f $Catalog.Versions['agp'])
    Add-Line -Builder $sb ("Kotlin                : {0}" -f $Catalog.Versions['kotlin'])
    Add-Line -Builder $sb ("Compose BOM           : {0}" -f $Catalog.Versions['androidxComposeBom'])
    Add-Line -Builder $sb ("Baseline Profile plug : {0}" -f $Catalog.Versions['baselineprofile'])
    Add-Line -Builder $sb ("Dependency catalog    : gradle/libs.versions.toml ({0} versions, {1} libraries, {2} plugins)" -f `
            $Catalog.Versions.Count, $Catalog.Libraries.Count, $Catalog.Plugins.Count)
    Add-Line -Builder $sb ("R8 minification       : {0}" -f $(if ($AppFacts.Minify) { 'enabled' } else { 'disabled' }))
    Add-Line -Builder $sb ("Resource shrinking    : {0}" -f $(if ($AppFacts.ShrinkResources) { 'enabled' } else { 'disabled' }))
    Add-Line -Builder $sb ("ProGuard/R8 rule files: {0}" -f (($AppFacts.ProguardFiles | Sort-Object -Unique) -join ', '))
    Add-Line -Builder $sb ("Universal APK         : {0}" -f $(if ($AppFacts.UniversalApk) { $AppFacts.UniversalApk } else { 'not specified' }))
    Add-Line -Builder $sb ''
    Add-Line -Builder $sb 'Release signing configuration reads the keystore path, store password, key alias and key'
    Add-Line -Builder $sb 'password from local.properties. Those values are machine-local secrets and are'
    Add-Line -Builder $sb 'deliberately NOT part of this export. The release build falls back to the debug signing'
    Add-Line -Builder $sb 'configuration when no release keystore is configured.'
    Add-Line -Builder $sb ''
    Add-Line -Builder $sb 'Common build commands:'
    Add-Line -Builder $sb '  ./gradlew assembleRelease            - build the per-ABI release APKs'
    Add-Line -Builder $sb '  ./gradlew assembleDebug              - debug build'
    Add-Line -Builder $sb '  ./gradlew :app:testReleaseUnitTest   - unit tests'
    Add-Line -Builder $sb '  ./gradlew :baselineProfile:assemble  - build the baseline profile module'
    Add-Line -Builder $sb '  ./gradlew :baselineProfile:connectedReleaseAndroidTest - regenerate the profile (needs a device)'
}

function Add-OverviewNative {
    param([System.Text.StringBuilder] $sb, [string] $RepoRoot, [object[]] $Plan, [object] $AppFacts)

    Add-Heading -Builder $sb -Text '4. NATIVE CODE, ABIs AND PACKAGED LIBRARIES'
    Add-Line -Builder $sb ("Configured ABIs        : {0}" -f (($AppFacts.Abis | Sort-Object -Unique) -join ', '))
    Add-Line -Builder $sb ''
    Add-Line -Builder $sb 'Prebuilt native libraries shipped in app/src/main/jniLibs:'
    foreach ($p in ($Plan | Where-Object { $_.Path -match '/jniLibs/' } | Sort-Object Path)) {
        Add-Line -Builder $sb ("  {0,-52} {1,12}" -f $p.Path, (Format-Bytes $p.Length))
    }
    Add-Line -Builder $sb ''
    Add-Line -Builder $sb 'CMake/NDK modules compiled from source in this repository:'
    foreach ($m in @('proot', 'link2symlink', 'terminal-emulator')) {
        $mf = Get-GradleFacts -RepoRoot $RepoRoot -Module $m
        if ($mf.Abis.Count -gt 0) {
            Add-Line -Builder $sb ("  {0,-20} ABIs: {1}" -f $m, (($mf.Abis | Sort-Object -Unique) -join ', '))
        }
    }
    Add-Line -Builder $sb ''
    Add-Line -Builder $sb 'The APK is split per ABI (armeabi-v7a, arm64-v8a, x86_64); no universal APK is produced'
    Add-Line -Builder $sb 'because it would duplicate every native runtime. Native libraries are pre-stripped by'
    Add-Line -Builder $sb 'AGP (llvm-strip --strip-unneeded).'
}

function Add-OverviewManifest {
    param([System.Text.StringBuilder] $sb, [object] $ManifestFacts)

    Add-Heading -Builder $sb -Text '5. ANDROID MANIFEST OVERVIEW'
    Add-Line -Builder $sb ("Declared permissions ({0}):" -f @($ManifestFacts.Permissions).Count)
    foreach ($p in ($ManifestFacts.Permissions | Sort-Object -Unique)) { Add-Line -Builder $sb ("  {0}" -f $p) }
    Add-Line -Builder $sb ''
    Add-Line -Builder $sb ("Components: {0} activities, {1} services, {2} receivers, {3} providers" -f `
            @($ManifestFacts.Activities).Count, @($ManifestFacts.Services).Count, @($ManifestFacts.Receivers).Count, @($ManifestFacts.Providers).Count)
    foreach ($c in ($ManifestFacts.Activities | Sort-Object -Unique)) { Add-Line -Builder $sb ("  activity : {0}" -f $c) }
    foreach ($c in ($ManifestFacts.Services | Sort-Object -Unique)) { Add-Line -Builder $sb ("  service  : {0}" -f $c) }
    foreach ($c in ($ManifestFacts.Providers | Sort-Object -Unique)) { Add-Line -Builder $sb ("  provider : {0}" -f $c) }
    Add-Line -Builder $sb ''
    Add-Line -Builder $sb 'The complete AndroidManifest.xml is included verbatim in SOURCE_CODE.'
}

function Add-OverviewArchitecture {
    param([System.Text.StringBuilder] $sb, [object[]] $Plan)

    Add-Heading -Builder $sb -Text '6. SOURCE ARCHITECTURE'

    $srcRoot = 'app/src/main/java/'
    $pkgCounts = @{}
    $extCounts = @{}
    foreach ($p in $Plan) {
        if ($p.Section -ne 'source') { continue }
        $key = $p.Extension
        if (-not $extCounts.ContainsKey($key)) { $extCounts[$key] = 0 }
        $extCounts[$key]++
        if (-not $p.Path.StartsWith($srcRoot)) { continue }
        $rel = $p.Path.Substring($srcRoot.Length)
        $idx = $rel.LastIndexOf('/')
        if ($idx -lt 0) { continue }
        $dir = $rel.Substring(0, $idx)
        if (-not $pkgCounts.ContainsKey($dir)) { $pkgCounts[$dir] = 0 }
        $pkgCounts[$dir]++
    }

    Add-Line -Builder $sb 'Source files by type:'
    foreach ($e in ($extCounts.GetEnumerator() | Sort-Object -Property @{Expression = 'Value'; Descending = $true}, @{Expression = 'Key'; Descending = $false})) {
        Add-Line -Builder $sb ("  {0,-14} {1,5}" -f $(if ($e.Key) { $e.Key } else { '(none)' }), $e.Value)
    }
    Add-Line -Builder $sb ''
    Add-Line -Builder $sb 'Package layout under app/src/main/java (directory = package):'
    Add-Line -Builder $sb ("{0,-62} {1,6}" -f 'PACKAGE DIRECTORY', 'FILES')
    Add-Line -Builder $sb ('-' * 100)
    foreach ($e in ($pkgCounts.GetEnumerator() | Sort-Object Name)) {
        Add-Line -Builder $sb ("{0,-62} {1,6}" -f $e.Key, $e.Value)
    }
    Add-Line -Builder $sb ''
    Add-Line -Builder $sb 'Architecture summary:'
    Add-Line -Builder $sb '  - Single-activity Compose application. The navigation drawer and the multi-tab file'
    Add-Line -Builder $sb '    browser live under screen.main; tab.files holds the bulk of the file-management logic.'
    Add-Line -Builder $sb '  - screen.main.tab.files.* is organised by concern: holder (per-file-type UI state),'
    Add-Line -Builder $sb '    service (long-running work), task (background jobs), ui (widgets), misc and search.'
    Add-Line -Builder $sb '  - screen.main.tab.home holds the dashboard; screen.main.tab.nfile_tools holds the'
    Add-Line -Builder $sb '    companion utilities (FTP server, web sharing, wallet, private vault).'
    Add-Line -Builder $sb '  - common holds cross-cutting services: theme, preferences, logging, archiver,'
    Add-Line -Builder $sb '    Magika file-type detection, the Tectonic cache manager and Convertio integration.'
    Add-Line -Builder $sb '  - office contains the LibreOfficeKit-based document conversion runtime.'
    Add-Line -Builder $sb '  - epub / comic / ebook are document readers; saf provides the SAF DocumentsProvider.'
    Add-Line -Builder $sb '  - utils contains Android platform helpers; coil holds custom Coil image loaders.'
}

function Build-ProjectOverview {
    param(
        [string] $RepoRoot,
        [object[]] $Plan,
        [object] $Catalog,
        [string[]] $Modules,
        [object] $AppFacts,
        [object] $ManifestFacts
    )

    $sb = New-SectionBuilder
    Add-Heading -Builder $sb -Text 'PROJECT OVERVIEW'
    Add-Line -Builder $sb 'ZenLabs File Explorer - a modern Android file manager built with Kotlin and Jetpack'
    Add-Line -Builder $sb 'Compose. This overview was generated from the repository itself.'
    Add-Line -Builder $sb ''
    Add-OverviewIdentity     -sb $sb -AppFacts $AppFacts
    Add-OverviewModules      -sb $sb -RepoRoot $RepoRoot -Modules $Modules
    Add-OverviewBuildConfig  -sb $sb -RepoRoot $RepoRoot -Catalog $Catalog -AppFacts $AppFacts
    Add-OverviewNative       -sb $sb -RepoRoot $RepoRoot -Plan $Plan -AppFacts $AppFacts
    Add-OverviewManifest     -sb $sb -ManifestFacts $ManifestFacts
    Add-OverviewArchitecture -sb $sb -Plan $Plan
    return $sb.ToString()
}

function Build-Dependencies {
    param(
        [string] $RepoRoot,
        [object] $Catalog,
        [string[]] $Modules,
        [hashtable] $Usage
    )

    $sb = New-SectionBuilder
    Add-Heading -Builder $sb -Text 'DEPENDENCIES'
    Add-Line -Builder $sb 'Resolved dependency catalogue for every Gradle module, followed by the raw Gradle'
    Add-Line -Builder $sb 'configuration so that build behaviour can be reproduced exactly.'

    Add-Heading -Builder $sb -Text '1. VERSION CATALOGUE (gradle/libs.versions.toml)'
    Add-Line -Builder $sb ("{0,-34} {1,-46} {2,-14} {3}" -f 'ALIAS', 'COORDINATE', 'VERSION', 'USED BY')
    Add-Line -Builder $sb ('-' * 130)
    foreach ($alias in ($Catalog.Libraries.Keys | Sort-Object)) {
        $lib = $Catalog.Libraries[$alias]
        $coord = if ($lib.Group -and $lib.Name) { "$($lib.Group):$($lib.Name)" } else { '(incomplete)' }
        $used = if ($Usage.ContainsKey($alias)) { (($Usage[$alias] | Sort-Object) -join ', ') } else { '(not referenced directly)' }
        Add-Line -Builder $sb ("{0,-34} {1,-46} {2,-14} {3}" -f $alias, $coord, $lib.Version, $used)
    }
    Add-Line -Builder $sb ''

    Add-Heading -Builder $sb -Text '2. GRADLE PLUGINS'
    Add-Line -Builder $sb ("{0,-34} {1,-52} {2}" -f 'ALIAS', 'PLUGIN ID', 'VERSION')
    Add-Line -Builder $sb ('-' * 100)
    foreach ($alias in ($Catalog.Plugins.Keys | Sort-Object)) {
        $pl = $Catalog.Plugins[$alias]
        Add-Line -Builder $sb ("{0,-34} {1,-52} {2}" -f $alias, $pl.Id, $pl.Version)
    }
    Add-Line -Builder $sb ''

    Add-Heading -Builder $sb -Text '3. PER-MODULE DIRECT DEPENDENCIES'
    foreach ($m in $Modules) {
        $mf = Get-GradleFacts -RepoRoot $RepoRoot -Module $m
        Add-Line -Builder $sb ("--- {0} ---" -f $m)
        if ($mf.DirectDependencies.Count -eq 0) {
            Add-Line -Builder $sb '  (all dependencies come from the version catalogue via libs.* accessors)'
        }
        foreach ($d in $mf.DirectDependencies) { Add-Line -Builder $sb ("  {0}" -f $d) }
        Add-Line -Builder $sb ''
    }

    Add-Heading -Builder $sb -Text '4. REPOSITORY AND GRADLE CONFIGURATION'
    foreach ($cfg in @('settings.gradle.kts', 'gradle.properties', 'gradle/wrapper/gradle-wrapper.properties')) {
        $full = Join-Path $RepoRoot ($cfg.Replace('/', '\'))
        if (-not (Test-Path -LiteralPath $full)) { continue }
        Add-Line -Builder $sb ''
        Add-Line -Builder $sb ("--- {0} ---" -f $cfg)
        Add-Line -Builder $sb ('=' * 100)
        Add-Line -Builder $sb ((Get-Content -LiteralPath $full -Raw).TrimEnd())
        Add-Line -Builder $sb ('=' * 100)
    }
    return $sb.ToString()
}

function Build-ResourceInventory {
    param([object[]] $Plan)

    $sb = New-SectionBuilder
    Add-Heading -Builder $sb -Text 'RESOURCES AND ASSETS'
    Add-Line -Builder $sb 'Inventory of every resource, asset, native library and prebuilt binary in the project.'
    Add-Line -Builder $sb 'Binary contents are not exported (they are not useful as text and would bloat the'
    Add-Line -Builder $sb 'bundle); their identity, format, size and - for images - pixel dimensions are'
    Add-Line -Builder $sb 'recorded here so the external AI knows exactly what ships in the APK.'
    Add-Line -Builder $sb ''

    $groups = [ordered]@{
        'Android resources (app/src/main/res)' = @($Plan | Where-Object { $_.Path -match '/res/' })
        'Runtime assets (app/src/main/assets)'  = @($Plan | Where-Object { $_.Path -match '/assets/' })
        'Native libraries (jniLibs)'            = @($Plan | Where-Object { $_.Path -match '/jniLibs/' })
        'Repository / store media'              = @($Plan | Where-Object { ($_.Path -match '^assets/') -or ($_.Path -match '^fastlane/') })
        'Prebuilt binaries'                     = @($Plan | Where-Object { $_.IsBinary -and $_.Path -notmatch '/res/|/assets/|/jniLibs/|^assets/|^fastlane/' })
    }

    foreach ($g in $groups.GetEnumerator()) {
        Add-Heading -Builder $sb -Text $g.Key
        if ($g.Value.Count -eq 0) {
            Add-Line -Builder $sb '  (none)'
            Add-Line -Builder $sb ''
            continue
        }
        Add-Line -Builder $sb ("{0,-58} {1,-12} {2,12} {3,-16} {4}" -f 'PATH', 'FORMAT', 'SIZE', 'DIMENSIONS', 'NOTE')
        Add-Line -Builder $sb ('-' * 130)
        foreach ($p in ($g.Value | Sort-Object Path)) {
            $format = if ($p.Extension) { $p.Extension.TrimStart('.').ToUpperInvariant() } else { 'FILE' }
            $dims = ''
            $note = ''
            if ($p.Path -match '/jniLibs/') {
                $dims = ($p.Path -split '/')[2]
                $note = 'prebuilt native library (stripped by AGP)'
            }
            elseif ($format -in @('PNG', 'JPG', 'JPEG', 'WEBP', 'GIF', 'BMP', 'ICO')) {
                $d = Get-ImageDimensions -Path $p.FullName -Extension $p.Extension
                if ($d) { $dims = $d }
            }
            elseif ($p.Extension -eq '.xml' -and $p.Path -match '/res/drawable') {
                $d = Get-VectorDrawableDimensions -Path $p.FullName
                if ($d) { $dims = $d; $note = 'vector drawable' }
            }
            elseif ($p.Extension -eq '.xml' -and $p.Path -match '/res/') {
                $note = 'resource XML (content in SOURCE_CODE)'
            }
            elseif ($p.Extension -eq '.onnx') { $note = 'ONNX model (Magika file-type detection)' }
            elseif ($p.Extension -eq '.xz') { $note = 'XZ-compressed LaTeX/Tectonic cache bundle' }
            elseif ($p.Extension -eq '.zip') { $note = 'ZIP archive' }
            elseif ($p.Extension -eq '.bundle') { $note = 'TextMate grammar bundle (ZIP container)' }
            elseif ($p.Extension -eq '.jar') { $note = 'prebuilt Java archive' }
            elseif ($p.Extension -in @('.ttf', '.otf')) { $note = 'font' }
            elseif ($p.Path -match '/assets/') { $note = 'runtime asset' }
            elseif ($p.Path -match '^fastlane/') { $note = 'store listing media' }
            Add-Line -Builder $sb ("{0,-58} {1,-12} {2,12} {3,-16} {4}" -f $p.Path, $format, (Format-Bytes $p.Length), $dims, $note)
        }
        Add-Line -Builder $sb ''
    }

    Add-Heading -Builder $sb -Text 'RESOURCE DIRECTORY SUMMARY'
    foreach ($dir in ($Plan | Where-Object { $_.Path -match '/res/[^/]+$' } | Group-Object { ($_.Path -split '/res/')[1] } | Sort-Object Name)) {
        Add-Line -Builder $sb ("  res/{0,-24} {1,4} file(s)" -f $dir.Name, $dir.Count)
    }
    return $sb.ToString()
}

function Build-GitStatus {
    param([object] $Git, [string] $RepoRoot)

    $sb = New-SectionBuilder
    Add-Heading -Builder $sb -Text 'GIT STATUS'
    if (-not $Git.Available) {
        Add-Line -Builder $sb 'git was not available on this machine; no VCS state could be collected.'
        return $sb.ToString()
    }

    Add-Line -Builder $sb ("Repository            : {0}" -f $RepoRoot)
    Add-Line -Builder $sb ("Current branch        : {0}" -f $Git.Branch)
    Add-Line -Builder $sb ("Tracked files         : {0}" -f $Git.TrackedCount)
    Add-Line -Builder $sb ''
    Add-Heading -Builder $sb -Text 'LATEST COMMIT'
    Add-Line -Builder $sb $Git.Head
    Add-Line -Builder $sb ''

    Add-Heading -Builder $sb -Text 'REMOTES'
    if ($Git.Remotes) { Add-Line -Builder $sb $Git.Remotes } else { Add-Line -Builder $sb '  (no remotes configured)' }
    Add-Line -Builder $sb ''

    Add-Heading -Builder $sb -Text 'WORKING TREE STATE (git status --short --branch)'
    if ($Git.StatusBranch) { Add-Line -Builder $sb $Git.StatusBranch } else { Add-Line -Builder $sb '  (clean)' }
    Add-Line -Builder $sb ''

    Add-Heading -Builder $sb -Text 'UNCOMMITTED CHANGES'
    Add-Line -Builder $sb 'Staged:'
    if ($Git.Staged.Count) { foreach ($f in ($Git.Staged | Sort-Object)) { Add-Line -Builder $sb ("  + {0}" -f $f) } }
    else { Add-Line -Builder $sb '  (none)' }
    Add-Line -Builder $sb 'Modified (unstaged):'
    if ($Git.Modified.Count) { foreach ($f in ($Git.Modified | Sort-Object)) { Add-Line -Builder $sb ("  ~ {0}" -f $f) } }
    else { Add-Line -Builder $sb '  (none)' }
    Add-Line -Builder $sb 'Deleted:'
    if ($Git.Deleted.Count) { foreach ($f in ($Git.Deleted | Sort-Object)) { Add-Line -Builder $sb ("  - {0}" -f $f) } }
    else { Add-Line -Builder $sb '  (none)' }
    Add-Line -Builder $sb 'Untracked:'
    if ($Git.Untracked.Count) { foreach ($f in ($Git.Untracked | Sort-Object)) { Add-Line -Builder $sb ("  ? {0}" -f $f) } }
    else { Add-Line -Builder $sb '  (none)' }
    Add-Line -Builder $sb ''

    Add-Heading -Builder $sb -Text 'UNSTAGED DIFF STAT'
    if ($Git.DiffStat) { Add-Line -Builder $sb $Git.DiffStat } else { Add-Line -Builder $sb '  (none)' }
    Add-Line -Builder $sb ''

    Add-Heading -Builder $sb -Text 'STAGED DIFF STAT'
    if ($Git.DiffCachedStat) { Add-Line -Builder $sb $Git.DiffCachedStat } else { Add-Line -Builder $sb '  (none)' }
    Add-Line -Builder $sb ''

    Add-Heading -Builder $sb -Text 'RECENT HISTORY (last 30 commits)'
    if ($Git.RecentLog) { Add-Line -Builder $sb $Git.RecentLog } else { Add-Line -Builder $sb '  (none)' }
    return $sb.ToString()
}

function Build-Manifest {
    param(
        [object[]] $Plan,
        [object[]] $ExcludedDirs,
        [object[]] $WrittenFiles,
        [object[]] $Redactions,
        [string] $RepoRoot,
        [string] $OutDir
    )

    $sb = New-SectionBuilder
    Add-Heading -Builder $sb -Text 'MANIFEST'
    Add-Line -Builder $sb ("Generated {0}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz'))
    Add-Line -Builder $sb ("Source repository      : {0}" -f $RepoRoot)
    Add-Line -Builder $sb ("Export directory       : {0}" -f $OutDir)
    Add-Line -Builder $sb ''
    Add-Line -Builder $sb 'This manifest lists every file that was exported (with the section it belongs to)'
    Add-Line -Builder $sb 'and every file that was deliberately left out (with the reason).'

    Add-Heading -Builder $sb -Text '1. EXPORTED FILES IN THIS BUNDLE'
    Add-Line -Builder $sb ("{0,-42} {1,-18} {2,12} {3}" -f 'FILE', 'SECTION', 'SIZE', 'NOTE')
    Add-Line -Builder $sb ('-' * 120)
    foreach ($w in ($WrittenFiles | Sort-Object File)) {
        Add-Line -Builder $sb ("{0,-42} {1,-18} {2,12} {3}" -f $w.File, $w.Section, (Format-Bytes $w.Bytes), $w.Note)
    }

    Add-Heading -Builder $sb -Text '2. SOURCE FILES INCLUDED IN THE EXPORT'
    Add-Line -Builder $sb ("{0,-62} {1,-14} {2,12} {3}" -f 'PATH', 'SECTION', 'SIZE', 'KIND')
    Add-Line -Builder $sb ('-' * 120)
    foreach ($p in ($Plan | Where-Object { $_.Section -ne 'excluded' } | Sort-Object Path)) {
        Add-Line -Builder $sb ("{0,-62} {1,-14} {2,12} {3}" -f $p.Path, $p.Section, (Format-Bytes $p.Length), $p.Kind)
    }

    Add-Heading -Builder $sb -Text '3. FILES EXCLUDED FROM THE EXPORT'
    Add-Line -Builder $sb ("{0,-62} {1,12} {2}" -f 'PATH', 'SIZE', 'REASON')
    Add-Line -Builder $sb ('-' * 120)
    foreach ($p in ($Plan | Where-Object { $_.Section -eq 'excluded' } | Sort-Object Path)) {
        Add-Line -Builder $sb ("{0,-62} {1,12} {2}" -f $p.Path, (Format-Bytes $p.Length), $p.Reason)
    }
    Add-Line -Builder $sb ''
    Add-Line -Builder $sb 'Binary files whose contents are not exported are inventoried in'
    Add-Line -Builder $sb 'RESOURCES_AND_ASSETS.txt and are listed in section 2 above under the'
    Add-Line -Builder $sb '"resources" section.'

    Add-Heading -Builder $sb -Text '4. DIRECTORIES PRUNED DURING THE SCAN'
    Add-Line -Builder $sb ("{0,-62} {1}" -f 'DIRECTORY', 'REASON')
    Add-Line -Builder $sb ('-' * 120)
    foreach ($d in ($ExcludedDirs | Sort-Object Path)) {
        Add-Line -Builder $sb ("{0,-62} {1}" -f $d.Path, $d.Reason)
    }

    Add-Heading -Builder $sb -Text '5. REDACTIONS APPLIED'
    if (@($Redactions).Count -eq 0) {
        Add-Line -Builder $sb 'No credential-shaped values were detected in the exported text.'
        Add-Line -Builder $sb 'Files that could contain credentials (local.properties, keystores, .env files)'
        Add-Line -Builder $sb 'were excluded outright and are listed in section 3.'
    }
    else {
        Add-Line -Builder $sb ("{0,-62} {1,-34} {2,6} {3}" -f 'FILE', 'RULE', 'COUNT', 'KIND')
        Add-Line -Builder $sb ('-' * 120)
        foreach ($r in ($Redactions | Sort-Object File, Rule)) {
            Add-Line -Builder $sb ("{0,-62} {1,-34} {2,6} {3}" -f $r.File, $r.Rule, $r.Count, $r.Kind)
        }
        Add-Line -Builder $sb ''
        Add-Line -Builder $sb 'Redacted values are replaced with <<REDACTED:...>> markers. The surrounding code'
        Add-Line -Builder $sb 'structure is preserved so the external AI can still reason about the code.'
    }
    return $sb.ToString()
}

function New-SectionEntries {
    <#
        Reads, redacts and renders every file that belongs to one or more sections.
    #>
    param(
        [object[]] $Plan,
        [string[]] $Sections,
        [System.Collections.Generic.List[object]] $RedactionLog
    )

    $entries = New-Object 'System.Collections.Generic.List[object]'
    foreach ($p in ($Plan | Where-Object { $Sections -contains $_.Section } | Sort-Object Path)) {
        $content = ''
        try { $content = Get-Content -LiteralPath $p.FullName -Raw -ErrorAction Stop } catch { $content = '' }
        if ($null -eq $content) { $content = '' }
        $content = $content -replace "`r`n", "`n"

        $before = $RedactionLog.Count
        $clean = Protect-Secrets -Content $content -RelativePath $p.Path -Extension $p.Extension -Log $RedactionLog
        $redactions = 0
        for ($i = $before; $i -lt $RedactionLog.Count; $i++) { $redactions += $RedactionLog[$i].Count }

        $sha = Get-FileSha256 -Path $p.FullName
        $sb = New-SectionBuilder
        Add-FileBlock -Builder $sb -RelativePath $p.Path -Content $clean -Kind $p.Kind `
                      -OriginalBytes $p.Length -Sha256 $sha -Redactions $redactions
        $entries.Add([pscustomobject]@{ Path = $p.Path; Block = $sb.ToString() })
    }
    # Return the list as a single object so PowerShell does not unroll it.
    return ,$entries
}

function New-SingleEntry {
    param([string] $Label, [string] $Text)
    $sb = New-SectionBuilder
    Add-Line -Builder $sb $Text
    $list = New-Object 'System.Collections.Generic.List[object]'
    $list.Add([pscustomobject]@{ Path = $Label; Block = $sb.ToString() })
    return ,$list
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

Write-Host ''
Write-Host 'AI Context Exporter - ZenLabs File Explorer' -ForegroundColor Cyan
Write-Host ('=' * 72)

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $Script:RepoRoot 'ai-context-export'
}
if (-not [System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory = Join-Path $Script:RepoRoot $OutputDirectory
}
$outFull = [System.IO.Path]::GetFullPath($OutputDirectory).TrimEnd('\')
$repoFull = $Script:RepoRoot.TrimEnd('\')

if ($outFull.Equals($repoFull, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'The output directory must not be the repository root itself.'
}
if ($repoFull.StartsWith($outFull + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'The output directory must not be a parent of the repository.'
}
if ($MaxChunkSizeMB -lt 1) { $MaxChunkSizeMB = 1 }
$maxBytes = [long]$MaxChunkSizeMB * 1MB

Write-Host ("Repository             : {0}" -f $repoFull)
Write-Host ("Output directory       : {0}" -f $outFull)
Write-Host ("Per-file size limit    : {0}" -f (Format-Bytes $maxBytes))
Write-Host ("Exclude tests          : {0}" -f [bool]$ExcludeTests)
Write-Host ("Exclude vendored code  : {0}" -f [bool]$ExcludeVendoredSources)
if ($DryRun) { Write-Host 'Mode                   : DRY RUN (nothing will be written)' -ForegroundColor Yellow }
Write-Host ''

if (-not $DryRun) {
    if (Test-Path -LiteralPath $outFull) {
        Write-Host 'Removing previous export...'
        Remove-Item -LiteralPath $outFull -Recurse -Force
    }
    New-Item -ItemType Directory -Path $outFull -Force | Out-Null
}

Write-Host 'Scanning repository...'
$scan = Invoke-ProjectScan -Root $repoFull -ExcludePath $outFull
$plan = Get-FilePlan -Files $scan.Files -RepoRoot $repoFull `
            -SkipTests:$ExcludeTests -SkipVendored:$ExcludeVendoredSources
Write-Host ("  discovered {0} file(s), pruned {1} director(ies)" -f $scan.Files.Count, $scan.ExcludedDirs.Count)

$git           = Get-GitFacts -RepoRoot $repoFull
$modules       = Get-GradleModules -RepoRoot $repoFull
$catalog       = Get-VersionCatalog -Path (Join-Path $repoFull 'gradle\libs.versions.toml')
$appFacts      = Get-GradleFacts -RepoRoot $repoFull -Module 'app'
$manifestFacts = Get-ManifestFacts -Path (Join-Path $repoFull 'app\src\main\AndroidManifest.xml')

$buildFiles = @()
foreach ($m in $modules) {
    foreach ($n in @('build.gradle.kts', 'build.gradle')) {
        $p = Join-Path (Join-Path $repoFull $m) $n
        if (Test-Path -LiteralPath $p) { $buildFiles += $p }
    }
}
$usage = Get-VersionCatalogUsage -RepoRoot $repoFull -BuildFiles $buildFiles

$redactionLog  = New-Object 'System.Collections.Generic.List[object]'
$manifestFiles = New-Object 'System.Collections.Generic.List[object]'

Write-Host 'Reading and redacting files...'
$sourceEntries = New-SectionEntries -Plan $plan -Sections @('source', 'dependencies') -RedactionLog $redactionLog
$docEntries    = New-SectionEntries -Plan $plan -Sections @('documentation') -RedactionLog $redactionLog
$depEntries    = New-SectionEntries -Plan $plan -Sections @('dependencies') -RedactionLog $redactionLog

if (-not $DryRun) {
    Write-Host 'Writing sections...'

    $overviewText    = Build-ProjectOverview -RepoRoot $repoFull -Plan $plan -Catalog $catalog -Modules $modules -AppFacts $appFacts -ManifestFacts $manifestFacts
    $structureText   = Build-StructureTree -Plan $plan -ExcludedDirs $scan.ExcludedDirs -RepoRoot $repoFull
    $resourcesText   = Build-ResourceInventory -Plan $plan
    $dependenciesText = Build-Dependencies -RepoRoot $repoFull -Catalog $catalog -Modules $modules -Usage $usage
    $gitText         = Build-GitStatus -Git $git -RepoRoot $repoFull

    Write-ChunkedSection -BaseName 'PROJECT_OVERVIEW' -OutDir $outFull -MaxBytes $maxBytes -Manifest $manifestFiles `
        -Description 'Architecture, technologies, frameworks, application purpose and build configuration.' `
        -Entries (New-SingleEntry -Label 'generated project overview' -Text $overviewText)

    Write-ChunkedSection -BaseName 'PROJECT_STRUCTURE' -OutDir $outFull -MaxBytes $maxBytes -Manifest $manifestFiles `
        -Description 'Complete relevant directory tree.' `
        -Entries (New-SingleEntry -Label 'generated directory tree' -Text $structureText)

    Write-ChunkedSection -BaseName 'SOURCE_CODE' -OutDir $outFull -MaxBytes $maxBytes -Manifest $manifestFiles `
        -Description 'Source code with exact repository paths and complete file contents.' `
        -Entries $sourceEntries

    Write-ChunkedSection -BaseName 'RESOURCES_AND_ASSETS' -OutDir $outFull -MaxBytes $maxBytes -Manifest $manifestFiles `
        -Description 'Resource and asset inventory including filenames, formats and dimensions.' `
        -Entries (New-SingleEntry -Label 'generated resource inventory' -Text $resourcesText)

    $dependencyEntries = New-Object 'System.Collections.Generic.List[object]'
    $dependencyEntries.Add((New-SingleEntry -Label 'resolved dependency catalogue' -Text $dependenciesText)[0])
    foreach ($e in $depEntries) { $dependencyEntries.Add($e) }

    Write-ChunkedSection -BaseName 'DEPENDENCIES' -OutDir $outFull -MaxBytes $maxBytes -Manifest $manifestFiles `
        -Description 'All dependencies and versions, plus the complete Gradle configuration.' `
        -Entries $dependencyEntries

    Write-ChunkedSection -BaseName 'DOCUMENTATION' -OutDir $outFull -MaxBytes $maxBytes -Manifest $manifestFiles `
        -Description 'Project documentation, licences and store listings.' `
        -Entries $docEntries

    Write-ChunkedSection -BaseName 'GIT_STATUS' -OutDir $outFull -MaxBytes $maxBytes -Manifest $manifestFiles `
        -Description 'Current branch, latest commit, modified files and uncommitted changes.' `
        -Entries (New-SingleEntry -Label 'generated git status' -Text $gitText)

    # ------------------------------------------------------------------ README
    $readme = New-SectionBuilder
    Add-Heading -Builder $readme -Text 'HOW TO USE THIS CONTEXT BUNDLE'
    Add-Line -Builder $readme 'This directory is a self-contained snapshot of the ZenLabs File Explorer project,'
    Add-Line -Builder $readme 'produced by export_ai_context.ps1. It is intended to be handed to an AI that has no'
    Add-Line -Builder $readme 'access to the original filesystem and no network connectivity.'
    Add-Line -Builder $readme ''
    Add-Heading -Builder $readme -Text 'RECOMMENDED READING ORDER'
    Add-Line -Builder $readme '  1. PROJECT_OVERVIEW.txt     - what the project is, how it is built, ABI/permission facts'
    Add-Line -Builder $readme '  2. PROJECT_STRUCTURE.txt    - where everything lives'
    Add-Line -Builder $readme '  3. DEPENDENCIES.txt         - exact dependency versions and Gradle configuration'
    Add-Line -Builder $readme '  4. GIT_STATUS.txt           - the exact revision and working-tree state'
    Add-Line -Builder $readme '  5. SOURCE_CODE.txt          - the code, for making changes'
    Add-Line -Builder $readme '  6. RESOURCES_AND_ASSETS.txt - what ships in the APK (names, formats, dimensions)'
    Add-Line -Builder $readme '  7. DOCUMENTATION.txt        - README, docs/, licence, store listing'
    Add-Line -Builder $readme '  8. MANIFEST.txt             - what is included, what is not, and why'
    Add-Line -Builder $readme ''

    Add-Heading -Builder $readme -Text 'NOTES FOR THE AI'
    Add-Line -Builder $readme '  - Every file in SOURCE_CODE.txt is wrapped in a header and a footer:'
    Add-Line -Builder $readme '        FILE  : <path relative to the repository root>'
    Add-Line -Builder $readme '        ...content...'
    Add-Line -Builder $readme '        END OF FILE: <same path>'
    Add-Line -Builder $readme '    Use those paths verbatim when referring to a file in a proposed change.'
    Add-Line -Builder $readme '  - Build files appear both in SOURCE_CODE.txt and in DEPENDENCIES.txt. The'
    Add-Line -Builder $readme '    DEPENDENCIES copy additionally contains the resolved dependency table.'
    Add-Line -Builder $readme '  - Binary files (native .so libraries, images, ONNX models, archives, fonts) are'
    Add-Line -Builder $readme '    not reproduced. RESOURCES_AND_ASSETS.txt lists each one with its path, format,'
    Add-Line -Builder $readme '    size and - for images - pixel dimensions.'
    Add-Line -Builder $readme '  - Credential-shaped values are replaced with <<REDACTED:...>> markers. The'
    Add-Line -Builder $readme '    surrounding code is intact; only the secret literal is gone. MANIFEST.txt'
    Add-Line -Builder $readme '    section 5 lists every redaction.'
    Add-Line -Builder $readme '  - If a section was split, the canonical file name (for example SOURCE_CODE.txt)'
    Add-Line -Builder $readme '    is an index listing the numbered part files. Read all parts in order.'
    Add-Line -Builder $readme ''
    Add-Heading -Builder $readme -Text 'REGENERATING THIS BUNDLE'
    Add-Line -Builder $readme 'From the repository root:'
    Add-Line -Builder $readme '  .\export_ai_context.ps1'
    Add-Line -Builder $readme 'The script only reads the repository; it never modifies the application.'
    $readmePath = Join-Path $outFull 'README.txt'
    [System.IO.File]::WriteAllText($readmePath, $readme.ToString(), $Script:Utf8NoBom)
    $manifestFiles.Add([pscustomobject]@{ File = 'README.txt'; Section = 'README'; Bytes = (Get-Item -LiteralPath $readmePath).Length; Note = 'how to use this bundle' })

    # ---------------------------------------------------------------- MANIFEST
    $manifestText = Build-Manifest -Plan $plan -ExcludedDirs $scan.ExcludedDirs -WrittenFiles $manifestFiles `
                                  -Redactions $redactionLog -RepoRoot $repoFull -OutDir $outFull
    $manifestPath = Join-Path $outFull 'MANIFEST.txt'
    [System.IO.File]::WriteAllText($manifestPath, $manifestText, $Script:Utf8NoBom)
    $manifestFiles.Add([pscustomobject]@{ File = 'MANIFEST.txt'; Section = 'MANIFEST'; Bytes = (Get-Item -LiteralPath $manifestPath).Length; Note = 'exported and excluded files' })

    # ----------------------------------------------------------------- SUMMARY
    $exportedSource = @($plan | Where-Object { $_.Section -ne 'excluded' }).Count
    $excludedFiles  = @($plan | Where-Object { $_.Section -eq 'excluded' }).Count
    $redactionCount = 0
    foreach ($r in $redactionLog) { $redactionCount += $r.Count }
    $totalBytes = 0L
    foreach ($f in (Get-ChildItem -LiteralPath $outFull -File)) { $totalBytes += $f.Length }
    $outputFileCount = (Get-ChildItem -LiteralPath $outFull -File).Count

    $summary = New-SectionBuilder
    Add-Heading -Builder $summary -Text 'EXPORT SUMMARY'
    Add-Line -Builder $summary ("Generated             : {0}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz'))
    Add-Line -Builder $summary ("Source repository      : {0}" -f $repoFull)
    Add-Line -Builder $summary ("Export directory       : {0}" -f $outFull)
    Add-Line -Builder $summary ("Files scanned          : {0}" -f $scan.Files.Count)
    Add-Line -Builder $summary ("Files exported         : {0}" -f $exportedSource)
    Add-Line -Builder $summary ("Files excluded         : {0}" -f $excludedFiles)
    Add-Line -Builder $summary ("Directories pruned     : {0}" -f $scan.ExcludedDirs.Count)
    Add-Line -Builder $summary ("Redactions applied     : {0}" -f $redactionCount)
    Add-Line -Builder $summary ("Output files written   : {0}" -f $outputFileCount)
    Add-Line -Builder $summary ("Total export size      : {0} ({1} bytes)" -f (Format-Bytes $totalBytes), $totalBytes)
    Add-Line -Builder $summary ("Per-file size limit    : {0}" -f (Format-Bytes $maxBytes))
    $summaryPath = Join-Path $outFull 'EXPORT_SUMMARY.txt'
    [System.IO.File]::WriteAllText($summaryPath, $summary.ToString(), $Script:Utf8NoBom)

    Write-Host ''
    Write-Host ('=' * 72)
    Write-Host 'EXPORT COMPLETE' -ForegroundColor Green
    Write-Host ('=' * 72)
    Write-Host ("  Files scanned        : {0}" -f $scan.Files.Count)
    Write-Host ("  Files exported       : {0}" -f $exportedSource)
    Write-Host ("  Files excluded       : {0}" -f $excludedFiles)
    Write-Host ("  Directories pruned   : {0}" -f $scan.ExcludedDirs.Count)
    Write-Host ("  Redactions applied   : {0}" -f $redactionCount)
    Write-Host ("  Output files written : {0}" -f $outputFileCount)
    Write-Host ("  Total export size    : {0}" -f (Format-Bytes $totalBytes))
    Write-Host ("  Export directory     : {0}" -f $outFull)
    Write-Host ''
    Get-ChildItem -LiteralPath $outFull -File | Sort-Object Name | ForEach-Object {
        Write-Host ("    {0,-44} {1,10}" -f $_.Name, (Format-Bytes $_.Length))
    }
    Write-Host ''
}
else {
    $exportedSource = @($plan | Where-Object { $_.Section -ne 'excluded' }).Count
    $excludedFiles  = @($plan | Where-Object { $_.Section -eq 'excluded' }).Count
    $redactionCount = 0
    foreach ($r in $redactionLog) { $redactionCount += $r.Count }
    Write-Host ''
    Write-Host 'DRY RUN SUMMARY (nothing written)' -ForegroundColor Yellow
    Write-Host ("  Files scanned               : {0}" -f $scan.Files.Count)
    Write-Host ("  Files that would be exported : {0}" -f $exportedSource)
    Write-Host ("  Files that would be excluded : {0}" -f $excludedFiles)
    Write-Host ("  Directories pruned           : {0}" -f $scan.ExcludedDirs.Count)
    Write-Host ("  Redactions detected          : {0}" -f $redactionCount)
    Write-Host ''
}

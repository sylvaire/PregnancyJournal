param(
    [Parameter(Mandatory)][string]$Group,
    [Parameter(Mandatory)][string]$Artifact,
    [Parameter(Mandatory)][string]$Version
)
# Optional bootstrap for slow large downloads. Only official Maven Central, verified SHA-256.
$ErrorActionPreference = 'Stop'
foreach ($part in @($Group, $Artifact, $Version)) {
    if ($part -notmatch '^[A-Za-z0-9_.-]+$') { throw 'Invalid Maven coordinate' }
}
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$relative = $Group.Replace('.', '/') + '/' + $Artifact + '/' + $Version
$base = 'https://repo.maven.apache.org/maven2/' + $relative
$fileName = "$Artifact-$Version.jar"
$destination = Join-Path $root ('.local/maven/' + $relative)
$chunks = Join-Path $root ('.local/downloads/' + $Artifact + '-' + $Version)
New-Item -ItemType Directory -Path $destination, $chunks -Force | Out-Null
$head = & curl.exe -f -sS -I --max-time 20 "$base/$fileName"
if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect official artifact' }
$sizeMatch = [regex]::Match(($head -join "`n"), '(?im)^Content-Length:\s*(\d+)')
if (-not $sizeMatch.Success) { throw 'Missing content length' }
$size = [long]$sizeMatch.Groups[1].Value
if ($size -gt 250MB) { throw 'Artifact exceeds bootstrap limit' }
$expected = ((& curl.exe -f -sS --max-time 20 "$base/$fileName.sha256") -join '').Trim()
if ($LASTEXITCODE -ne 0 -or $expected -notmatch '^[a-fA-F0-9]{64}$') { throw 'Missing official SHA-256' }
$chunkSize = 1MB
$count = [int][Math]::Ceiling($size / $chunkSize)
$indices = 0..($count - 1)
$indices | ForEach-Object -Parallel {
    $start = [long]$_ * $using:chunkSize
    $end = [Math]::Min($using:size - 1, $start + $using:chunkSize - 1)
    $chunk = Join-Path $using:chunks "$_.bin"
    $header = Join-Path $using:chunks "$_.headers"
    & curl.exe -f -sS --retry 2 --connect-timeout 10 --max-time 60 -r "$start-$end" -D $header "$using:base/$using:fileName" -o $chunk
    if ($LASTEXITCODE -ne 0) { throw "Chunk $_ failed" }
    $response = [IO.File]::ReadAllText($header)
    if ($response -notmatch '(?im)^HTTP/\S+\s+206\b' -or
        $response -notmatch "(?im)^Content-Range:\s*bytes $start-$end/$using:size" -or
        (Get-Item -LiteralPath $chunk).Length -ne ($end - $start + 1)) {
        throw "Invalid range response for chunk $_"
    }
} -ThrottleLimit 8
$target = Join-Path $destination $fileName
$output = [IO.File]::Create($target)
try {
    foreach ($index in $indices) {
        $input = [IO.File]::OpenRead((Join-Path $chunks "$index.bin"))
        try { $input.CopyTo($output) } finally { $input.Dispose() }
    }
} finally { $output.Dispose() }
if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $expected) { throw 'Official SHA-256 verification failed' }
& curl.exe -f -sS --max-time 30 "$base/$Artifact-$Version.pom" -o (Join-Path $destination "$Artifact-$Version.pom")
if ($LASTEXITCODE -ne 0) { throw 'Missing official POM' }
Write-Output "Verified $Group`:$Artifact`:$Version ($size bytes)"

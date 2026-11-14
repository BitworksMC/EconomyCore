param(
    [Parameter(Mandatory)][string[]]$ServerDirectory,
    [Parameter(Mandatory)][string]$JavaHome,
    [string]$PluginJar = 'PaperCore/target/TNE-Paper-0.1.5.4.jar',
    [ValidateSet('SaveProbe', 'LifecycleProbe')][string]$Probe = 'SaveProbe'
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$testRoot = (Resolve-Path (Join-Path $repo '.testserver')).Path
$artifact = (Resolve-Path (Join-Path $repo $PluginJar)).Path
$servers = @($ServerDirectory | ForEach-Object {
    $resolved = (Resolve-Path (Join-Path $repo $_)).Path
    if (!$resolved.StartsWith($testRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Test servers must be inside $testRoot; the probes stop the server."
    }
    if (!(Get-Content (Join-Path $resolved 'server.properties') | Where-Object { $_ -eq 'server-ip=127.0.0.1' })) {
        throw "Test server must bind to 127.0.0.1: $resolved"
    }
    $resolved
})
$build = Join-Path $testRoot ('storage-regression/probe-' + [guid]::NewGuid().ToString('N'))
$classes = Join-Path $build 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$dependencies = @($artifact) + @(Get-ChildItem (Join-Path $servers[0] 'libraries') -Recurse -Filter '*.jar' | ForEach-Object FullName)
$source = Join-Path $PSScriptRoot ($Probe + '.java')
$scheduler = Join-Path $PSScriptRoot '../support/ProbeScheduler.java'
& (Join-Path $JavaHome 'bin/javac.exe') --release 21 -cp ($dependencies -join ';') -d $classes $source $scheduler
if ($LASTEXITCODE -ne 0) { throw "Cannot compile $Probe" }
$descriptor = if ($Probe -eq 'SaveProbe') { 'plugin.yml' } else { 'lifecycle-plugin.yml' }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot $descriptor) -Destination (Join-Path $classes 'plugin.yml')
$probeJar = Join-Path $build ($Probe + '.jar')
& (Join-Path $JavaHome 'bin/jar.exe') --create --file $probeJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw "Cannot package $Probe" }

foreach ($server in $servers) {
    $plugins = Join-Path $server 'plugins'
    $installed = @(Get-ChildItem $plugins -Filter 'TNE-*.jar')
    if ($installed.Count -ne 1) { throw "Expected exactly one TNE JAR in $plugins" }
    $otherProbes = @(Get-ChildItem $plugins -Filter '*.jar' | Where-Object {
        $_.BaseName -in @('SaveProbe', 'LifecycleProbe', 'ItemRegression', 'InventoryAudit', 'DeleteOrderProbe', 'FoliaSchedulerProbe', 'OnlineItemProbe', 'ReceiptLoadProbe') -and $_.BaseName -ne $Probe
    })
    if ($otherProbes.Count -gt 0) { throw "Remove other server-stopping probes from $plugins before running $Probe" }
    Copy-Item -LiteralPath $artifact -Destination $installed[0].FullName
    Copy-Item -LiteralPath $probeJar -Destination $plugins
    $serverJar = @('paper.jar', 'leaf.jar', 'folia.jar') | Where-Object { Test-Path (Join-Path $server $_) }
    if (@($serverJar).Count -ne 1) { throw "Expected one server JAR in $server" }
    Push-Location $server
    try {
        foreach ($phase in 1..2) {
            $logFile = ($Probe.ToLowerInvariant() + '-validation-' + $phase + '.log')
            & (Join-Path $JavaHome 'bin/java.exe') -Xms256M -Xmx1536M '-Dterminal.jline=false' '-Dterminal.ansi=false' -jar $serverJar --nogui *> $logFile
            if ($LASTEXITCODE -ne 0) { throw "Server exited with $LASTEXITCODE; see $server/$logFile" }
            $log = Get-Content -LiteralPath $logFile -Raw
            $marker = if ($Probe -eq 'SaveProbe') { 'SAVE_PROBE_RESULT' } else { 'LIFECYCLE_PROBE_RESULT' }
            $log -split "`r?`n" | Select-String "Loading (Paper|Folia)|$marker"
            if ($log -notmatch "$marker phase=$phase failures=0") {
                throw "Storage regression checks failed; see $server/$logFile"
            }
            if ($log -match 'Error occurred while (enabling|disabling) TheNewEconomy|Plugin attempted to register task while disabled|The server has not responded|has not responded for') {
                throw "TNE lifecycle or watchdog failure; see $server/$logFile"
            }
        }
    } finally {
        Pop-Location
    }
}

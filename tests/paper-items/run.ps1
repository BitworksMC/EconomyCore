param(
    [Parameter(Mandatory)][string[]]$ServerDirectory,
    [Parameter(Mandatory)][string]$JavaHome,
    [string]$PluginJar = ''
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$testRoot = (Resolve-Path (Join-Path $repo '.testserver')).Path
$compileJar = if ($PluginJar) { $PluginJar } else { 'PaperCore/target/TNE-Paper-0.1.5.4.jar' }
$artifact = (Resolve-Path (Join-Path $repo $compileJar)).Path
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
$build = Join-Path $testRoot ('item-regression/probe-' + [guid]::NewGuid().ToString('N'))
$dependencies = @($artifact) + @(Get-ChildItem (Join-Path $servers[0] 'libraries') -Recurse -Filter '*.jar' | ForEach-Object FullName)
$probes = @(
    @{ Name = 'ItemRegression'; Descriptor = 'plugin.yml' },
    @{ Name = 'InventoryAudit'; Descriptor = 'inventory-plugin.yml' }
)
foreach ($probe in $probes) {
    $classes = Join-Path $build $probe.Name
    New-Item -ItemType Directory -Force -Path $classes | Out-Null
    & (Join-Path $JavaHome 'bin/javac.exe') --release 21 -cp ($dependencies -join ';') -d $classes (Join-Path $PSScriptRoot '../support/ProbeScheduler.java') (Join-Path $PSScriptRoot ($probe.Name + '.java'))
    if ($LASTEXITCODE -ne 0) { throw "Cannot compile $($probe.Name)" }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $probe.Descriptor) -Destination (Join-Path $classes 'plugin.yml')
    & (Join-Path $JavaHome 'bin/jar.exe') --create --file (Join-Path $build ($probe.Name + '.jar')) -C $classes .
    if ($LASTEXITCODE -ne 0) { throw "Cannot package $($probe.Name)" }
}

foreach ($server in $servers) {
    $serverJar = @('paper.jar', 'leaf.jar', 'folia.jar') | Where-Object { Test-Path (Join-Path $server $_) }
    if (@($serverJar).Count -ne 1) { throw "Expected one server JAR in $server" }
    $selectedJar = if ($PluginJar) { $PluginJar } elseif ($serverJar -eq 'folia.jar') { 'Folia/target/TNE-Folia-0.1.5.4.jar' } else { $compileJar }
    $selectedArtifact = (Resolve-Path (Join-Path $repo $selectedJar)).Path
    $plugins = Join-Path $server 'plugins'
    $otherProbes = @(Get-ChildItem $plugins -Filter '*.jar' | Where-Object {
        $_.BaseName -in @('SaveProbe', 'LifecycleProbe', 'DeleteOrderProbe', 'FoliaSchedulerProbe', 'OnlineItemProbe')
    })
    if ($otherProbes.Count -gt 0) { throw "Remove other server-stopping probes from $plugins before running item probes" }
    $installed = @(Get-ChildItem $plugins -Filter 'TNE-*.jar')
    if ($installed.Count -ne 1) { throw "Expected exactly one TNE JAR in $plugins" }
    $stoppingProbes = @(Get-ChildItem $plugins -Filter '*.jar' | Where-Object {
        $_.BaseName -in @('SaveProbe', 'LifecycleProbe', 'DeleteOrderProbe', 'FoliaSchedulerProbe', 'OnlineItemProbe', 'ReceiptLoadProbe')
    })
    if ($stoppingProbes.Count) { throw "Remove other server-stopping probes from $plugins before running item tests" }
    Copy-Item -LiteralPath $selectedArtifact -Destination $installed[0].FullName
    foreach ($probe in $probes) {
        Copy-Item -LiteralPath (Join-Path $build ($probe.Name + '.jar')) -Destination $plugins
    }
    Push-Location $server
    try {
        & (Join-Path $JavaHome 'bin/java.exe') -Xms256M -Xmx1536M '-Dterminal.jline=false' '-Dterminal.ansi=false' -jar $serverJar --nogui *> regression-validation.log
        if ($LASTEXITCODE -ne 0) { throw "Server exited with $LASTEXITCODE; see $server/regression-validation.log" }
        $log = Get-Content -LiteralPath regression-validation.log -Raw
        $log -split "`r?`n" | Select-String 'Loading (Paper|Leaf|Folia)|ITEM_REGRESSION_RESULT|AUDIT_RESULT'
        if ($log -match 'Error occurred while (enabling|disabling)|TNE storage task failed') {
            throw "Plugin lifecycle failed; see $server/regression-validation.log"
        }
        if ($log -notmatch 'ITEM_REGRESSION_RESULT checks=\d+ failures=0' -or $log -notmatch 'AUDIT_RESULT attempts=\d+ failingGroups=0') {
            throw "Item regression checks failed; see $server/regression-validation.log"
        }
    } finally {
        Pop-Location
    }
}

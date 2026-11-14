param(
    [Parameter(Mandatory)][string]$JavaHome,
    [Parameter(Mandatory)][int]$RedisPort,
    [string]$MavenRepository = (Join-Path $env:USERPROFILE '.m2/repository')
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$build = Join-Path $repo ('.testserver/redis-regression/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $build | Out-Null
$dependencies = @(
    'com/google/guava/guava',
    'redis/clients/jedis',
    'redis/clients/authentication/redis-authx-core',
    'org/apache/commons/commons-pool2',
    'org/slf4j/slf4j-api',
    'org/json/json',
    'com/google/code/gson/gson'
) | ForEach-Object {
    $jar = Get-ChildItem (Join-Path $MavenRepository $_) -Recurse -Filter '*.jar' |
        Where-Object { $_.Name -notmatch '-(sources|javadoc|tests)\.jar$' } |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (!$jar) { throw "Missing dependency: $_. Build the project first." }
    $jar.FullName
}
$classpath = $dependencies -join ';'
$sources = @(Get-ChildItem (Join-Path $PSScriptRoot 'stubs') -Recurse -Filter '*.java' | ForEach-Object FullName) + @(
    (Join-Path $repo 'Core/src/net/tnemc/core/channel/RedisSync.java'),
    (Join-Path $PSScriptRoot 'RedisSyncRegression.java')
)
& (Join-Path $JavaHome 'bin/javac.exe') --release 21 -cp $classpath -d (Join-Path $build 'classes') $sources
if ($LASTEXITCODE -ne 0) { throw 'Redis regression compilation failed' }
& (Join-Path $JavaHome 'bin/java.exe') -cp ((Join-Path $build 'classes') + ';' + $classpath) RedisSyncRegression $RedisPort (Join-Path $build 'data') 2>&1 |
    Tee-Object (Join-Path $build 'validation.log')
if ($LASTEXITCODE -ne 0) { throw "Redis regression failed: $build/validation.log" }
Write-Output "Redis regression evidence: $build/validation.log"

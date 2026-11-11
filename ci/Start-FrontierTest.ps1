param([string]$Java = 'java', [switch]$AcceptEula, [ValidateSet('Paper', 'Leaf')][string]$Runtime = 'Paper')
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if ($AcceptEula) { 'eula=true' | Set-Content -LiteralPath 'eula.txt' }
if ((Get-Content -LiteralPath 'eula.txt') -notcontains 'eula=true') {
    throw 'Read https://aka.ms/MinecraftEULA and, if you agree, rerun with -AcceptEula.'
}
$serverJar = if ($Runtime -eq 'Leaf') { 'leaf-26.3-22.jar' } else { 'paper.jar' }
if (!(Test-Path -LiteralPath $serverJar)) { throw "Missing $Runtime server JAR: $serverJar" }
& $Java -Xms1G -Xmx4G -jar $serverJar --nogui
exit $LASTEXITCODE

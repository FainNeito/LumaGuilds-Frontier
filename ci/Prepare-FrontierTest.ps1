param([string]$Destination = (Join-Path $PSScriptRoot '../../frontier-test-server'))
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$target = [IO.Path]::GetFullPath($Destination)
if (Test-Path (Join-Path $target 'plugins/LumaGuilds/lumaguilds.db')) { throw 'Refusing to replace an existing server profile.' }
if (Test-Path (Join-Path $target 'frontier/level.dat')) { throw 'Refusing to replace a world that has already been started.' }

function Set-YamlScalar([System.Collections.Generic.List[string]]$Lines, [string]$Path, [string]$Value) {
    $parts = $Path.Split('.')
    $start = 0
    $end = $Lines.Count
    for ($depth = 0; $depth -lt $parts.Length; $depth++) {
        $indent = ' ' * ($depth * 2)
        $pattern = '^' + $indent + [regex]::Escape($parts[$depth]) + ':'
        $found = -1
        for ($i = $start; $i -lt $end; $i++) { if ($Lines[$i] -match $pattern) { $found = $i; break } }
        if ($found -lt 0) { throw "Missing configuration key: $Path" }
        if ($depth -eq $parts.Length - 1) { $Lines[$found] = "$indent$($parts[$depth]): $Value"; return }
        $start = $found + 1
        for ($i = $start; $i -lt $end; $i++) {
            if ($Lines[$i] -match '^(\s*)[^\s#]' -and $Matches[1].Length -le $depth * 2) { $end = $i; break }
        }
    }
}

New-Item -ItemType Directory -Force -Path (Join-Path $target 'plugins/LumaGuilds'), (Join-Path $target 'plugins/EnthusiaFrontier') | Out-Null
[System.Collections.Generic.List[string]]$lines = Get-Content (Join-Path $repo 'src/main/resources/config.yml')
$overrides = @{
    'frontier.enabled'='true'; 'claim_geometry'='CHUNKS'; 'database_type'='sqlite';
    'frontier.raid.timezone'='America/New_York'; 'frontier.raid.start'="'20:00'";
    'parties_enabled'='false'; 'distance_between_claims'='0';
    'guild.peaceful_mode_enabled'='false'; 'guild.mode_switching_enabled'='false';
    'vault.bank_mode'='PHYSICAL'; 'vault.require_economy_plugin'='false'; 'vault.virtual_bank_fallback'='false';
    'vault.capacity_scaling_enabled'='false'; 'vault.base_capacity_slots'='54';
    'progression.chapter_two_rewards_enabled'='false'; 'progression.chapter_two_gold_costs_enabled'='false';
    'progression.prestige.enabled'='false'; 'discord.guild_roles.enabled'='false';
    'apollo.enabled'='false'; 'strikes.enabled'='false'; 'seasonal_elo.enabled'='false'
}
foreach ($key in $overrides.Keys) { Set-YamlScalar $lines $key $overrides[$key] }
[IO.File]::WriteAllLines((Join-Path $target 'plugins/LumaGuilds/config.yml'), $lines)
[System.Collections.Generic.List[string]]$frontier = Get-Content (Join-Path $repo '../EnthusiaFrontier/src/main/resources/config.yml')
$frontierText = ($frontier -join "`n").Replace('  world:', '  frontier:').Replace('  world_nether:', '  frontier_nether:').Replace('  world_the_end:', '  frontier_the_end:')
[System.Collections.Generic.List[string]]$frontier = $frontierText.Split("`n")
Set-YamlScalar $frontier 'worlds.frontier.core-radius-blocks' '0'
Set-YamlScalar $frontier 'cleanup.enabled' 'false'
[IO.File]::WriteAllLines((Join-Path $target 'plugins/EnthusiaFrontier/config.yml'), $frontier)

$artifacts = @{
    'plugins/LumaGuilds.jar'=(Join-Path $repo 'build/libs/LumaGuilds-3.0.0-frontier-test.12.jar');
    'plugins/RoseChat.jar'=(Join-Path $repo 'libs/RoseChat-RC-2.jar');
    'plugins/Vault.jar'=(Join-Path $repo 'libs/Vault-1.7.3.jar');
    'plugins/ViaVersion.jar'=(Join-Path $repo 'libs/ViaVersion-5.12.2.jar');
    'plugins/ViaBackwards.jar'=(Join-Path $repo 'libs/ViaBackwards-5.12.2.jar');
    'plugins/TAB.jar'=(Join-Path $repo 'libs/TAB-6.2.1.jar');
    'plugins/EnthusiaFrontier.jar'=(Join-Path $repo '../EnthusiaFrontier/build/libs/EnthusiaFrontier-0.1.2.jar')
}
foreach ($file in $artifacts.Keys) { Copy-Item -LiteralPath $artifacts[$file] -Destination (Join-Path $target $file) }
Copy-Item -Recurse -Force -LiteralPath (Join-Path $PSScriptRoot 'frontier/TAB') -Destination (Join-Path $target 'plugins/TAB')
Copy-Item -Recurse -Force -LiteralPath (Join-Path $PSScriptRoot 'frontier/ViaVersion') -Destination (Join-Path $target 'plugins/ViaVersion')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Start-FrontierTest.ps1') -Destination (Join-Path $target 'Start-FrontierTest.ps1')
Copy-Item -LiteralPath (Join-Path $repo 'docs/FRONTIER_TESTING.md') -Destination (Join-Path $target 'README.md')
@'
server-port=25566
server-ip=127.0.0.1
level-name=frontier
motd=Frontier isolated test server
online-mode=true
white-list=true
enforce-whitelist=true
max-players=12
pvp=true
difficulty=hard
gamemode=survival
spawn-protection=0
view-distance=8
simulation-distance=6
enable-rcon=false
enable-query=false
allow-nether=true
'@ | Set-Content -LiteralPath (Join-Path $target 'server.properties')
'eula=false' | Set-Content -LiteralPath (Join-Path $target 'eula.txt')
@'
frontier.player:
  default: true
  children:
    bellclaims.action.bell: true
    rosechat.channel.guild: true
    rosechat.channel.guild-ally: true
    rosechat.channel.guild-modchat: true
'@ | Set-Content -LiteralPath (Join-Path $target 'permissions.yml')
@'
settings:
  allow-end: true
'@ | Set-Content -LiteralPath (Join-Path $target 'bukkit.yml')
& (Join-Path $PSScriptRoot "Prepare-FrontierPresentation.ps1") -Destination $target
$hashes = Get-ChildItem -LiteralPath (Join-Path $target 'plugins') -Filter '*.jar' | Get-FileHash -Algorithm SHA256
$hashes | Select-Object @{n='File';e={Split-Path $_.Path -Leaf}},Hash | ConvertTo-Json | Set-Content (Join-Path $target 'plugin-checksums.json')
Write-Output "Prepared isolated server: $target"

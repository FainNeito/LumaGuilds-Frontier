param([Parameter(Mandatory=$true)][string]$Destination)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
New-Item -ItemType Directory -Force -Path "$Destination/plugins/RoseChat", "$Destination/plugins/LumaGuilds/lang" | Out-Null
Copy-Item "$PSScriptRoot/frontier/channels.yml" "$Destination/plugins/RoseChat/channels.yml"
$locale = Get-Content -Raw "$repo/src/main/resources/lang/en_US.yml"
$colors = @{red='#C0261D';dark_red='#A6221A';gold='#D6B56C';yellow='#E2C68A';green='#D6B56C';dark_green='#BA9B59';aqua='#D5A09A';blue='#C9926C';dark_blue='#C9926C';dark_aqua='#D5A09A';light_purple='#D5A09A';dark_purple='#D5A09A';white='#EFE5D0';gray='#B5ACA0';dark_gray='#81796D'}
foreach ($name in $colors.Keys) {
    $locale = $locale.Replace("<$name>","<$($colors[$name])>").Replace("</$name>","</$($colors[$name])>")
}
$locale | Set-Content "$Destination/plugins/LumaGuilds/lang/en_US.yml"

# Complete the chat locale without changing commands, placeholders or text.
New-Item -ItemType Directory -Force -Path "$Destination/plugins/RoseChat/locale" | Out-Null
$chatLocale = Get-Content -Raw "$repo/../RoseChat/src/main/resources/locale/en_US.yml"
$legacy = @{c='#C0261D';'4'='#A6221A';'6'='#D6B56C';e='#E2C68A';a='#D6B56C';'2'='#BA9B59';b='#D5A09A';'3'='#D5A09A';'9'='#C9926C';'1'='#C9926C';d='#D5A09A';'5'='#D5A09A';f='#EFE5D0';'7'='#B5ACA0';'8'='#81796D'}
foreach ($key in $legacy.Keys) { $chatLocale = $chatLocale.Replace("&$key", "&$($legacy[$key])") }
$chatLocale = $chatLocale.Replace('<g:#8A2387:#E94057:#F27121>', '<g:#C0261D:#D13B30>')
$chatLocale | Set-Content "$Destination/plugins/RoseChat/locale/en_US.yml"
$placeholders = Get-Content -Raw "$repo/../RoseChat/src/main/resources/custom-placeholders.yml"
foreach ($key in $legacy.Keys) { $placeholders = $placeholders.Replace("&$key", "&$($legacy[$key])") }
$placeholders = $placeholders.Replace('#C0FFEE', '#C0261D')
$placeholders | Set-Content "$Destination/plugins/RoseChat/custom-placeholders.yml"


$configPath = "$Destination/plugins/RoseChat/config.yml"
if (Test-Path $configPath) {
    $chatConfig = Get-Content -Raw $configPath
    $chatConfig = [regex]::Replace($chatConfig, '(?m)^  message-sound:.*$', '  message-sound: minecraft:block.note_block.harp')
    Set-Content $configPath $chatConfig
} else {
    "chat-settings:`n  message-sound: minecraft:block.note_block.harp" | Set-Content $configPath
}

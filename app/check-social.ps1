# Public upstream fixtures; records actual outcome, never deletes existing downloads.
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
function Adb { $r = & $adb @args; if ($LASTEXITCODE) { throw "adb: $args" }; $r }
function State { [xml]((Adb shell run-as com.rootlyt.aio cat shared_prefs/state.xml) -join "`n") }
if ((State).SelectSingleNode('//boolean[@name="busy"]').value -eq 'true') { throw 'Operation active' }
$urls = @('https://www.instagram.com/p/BQ0eAlwhDrw/', 'https://www.instagram.com/reel/Chunk8-jurw/', 'https://www.instagram.com/p/BoHk1haB5tM/', 'https://www.instagram.com/p/BqvsDleB3lV/', 'https://www.facebook.com/PankajShahLondon/posts/10157667649866271', 'https://www.facebook.com/joho.press.jp/posts/pfbid02mfFRpVkErLQxQ8cpD2f1hwXEVsFzK8kfNBKdK2Jndnx6AkmMQZuXhovwDgwvoDNil')
foreach ($url in $urls) {
    Adb shell am start -W -n com.rootlyt.aio/.MainActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT $url | Out-Null
    Start-Sleep -Seconds 3
    for ($i=0; $i -lt 65; $i++) {
        $s = State
        if ($s.SelectSingleNode('//boolean[@name="busy"]').value -eq 'false') { break }
        Start-Sleep -Seconds 2
    }
    if ($s.SelectSingleNode('//boolean[@name="busy"]').value -eq 'true') { throw 'Timed out' }
    $p = $s.SelectSingleNode('//string[@name="preview"]')
    $preview = if ($p) { $p.InnerText | ConvertFrom-Json } else { $null }
    [pscustomobject]@{url=$url; status=$s.SelectSingleNode('//string[@name="status"]').InnerText; images=$preview.imageCount; videos=$preview.videoCount; matches=($preview.url -eq $url)} | ConvertTo-Json -Compress
}

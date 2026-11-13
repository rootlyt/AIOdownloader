param([string]$Url = 'https://www.instagram.com/reel/Chunk8-jurw/')
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
function Adb { $r = & $adb @args; if ($LASTEXITCODE) { throw "adb failed: $args" }; $r }
function State { [xml]((Adb shell run-as com.rootlyt.aio cat shared_prefs/state.xml) -join "`n") }
function Idle {
    Start-Sleep -Seconds 3
    for ($i=0;$i -lt 90;$i++) {
        $s=State
        if ($s.SelectSingleNode('//boolean[@name="busy"]').value -eq 'false') { return $s }
        Start-Sleep -Seconds 2
    }
    throw 'Timeout'
}
if ((State).SelectSingleNode('//boolean[@name="busy"]').value -eq 'true') { throw 'Busy' }
$before = @(Adb shell ls /sdcard/Download/AIOdownloader)
Adb shell am start -W -n com.rootlyt.aio/.MainActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT $Url | Out-Null
$s=Idle
$p=$s.SelectSingleNode('//string[@name="preview"]')
if (!$p) { throw $s.SelectSingleNode('//string[@name="status"]').InnerText }
$preview=$p.InnerText | ConvertFrom-Json
if ($preview.url -ne $Url) { throw 'Wrong preview' }
Adb shell input swipe 500 1400 500 650 300 | Out-Null
Adb shell uiautomator dump /sdcard/aio-social.xml | Out-Null
[xml]$ui=(Adb shell cat /sdcard/aio-social.xml)-join "`n"
$n=$ui.SelectSingleNode('//node[starts-with(@text,"Download all ")]')
if (!$n) { throw 'Missing download button' }
$b=[regex]::Matches($n.bounds,'\d+') | ForEach-Object { [int]$_.Value }
Adb shell input tap ([int](($b[0]+$b[2])/2)) ([int](($b[1]+$b[3])/2)) | Out-Null
$s=Idle
$status=$s.SelectSingleNode('//string[@name="status"]').InnerText
$status
if ($status -notlike 'Saved *items to*') { throw 'Save failed' }
$after=@(Adb shell ls /sdcard/Download/AIOdownloader)
foreach ($f in $before) { if ($after -notcontains $f) { throw "Existing file missing: $f" } }
if ($after.Count -ne $before.Count+$preview.items.Count) { throw 'Wrong saved count' }
if ($s.SelectSingleNode('//string[@name="preview"]')) { throw 'Stale preview' }
Adb shell ls -l /sdcard/Download/AIOdownloader
'PASS: actual batch saved, preview reset, originals preserved.'

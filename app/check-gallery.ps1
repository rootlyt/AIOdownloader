# Adds one BMKG batch. Never deletes downloads. Run on unlocked debug device.
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
function Adb { $r = & $adb @args; if ($LASTEXITCODE) { throw "adb failed $args" }; $r }
function State { [xml]((Adb shell run-as com.rootlyt.aio cat shared_prefs/state.xml) -join "`n") }
function Idle {
    for ($i=0; $i -lt 120; $i++) { Start-Sleep 2; $s=State; if ($s.SelectSingleNode('//boolean[@name="busy"]').value -eq 'false') { return $s } }
    throw 'Timed out'
}
function Ui { Adb shell uiautomator dump /sdcard/aio-gallery.xml | Out-Null; [xml]((Adb shell cat /sdcard/aio-gallery.xml) -join "`n") }
function Tap($n) { if (!$n) { throw 'Missing control' }; $b=[regex]::Matches($n.bounds,'\d+') | ForEach-Object { [int]$_.Value }; Adb shell input tap ([int](($b[0]+$b[2])/2)) ([int](($b[1]+$b[3])/2)) | Out-Null }
if ((State).SelectSingleNode('//boolean[@name="busy"]').value -eq 'true') { throw 'Operation active' }
$before=@(Adb shell ls /sdcard/Download/AIOdownloader)
Adb shell am start -W -n com.rootlyt.aio/.MainActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT https://x.com/infoBMKG/status/2121157219890971057 | Out-Null
$s=Idle
if (($s.SelectSingleNode('//string[@name="preview"]').InnerText | ConvertFrom-Json).photos.Count -ne 2) { throw $s.OuterXml }
Adb shell input swipe 500 1400 500 650 300 | Out-Null
Tap ((Ui).SelectSingleNode('//node[@text="Download all 2 photos"]'))
$s=Idle
if ($s.SelectSingleNode('//string[@name="status"]').InnerText -notlike 'Saved 2/2 photos*') { throw $s.OuterXml }
$after=@(Adb shell ls /sdcard/Download/AIOdownloader)
foreach ($f in $before) { if ($after -notcontains $f) { throw "Missing original $f" } }
if ($after.Count -ne $before.Count+2) { throw 'Wrong file count' }
[xml]$records=(Adb shell run-as com.rootlyt.aio cat shared_prefs/gallery.xml) -join "`n"
$batch=@($records.map.string | ForEach-Object { $_.InnerText | ConvertFrom-Json } | Group-Object batch | Where-Object Count -eq 2)
if (!$batch) { throw 'No persistent pair' }
Tap ((Ui).SelectSingleNode('//node[@content-desc="Gallery, page 2 of 2"]'))
foreach ($layout in @('List','Grid')) {
    Tap ((Ui).SelectSingleNode("//node[@text='$layout']"))
    Tap ((Ui).SelectSingleNode('//node[@text="Show 2 items"]'))
    $ui=Ui
    if (!$ui.SelectSingleNode('//node[@text="2 saved items"]') -or !$ui.SelectSingleNode('//node[@text="Close"]')) { throw 'Group popup missing' }
    $open=$ui.SelectSingleNode('//node[@text="Open"]'); $delete=$ui.SelectSingleNode('//node[@text="Delete"]')
    if (!$open -or !$delete) { throw 'Missing item actions' }
    $ob=[regex]::Matches($open.bounds,'\d+'); $db=[regex]::Matches($delete.bounds,'\d+')
    if ($ob[1].Value -ne $db[1].Value) { throw 'Actions not side by side' }
    Tap $delete
    $ui=Ui
    if (!$ui.SelectSingleNode('//node[contains(@text,"Deletes only this file")]')) { throw 'Delete scope missing' }
    Tap ($ui.SelectSingleNode('//node[@text="Keep"]'))
    Start-Sleep -Seconds 1
    Adb shell screencap -p "/sdcard/aio-gallery-$layout.png" | Out-Null
    Adb pull "/sdcard/aio-gallery-$layout.png" "$env:LOCALAPPDATA\Temp\opencode\aio-gallery-$layout.png" | Out-Null
    Adb shell input keyevent 4 | Out-Null
    if ((Ui).SelectSingleNode('//node[@text="2 saved items"]')) { throw 'Back did not close popup' }
}
Adb shell am force-stop com.rootlyt.aio | Out-Null
Adb shell am start -W -n com.rootlyt.aio/.MainActivity | Out-Null
Tap ((Ui).SelectSingleNode('//node[@content-desc="Gallery, page 2 of 2"]'))
if (!(Ui).SelectSingleNode('//node[@text="Grid" and @checked="true"]')) { throw 'Grid not persisted' }
Tap ((Ui).SelectSingleNode('//node[@text="Show 2 items"]'))
Tap ((Ui).SelectSingleNode('//node[@text="Open"]'))
Start-Sleep 2
Adb shell dumpsys activity activities | Select-String 'image/jpeg|mResumedActivity'
'PASS: persisted batch, list/grid, side-by-side actions, explicit single-file confirmation, originals preserved, image open.'

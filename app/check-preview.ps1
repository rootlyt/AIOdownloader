# Non-destructive regression: adds one small video; never removes saved media.
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
function Adb { $result = & $adb @args; if ($LASTEXITCODE -ne 0) { throw "adb failed: $args" }; return $result }
function State { [xml]((Adb shell run-as com.rootlyt.aio cat shared_prefs/state.xml) -join "`n") }
function Wait-Idle {
    for ($i = 0; $i -lt 90; $i++) {
        Start-Sleep -Seconds 2
        $state = State
        if ($state.SelectSingleNode('//boolean[@name="busy"]').value -eq 'false') { return $state }
    }
    throw 'Operation did not finish within 180 seconds'
}
function Ui { Adb shell uiautomator dump /sdcard/aio-preview-check.xml | Out-Null; [xml]((Adb shell cat /sdcard/aio-preview-check.xml) -join "`n") }
function Tap($node) {
    if (!$node) { throw 'Missing control' }
    $b = [regex]::Matches($node.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    Adb shell input tap ([int](($b[0]+$b[2])/2)) ([int](($b[1]+$b[3])/2)) | Out-Null
}
function Assert-Clean {
    if ((State).SelectSingleNode('//string[@name="preview"]')) { throw 'Persisted preview remains' }
    $ui = Ui
    if (!$ui.SelectSingleNode('//node[@text="Preview appears here"]')) { throw 'Stale title' }
    if ($ui.SelectSingleNode('//node[@content-desc="Media thumbnail"]')) { throw 'Stale thumbnail' }
    if ($ui.SelectSingleNode('//node[@text="Best available video" or starts-with(@text,"Up to ")]')) { throw 'Stale quality' }
    if ($ui.SelectSingleNode('//node[@text="Download selected quality" and @enabled="true"]')) { throw 'Download still enabled' }
}
if ((State).SelectSingleNode('//boolean[@name="busy"]').value -eq 'true') { throw 'Existing operation active; not interrupting' }
$before = @(Adb shell ls /sdcard/Download/AIOdownloader)
Adb shell am force-stop com.rootlyt.aio | Out-Null
Adb shell am start -W -n com.rootlyt.aio/.MainActivity | Out-Null
Assert-Clean
Adb shell am start -W -n com.rootlyt.aio/.MainActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT https://x.com/historyinmemes/status/1790637656616943991 | Out-Null
$state = Wait-Idle
if (!$state.SelectSingleNode('//string[@name="preview"]')) { throw 'Video preview failed' }
# Thumbnail can push download below viewport.
Adb shell input swipe 500 1400 500 650 300 | Out-Null
Tap ((Ui).SelectSingleNode('//node[@text="Download selected quality"]'))
$state = Wait-Idle
if ($state.SelectSingleNode('//string[@name="status"]').InnerText -notlike 'Saved to*') { throw $state.OuterXml }
Adb shell input swipe 500 500 500 1500 300 | Out-Null
Assert-Clean
Adb shell am force-stop com.rootlyt.aio | Out-Null
Adb shell am start -W -n com.rootlyt.aio/.MainActivity | Out-Null
Assert-Clean
$after = @(Adb shell ls /sdcard/Download/AIOdownloader)
foreach ($file in $before) { if ($after -notcontains $file) { throw "Existing file missing: $file" } }
if ($after.Count -le $before.Count) { throw 'No new saved file' }
Tap ((Ui).SelectSingleNode('//node[@content-desc="Gallery, page 2 of 2"]'))
if (!(Ui).SelectSingleNode('//node[starts-with(@text,"Open ")]')) { throw 'Gallery empty' }
'PASS: successful download clears preview/title/thumbnail/quality, cold reopen clean, Gallery and existing files preserved.'

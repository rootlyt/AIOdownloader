# Adds two photos; preserves every existing file. Requires an unlocked debug device.
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
    throw 'Operation timed out'
}
function Ui { Adb shell uiautomator dump /sdcard/aio-photos-check.xml | Out-Null; [xml]((Adb shell cat /sdcard/aio-photos-check.xml) -join "`n") }
function Tap($node) {
    if (!$node) { throw 'Missing control' }
    $b = [regex]::Matches($node.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    Adb shell input tap ([int](($b[0]+$b[2])/2)) ([int](($b[1]+$b[3])/2)) | Out-Null
}
if ((State).SelectSingleNode('//boolean[@name="busy"]').value -eq 'true') { throw 'Existing operation active' }
$before = @(Adb shell ls /sdcard/Download/AIOdownloader)
Adb shell am start -W -n com.rootlyt.aio/.MainActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT https://x.com/infoBMKG/status/2121157219890971057 | Out-Null
$state = Wait-Idle
$preview = $state.SelectSingleNode('//string[@name="preview"]').InnerText | ConvertFrom-Json
if ($preview.photos.Count -ne 2) { throw $state.OuterXml }
Adb shell input swipe 500 1400 500 650 300 | Out-Null
$ui = Ui
if ($ui.SelectSingleNode('//node[@text="Best available video" or @text="Audio only · MP3"]')) { throw 'Video choices on photos' }
Tap ($ui.SelectSingleNode('//node[@text="Download all 2 photos"]'))
$state = Wait-Idle
if ($state.SelectSingleNode('//string[@name="status"]').InnerText -notlike 'Saved 2/2 photos*') { throw $state.OuterXml }
if ($state.SelectSingleNode('//string[@name="preview"]')) { throw 'Stale preview' }
$after = @(Adb shell ls /sdcard/Download/AIOdownloader)
foreach ($file in $before) { if ($after -notcontains $file) { throw "Existing file missing: $file" } }
if ($after.Count -ne $before.Count + 2) { throw 'Expected exactly two new files' }
Tap ((Ui).SelectSingleNode('//node[@content-desc="Gallery, page 2 of 2"]'))
Start-Sleep -Seconds 3
$ui = Ui
Tap ($ui.SelectSingleNode('//node[@text="Show 2 items"]'))
$ui = Ui
$open = $ui.SelectSingleNode('//node[@text="Open" and contains(@content-desc,"X 2121157219890971057")]')
Tap $open
Start-Sleep -Seconds 2
Adb shell dumpsys activity activities | Select-String 'image/jpeg|ResumedActivity|mResumedActivity'
Adb shell input keyevent 4 | Out-Null
Adb shell am start -W -n com.rootlyt.aio/.MainActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT https://x.com/historyinmemes/status/1790637656616943991 | Out-Null
$state = Wait-Idle
$preview = $state.SelectSingleNode('//string[@name="preview"]').InnerText | ConvertFrom-Json
if ($preview.photos -or $preview.heights.Count -lt 1) { throw 'Video fallback regression' }
'PASS: two photos saved, preview cleared, existing files preserved, image open requested, Twitter video preview retained.'

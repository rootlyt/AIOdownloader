# Non-destructive; unlocked device, idle app. Saves screenshots to the approved temp directory.
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
function Adb { $r = & $adb @args; if ($LASTEXITCODE) { throw "adb failed $args" }; $r }
function Ui { Adb shell uiautomator dump /sdcard/aio-floating.xml | Out-Null; [xml]((Adb shell cat /sdcard/aio-floating.xml) -join "`n") }
function Bounds($n) { if (!$n) { throw 'Missing control' }; @([regex]::Matches($n.bounds, '\d+') | ForEach-Object { [int]$_.Value }) }
function Tap($n) { $b=Bounds $n; Adb shell input tap ([int](($b[0]+$b[2])/2)) ([int](($b[1]+$b[3])/2)) | Out-Null }
function Capture($name) {
    Adb shell screencap -p "/sdcard/aio-floating-$name.png" | Out-Null
    Adb pull "/sdcard/aio-floating-$name.png" "$env:LOCALAPPDATA\Temp\opencode\aio-floating-$name.png" | Out-Null
}
Adb shell am start -W -n com.rootlyt.aio/.MainActivity | Out-Null
$dpi=[int]([regex]::Matches(((Adb shell wm density) -join ' '),'\d+') | Select-Object -Last 1).Value
$unit=$dpi/160
foreach ($name in @('Home','Gallery')) {
    Tap ((Ui).SelectSingleNode("//node[starts-with(@content-desc,'$name, page ')]"))
    $ui=Ui
    $targets=@($ui.SelectNodes('//node[contains(@content-desc,", page ")]'))
    if ($targets.Count -ne 2) { throw 'Expected two page targets' }
    $a=Bounds $targets[0]; $b=Bounds $targets[1]
    foreach ($target in $targets) {
        $r=Bounds $target
        if ($r[2]-$r[0] -lt [math]::Floor(48*$unit) -or $r[3]-$r[1] -lt [math]::Floor(48*$unit)) { throw 'Target below 48dp' }
    }
    if ($b[2]-$a[0] -gt 97*$unit -or $a[2] -ne $b[0] -or $a[1] -ne $b[1]) { throw 'Pill not compact 96x48dp' }
    if ($ui.SelectSingleNode("//node[starts-with(@content-desc,'$name, page ')]").selected -ne 'true') { throw 'Selection not exposed' }
    $page=Bounds ($ui.SelectSingleNode('//node[@class="android.widget.ScrollView"]'))
    if ($page[3] -le $a[3] -or [math]::Abs(($a[0]+$b[2])-($page[0]+$page[2])) -gt 2) { throw 'Pill not centered overlay inside viewport' }
    Capture $name.ToLower()
}
# A swipe outside the pill must still reach the pager.
Adb shell input swipe 100 700 900 700 300 | Out-Null
if ((Ui).SelectSingleNode('//node[@content-desc="Home, page 1 of 2"]').selected -ne 'true') { throw 'Swipe navigation failed' }
Tap ((Ui).SelectSingleNode('//node[@content-desc="Gallery, page 2 of 2"]'))
# Scroll from outside the pill, down to the last Gallery row without touching file actions.
for ($i=0; $i -lt 15; $i++) { Adb shell input swipe 80 1700 80 400 100 | Out-Null }
$ui=Ui
$pill=Bounds ($ui.SelectSingleNode('//node[@content-desc="Gallery, page 2 of 2"]'))
$actions=@($ui.SelectNodes('//node[@text="Open" or @text="Delete" or starts-with(@text,"Show ")]'))
if (!$actions.Count) { Write-Warning 'Gallery empty: final-row clearance and mixed-grid geometry require existing files.' }
foreach ($action in $actions) { $r=Bounds $action; if ($r[3] -gt $pill[1]) { throw 'Final row obscured by pill' } }
Capture 'gallery-bottom'
'PASS: 96x48dp centered overlay; two >=48dp targets; selected semantics; tap/swipe navigation.'
if ($actions.Count) { 'PASS: visible final-row actions clear the pill.' }

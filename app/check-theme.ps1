# Non-destructive popup/theme smoke check; requires an existing grouped download.
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
function Adb { $r = & $adb @args; if ($LASTEXITCODE) { throw "adb failed $args" }; $r }
function Ui { Adb shell uiautomator dump /sdcard/aio-theme.xml | Out-Null; [xml]((Adb shell cat /sdcard/aio-theme.xml) -join "`n") }
function Tap($n) { if (!$n) { throw 'Missing control' }; $b=[regex]::Matches($n.bounds,'\d+') | ForEach-Object { [int]$_.Value }; Adb shell input tap ([int](($b[0]+$b[2])/2)) ([int](($b[1]+$b[3])/2)) | Out-Null }
function Shot($name) { Start-Sleep -Seconds 1; Adb shell screencap -p "/sdcard/$name.png" | Out-Null; Adb pull "/sdcard/$name.png" "$env:LOCALAPPDATA\Temp\opencode\$name.png" | Out-Null }
$before=@(Adb shell ls /sdcard/Download/AIOdownloader)
Adb shell am start -W -n com.rootlyt.aio/.MainActivity | Out-Null
Tap ((Ui).SelectSingleNode('//node[@content-desc="Home, page 1 of 2"]'))
Adb shell input swipe 500 500 500 1500 300 | Out-Null
Shot 'aio-brand-home'
Adb shell input swipe 500 1400 500 500 300 | Out-Null
Tap ((Ui).SelectSingleNode('//node[@content-desc="Site support and photo limitations"]'))
$ui=Ui
if (!$ui.SelectSingleNode('//node[contains(@text,"Photos / albums unavailable")]')) { throw 'Missing Facebook limit' }
Shot 'aio-site-support'
Tap ($ui.SelectSingleNode('//node[@text="Close"]'))
Tap ((Ui).SelectSingleNode('//node[@content-desc="Gallery, page 2 of 2"]'))
foreach ($layout in @('List','Grid')) {
 Tap ((Ui).SelectSingleNode("//node[@text='$layout']"))
 Tap ((Ui).SelectSingleNode('//node[starts-with(@text,"Show ") and contains(@text," items")]'))
 $ui=Ui
 if (!$ui.SelectSingleNode('//node[contains(@text," saved items")]') -or !$ui.SelectSingleNode('//node[@text="Close"]')) { throw 'Missing popup' }
 $open=$ui.SelectSingleNode('//node[@text="Open"]'); $delete=$ui.SelectSingleNode('//node[@text="Delete"]')
 if (!$open -or !$delete) { throw 'Missing actions' }
 if (([regex]::Matches($open.bounds,'\d+'))[1].Value -ne ([regex]::Matches($delete.bounds,'\d+'))[1].Value) { throw 'Actions not adjacent' }
 Tap $delete
 $ui=Ui
 if (!$ui.SelectSingleNode('//node[contains(@text,"Deletes only this file")]')) { throw 'Missing scoped confirmation' }
 Tap ($ui.SelectSingleNode('//node[@text="Keep"]'))
 Shot "aio-popup-$layout"
 Adb shell input keyevent 4 | Out-Null
 if ((Ui).SelectSingleNode('//node[contains(@text," saved items")]')) { throw 'Back failed' }
}
$after=@(Adb shell ls /sdcard/Download/AIOdownloader)
if (Compare-Object $before $after) { throw 'Downloads changed' }
'PASS: brand/support, List/Grid popup, adjacent actions, scoped Keep, Back, unchanged downloads.'

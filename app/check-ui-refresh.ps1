# Non-destructive layout regression; device unlocked, app idle.
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
function Adb { $r = & $adb @args; if ($LASTEXITCODE) { throw "adb failed $args" }; $r }
function Ui { Adb shell uiautomator dump /sdcard/aio-refresh.xml | Out-Null; [xml]((Adb shell cat /sdcard/aio-refresh.xml) -join "`n") }
function Bounds($n) { if (!$n) { throw 'Missing control' }; @([regex]::Matches($n.bounds,'\d+') | ForEach-Object { [int]$_.Value }) }
function Tap($n) { $b=Bounds $n; Adb shell input tap ([int](($b[0]+$b[2])/2)) ([int](($b[1]+$b[3])/2)) | Out-Null }
Adb shell am start -W -n com.rootlyt.aio/.MainActivity | Out-Null
Tap ((Ui).SelectSingleNode('//node[@content-desc="Home, page 1 of 2"]'))
Adb shell input swipe 500 500 500 1500 300 | Out-Null
$ui=Ui
$a=Bounds ($ui.SelectSingleNode('//node[@text="Paste & preview"]'))
$b=Bounds ($ui.SelectSingleNode('//node[@text="Update Youtube Site Extractor"]'))
if ($a[1] -ne $b[1] -or $a[3] -ne $b[3] -or $a[2] -gt $b[0]) { throw 'Action row misaligned or overlapping' }
$dpi=[int]([regex]::Matches(((Adb shell wm density) -join ' '),'\d+') | Select-Object -Last 1).Value
foreach ($label in @('Paste & preview','Update Youtube Site Extractor','About','Supported Site')) {
 $b=Bounds ($ui.SelectSingleNode("//node[@text='$label']"))
 if ($b[3]-$b[1] -lt 48*$dpi/160) { throw "$label below 48dp" }
}
Start-Sleep -Seconds 1
Adb shell screencap -p /sdcard/aio-refresh-home.png | Out-Null
Adb pull /sdcard/aio-refresh-home.png "$env:LOCALAPPDATA\Temp\opencode\aio-refresh-home.png" | Out-Null
'PASS: aligned full-label Home action row; action/footer targets >=48dp.'

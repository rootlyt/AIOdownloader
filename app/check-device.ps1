# Non-destructive UI smoke check; install debug APK, unlock device first.
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb shell am start -n com.rootlyt.aio/.MainActivity
function Get-Ui {
    & $adb shell uiautomator dump /sdcard/aio-check.xml | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'UI dump failed' }
    [xml]($(& $adb shell cat /sdcard/aio-check.xml) -join "`n")
}
function Tap-Node($node) {
    if (!$node) { throw 'Missing UI control' }
    $b = [regex]::Matches($node.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    & $adb shell input tap ([int](($b[0]+$b[2])/2)) ([int](($b[1]+$b[3])/2))
}
$ui = Get-Ui
Tap-Node ($ui.SelectSingleNode('//node[@content-desc="Home, page 1 of 2"]'))
$size = ((& $adb shell wm size) -join ' ')
$dimensions = [regex]::Matches($size, '\d+x\d+') | Select-Object -Last 1
$width, $height = $dimensions.Value.Split('x') | ForEach-Object { [int]$_ }
function Swipe-Page($forward) {
    $start = [int]($width * 0.85); $end = [int]($width * 0.15); $y = [int]($height * 0.4)
    if ($forward) { & $adb shell input swipe $start $y $end $y 350 }
    else { & $adb shell input swipe $end $y $start $y 350 }
}
Swipe-Page $true
$ui = Get-Ui
if (!$ui.SelectSingleNode('//node[@text="Refresh saved files"]')) { throw 'Downloads page missing' }
if ($ui.SelectSingleNode('//node[@text="Paste & preview"]')) { throw 'Pages not separated' }
# Only inspect the dialog, never confirm deletion.
$delete = $ui.SelectSingleNode('//node[@text="Delete"]')
if ($delete) {
    Tap-Node $delete
    $ui = Get-Ui
    if (!$ui.SelectSingleNode('//node[@text="Delete downloaded file?"]')) { throw 'Deletion confirmation missing' }
    Tap-Node ($ui.SelectSingleNode('//node[@text="Keep"]'))
}
$ui = Get-Ui
if (!$ui.SelectSingleNode('//node[@content-desc="Gallery, page 2 of 2" and @selected="true"]')) { throw 'Gallery indicator not selected' }
Swipe-Page $false
$ui = Get-Ui
$density = ((& $adb shell wm density) -join ' ')
$dpi = [int]([regex]::Matches($density, '\d+') | Select-Object -Last 1).Value
if (!$ui.SelectSingleNode('//node[@content-desc="Home, page 1 of 2" and @selected="true"]')) { throw 'Home indicator not selected' }
foreach ($label in @('Home, page 1 of 2', 'Gallery, page 2 of 2')) {
    $node = $ui.SelectSingleNode("//node[@content-desc='$label']")
    $b = [regex]::Matches($node.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    if (($b[3]-$b[1]) -lt (48*$dpi/160)) { throw "$label target smaller than 48dp" }
}
& $adb shell input swipe ([int]($width/2)) ([int]($height*0.8)) ([int]($width/2)) ([int]($height*0.25)) 400
$ui = Get-Ui
if ($ui.SelectSingleNode('//node[contains(@text,"yt-dlp")]')) { throw 'Extractor version leaked into Home status' }
Tap-Node ($ui.SelectSingleNode('//node[@text="About"]'))
$ui = Get-Ui
foreach ($text in @('AIOdownloader', 'Made by rootlyt', 'Versions')) {
    if (!$ui.SelectSingleNode("//node[@text='$text']")) { throw "About missing $text" }
}
if (!$ui.SelectSingleNode('//node[@content-desc="Author rootlyt on GitHub" and @clickable="true"]')) { throw 'Author link missing' }
if (!$ui.SelectSingleNode('//node[starts-with(@text,"App ")]')) { throw 'App version missing' }
if (!$ui.SelectSingleNode('//node[starts-with(@text,"yt-dlp ")]')) { throw 'Extractor version missing' }
& $adb shell input swipe ([int]($width/2)) ([int]($height*0.75)) ([int]($width/2)) ([int]($height*0.3)) 400
$ui = Get-Ui
if (!$ui.SelectSingleNode('//node[@text="Source"]') -or !$ui.SelectSingleNode('//node[@text="License"]')) { throw 'Upstream links missing' }
Tap-Node ($ui.SelectSingleNode('//node[@text="Close"]'))
$services = (& $adb shell dumpsys activity services com.rootlyt.aio) -join "`n"
if ($services -match 'isForeground=true') { throw 'Unexpected foreground operation; run this smoke check while idle' }
$notifications = (& $adb shell dumpsys notification --noredact) -join "`n"
if ($notifications -match 'NotificationRecord\([^\r\n]*pkg=com.rootlyt.aio[^\r\n]* id=1 ') { throw 'Stale progress notification' }
Write-Output 'Navigation, 48dp targets, About, idle notification cleanup and non-destructive confirmation passed.'

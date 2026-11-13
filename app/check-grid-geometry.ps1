# Non-destructive: open Gallery/Grid first; scroll until a mixed group/single row is fully visible.
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
function Adb { $r = & $adb @args; if ($LASTEXITCODE) { throw "adb failed $args" }; $r }
function Bounds($n) { @([regex]::Matches($n.bounds, '\d+') | ForEach-Object { [int]$_.Value }) }
Adb shell uiautomator dump /sdcard/aio-geometry.xml | Out-Null
[xml]$ui = (Adb shell cat /sdcard/aio-geometry.xml) -join "`n"
$gallery = $ui.SelectSingleNode('//node[@content-desc="Gallery, page 2 of 2"]')
if (!$gallery) { throw 'Missing page target' }
$gb = Bounds $gallery
$densityText = (Adb shell wm density) -join ' '
$density = [int]([regex]::Matches($densityText, '\d+') | Select-Object -Last 1).Value
$minimum = [math]::Floor(48 * $density / 160)
foreach ($target in $ui.SelectNodes('//node[contains(@content-desc,"page ")]')) {
    $b = Bounds $target
    if ($b[2]-$b[0] -lt $minimum -or $b[3]-$b[1] -lt $minimum) { throw 'Page target smaller than 48dp' }
}
$checked = 0
foreach ($row in $ui.SelectNodes('//node[@class="android.widget.LinearLayout"]')) {
    $cards = @($row.SelectNodes('./node[@class="androidx.cardview.widget.CardView"]'))
    if ($cards.Count -ne 2 -or !$row.SelectSingleNode('.//node[@text="Open"]') -or !$row.SelectSingleNode('.//node[starts-with(@text,"Show ")]')) { continue }
    $a = Bounds $cards[0]; $b = Bounds $cards[1]
    if ($a[1] -lt 100 -or $a[3] -ge $gb[1]) { continue }
    if ($a[1] -ne $b[1] -or $a[3] -ne $b[3] -or $a[2]-$a[0] -ne $b[2]-$b[0]) { throw 'Unequal mixed card geometry' }
    $show = Bounds ($row.SelectSingleNode('.//node[starts-with(@text,"Show ")]'))
    $open = Bounds ($row.SelectSingleNode('.//node[@text="Open"]'))
    if ($show[1] -ne $open[1] -or $show[3] -ne $open[3] -or $open[3]-$open[1] -lt $minimum) { throw 'Unequal/undersized action rows' }
    $checked++
}
if (!$checked) { throw 'Scroll to a fully visible mixed group/single row first' }
"PASS: $checked mixed rows equal; action rows aligned >=48dp; page targets >=48dp."

# Adds one source-provided SoundCloud preview MP3; deletes nothing.
$ErrorActionPreference='Stop'
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
function Adb { $r=& $adb @args; if ($LASTEXITCODE) { throw "adb failed $args" }; $r }
function Ui { Adb shell uiautomator dump /sdcard/aio-audio.xml | Out-Null; [xml]((Adb shell cat /sdcard/aio-audio.xml) -join "`n") }
function Tap($n) { if (!$n) { throw 'Missing control' }; $b=[regex]::Matches($n.bounds,'\d+') | ForEach-Object { [int]$_.Value }; Adb shell input tap ([int](($b[0]+$b[2])/2)) ([int](($b[1]+$b[3])/2)) | Out-Null }
function Idle { for ($i=0;$i -lt 120;$i++) { Start-Sleep 2; [xml]$s=(Adb shell run-as com.rootlyt.aio cat shared_prefs/state.xml)-join "`n"; if ($s.SelectSingleNode('//boolean[@name="busy"]').value -eq 'false') { return $s } }; throw 'Timed out' }
$before = @(Adb shell ls /sdcard/Download/AIOdownloader)
Adb shell am start -W -n com.rootlyt.aio/.MainActivity -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT https://soundcloud.com/imaginedragons/demons | Out-Null
$s=Idle
if (($s.SelectSingleNode('//string[@name="preview"]').InnerText | ConvertFrom-Json).title -ne 'Demons') { throw $s.OuterXml }
Adb shell input swipe 500 1500 500 600 300 | Out-Null
Tap ((Ui).SelectSingleNode('//node[@text="Best available video"]'))
Tap ((Ui).SelectSingleNode('//node[starts-with(@text,"Audio only")]'))
Tap ((Ui).SelectSingleNode('//node[@text="Download selected quality"]'))
$s=Idle
if ($s.SelectSingleNode('//string[@name="status"]').InnerText -notlike 'Saved to*') { throw $s.OuterXml }
$after = @(Adb shell ls /sdcard/Download/AIOdownloader)
foreach ($file in $before) { if ($after -notcontains $file) { throw "Existing file missing: $file" } }
$new = @($after | Where-Object { $before -notcontains $_ })
if ($new.Count -ne 1 -or $new[0] -notlike 'Demons*.mp3') { throw 'Expected one new MP3' }
Adb pull "/sdcard/Download/AIOdownloader/$($new[0])" "$env:LOCALAPPDATA\Temp\opencode\aio-Demons.mp3"
'PASS: SoundCloud MP3 saved. Run ffprobe on pulled file to verify title and attached_pic.'

# Tiny adb UI driver for the emulator functional walkthrough (task-11).
# Usage (from repo root):
#   pwsh -NoProfile -File docs\verification\2026-10-24-emulator-functional-coverage\_tools\ui.ps1 -Cmd dump  -Name 01-welcome
#   pwsh -NoProfile -File ...\ui.ps1 -Cmd tap   -Text "创建我的孕期档案"
#   pwsh -NoProfile -File ...\ui.ps1 -Cmd tap   -Text "保存" -Contains
#   pwsh -NoProfile -File ...\ui.ps1 -Cmd input -Text "Synthetic"
#   pwsh -NoProfile -File ...\ui.ps1 -Cmd key   -Key BACK
#   pwsh -NoProfile -File ...\ui.ps1 -Cmd swipe -X1 540 -Y1 1800 -X2 540 -Y2 900
#   pwsh -NoProfile -File ...\ui.ps1 -Cmd shell -Text "dumpsys alarm | grep -A3 cn.yunji.journal"
param(
    [string]$Cmd = 'dump',
    [string]$Text = '',
    [string]$Name = 'last',
    [string]$Key = 'BACK',
    [switch]$Contains,
    [int]$X1 = 540, [int]$Y1 = 1800, [int]$X2 = 540, [int]$Y2 = 900, [int]$Duration = 400
)
$ErrorActionPreference = 'Continue'
$d = 'emulator-5556'
$out = 'D:\Devtools\Code\CodexWorkspace\PregnancyJournal\docs\verification\2026-10-24-emulator-functional-coverage'
$flows = Join-Path $out 'flows'

function Get-UiXml {
    adb -s $d shell "uiautomator dump /sdcard/ui.xml" 2>&1 | Out-Null
    $local = Join-Path $flows 'last.xml'
    adb -s $d pull /sdcard/ui.xml $local 2>&1 | Out-Null
    return [System.IO.File]::ReadAllText($local, [System.Text.Encoding]::UTF8)
}

function Get-Nodes([string]$xml) {
    $nodes = @()
    foreach ($m in [regex]::Matches($xml, '<node[^>]*?/?>')) {
        $s = $m.Value
        $get = {
            param($k)
            if ($s -match "$k=`"([^`"]*)`"") { return $Matches[1] } else { return '' }
        }
        $nodes += [pscustomobject]@{
            text    = (& $get 'text')
            desc    = (& $get 'content-desc')
            cls     = (& $get 'class')
            bounds  = (& $get 'bounds')
            clickable = (& $get 'clickable')
            scrollable = (& $get 'scrollable')
        }
    }
    return $nodes
}

function Get-Center([string]$bounds) {
    if ($bounds -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
        $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
        $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
        return @([int]$x, [int]$y)
    }
    return @(0, 0)
}

function Find-Node($nodes, [string]$needle) {
    $exact = $nodes | Where-Object { $_.text -eq $needle -or $_.desc -eq $needle } | Select-Object -First 1
    if ($exact) { return $exact }
    if ($Contains) {
        return ($nodes | Where-Object { $_.text -like "*$needle*" -or $_.desc -like "*$needle*" } | Select-Object -First 1)
    }
    return $null
}

switch ($Cmd) {
    'dump' {
        $xml = Get-UiXml
        Copy-Item (Join-Path $flows 'last.xml') (Join-Path $flows "$Name.xml") -Force
        $nodes = Get-Nodes $xml
        foreach ($n in $nodes) {
            if ($n.text -or $n.desc -or $n.scrollable -eq 'true') {
                'text={0} | desc={1} | cls={2} | bounds={3} | click={4} | scroll={5}' -f $n.text, $n.desc, $n.cls, $n.bounds, $n.clickable, $n.scrollable
            }
        }
        Write-Host "saved $Name.xml"
    }
    'tap' {
        $xml = Get-UiXml
        $nodes = Get-Nodes $xml
        $node = Find-Node $nodes $Text
        if (-not $node) {
            Write-Host "NODE NOT FOUND: '$Text'"
            $nodes | Where-Object { $_.text -or $_.desc } | ForEach-Object { '  ' + $_.text + ' | ' + $_.desc + ' | ' + $_.bounds }
            exit 2
        }
        $c = Get-Center $node.bounds
        adb -s $d shell "input tap $($c[0]) $($c[1])" | Out-Null
        Write-Host "tapped '$($node.text)$($node.desc)' at ($($c[0]),$($c[1]))"
    }
    'input' {
        adb -s $d shell "input text '$Text'" | Out-Null
        Write-Host "input '$Text'"
    }
    'key' {
        adb -s $d shell "input keyevent KEYCODE_$Key" | Out-Null
        Write-Host "key $Key"
    }
    'swipe' {
        adb -s $d shell "input swipe $X1 $Y1 $X2 $Y2 $Duration" | Out-Null
        Write-Host "swipe $X1,$Y1 -> $X2,$Y2"
    }
    'shell' {
        adb -s $d shell $Text 2>&1 | ForEach-Object { $_ }
    }
    'clear' {
        # clear the focused text field: select all then delete (API 33+ keycombination)
        adb -s $d shell "input keycombination 113 29" | Out-Null   # CTRL_LEFT + A
        adb -s $d shell "input keyevent KEYCODE_DEL" | Out-Null
        Write-Host 'cleared focused field'
    }
    'foreground' {
        adb -s $d shell "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'" 2>&1
    }
}

# 将 `adb shell am instrument -w -r` 的原始输出映射为 JUnit XML。
# 只做 status code 映射（1=开始、0=通过、-1=错误、-2=失败、-3=忽略、-4=前置条件跳过），
# 不重新判定结果；`am instrument` 本身不输出 XML，原始日志仍作为第一证据保留。
# 用法：
#   pwsh -File make-junit-xml.ps1 -Log full-api36-round1.log -Out full-api36-round1.xml
param(
    [Parameter(Mandatory = $true)][string]$Log,
    [Parameter(Mandatory = $true)][string]$Out,
    [string]$Suite = 'cn.yunji.journal.test'
)

$lines = Get-Content -LiteralPath $Log
$cases = New-Object System.Collections.Generic.List[object]
$currentClass = $null
$currentTest = $null
$lastStack = $null
$collectingStack = $false
$stackBuffer = New-Object System.Collections.Generic.List[string]

function Add-Case([string]$class, [string]$test, [int]$code, [string]$stack) {
    $cases.Add([pscustomobject]@{
        Class = if ($class) { $class } else { 'unknown' }
        Test  = if ($test) { $test } else { 'unknown' }
        Code  = $code
        Stack = $stack
    })
}

foreach ($line in $lines) {
    if ($collectingStack) {
        if ($line -match '^INSTRUMENTATION_(STATUS|RESULT|CODE)') {
            $collectingStack = $false
        }
        else {
            $stackBuffer.Add($line)
            continue
        }
    }
    if ($line -match '^INSTRUMENTATION_STATUS: class=(.*)$') { $currentClass = $Matches[1].Trim(); continue }
    if ($line -match '^INSTRUMENTATION_STATUS: test=(.*)$') { $currentTest = $Matches[1].Trim(); continue }
    if ($line -match '^INSTRUMENTATION_STATUS: stack=(.*)$') {
        $stackBuffer.Clear()
        $stackBuffer.Add($Matches[1])
        $collectingStack = $true
        continue
    }
    if ($line -match '^INSTRUMENTATION_STATUS_CODE: (-?\d+)') {
        $code = [int]$Matches[1]
        if ($code -eq 1) {
            # 开始：先占位，结束码到来时补齐结论
            $lastStack = $null
            Add-Case $currentClass $currentTest 1 $null
        }
        else {
            # 该测试的开始占位是列表中最后一条同 class/test 的记录
            $existing = $cases | Where-Object { $_.Class -eq $currentClass -and $_.Test -eq $currentTest } | Select-Object -Last 1
            if ($null -ne $existing) {
                $existing.Code = $code
                $existing.Stack = if ($stackBuffer.Count -gt 0) { ($stackBuffer -join "`n") } else { $lastStack }
            }
            else {
                Add-Case $currentClass $currentTest $code ($stackBuffer -join "`n")
            }
        }
        $lastStack = if ($stackBuffer.Count -gt 0) { ($stackBuffer -join "`n") } else { $lastStack }
        continue
    }
}

$esc = {
    param([string]$value)
    if ($null -eq $value) { return '' }
    $clean = ($value -replace '[^\x09\x0A\x0D\x20-\uD7FF\uE000-\uFFFD]', '')
    return [System.Security.SecurityElement]::Escape($clean)
}

$failed = @($cases | Where-Object { $_.Code -eq -2 })
$errors = @($cases | Where-Object { $_.Code -eq -1 })
$skipped = @($cases | Where-Object { $_.Code -eq -3 -or $_.Code -eq -4 })

$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine('<?xml version="1.0" encoding="UTF-8"?>')
[void]$sb.AppendLine(('<!-- generated from raw `am instrument -w -r` output: {0} -->' -f (Split-Path $Log -Leaf)))
[void]$sb.AppendLine(('<testsuite name="{0}" tests="{1}" failures="{2}" errors="{3}" skipped="{4}">' -f
        (& $esc $Suite), $cases.Count, $failed.Count, $errors.Count, $skipped.Count))
foreach ($case in $cases) {
    $class = & $esc $case.Class
    $test = & $esc $case.Test
    if ($case.Code -eq -2) {
        $message = if ($case.Stack) { (& $esc (($case.Stack -split "`n")[0])) } else { '' }
        [void]$sb.AppendLine(('  <testcase classname="{0}" name="{1}"><failure message="{2}">{3}</failure></testcase>' -f
                $class, $test, $message, (& $esc $case.Stack)))
    }
    elseif ($case.Code -eq -1) {
        $message = if ($case.Stack) { (& $esc (($case.Stack -split "`n")[0])) } else { '' }
        [void]$sb.AppendLine(('  <testcase classname="{0}" name="{1}"><error message="{2}">{3}</error></testcase>' -f
                $class, $test, $message, (& $esc $case.Stack)))
    }
    elseif ($case.Code -eq -3 -or $case.Code -eq -4) {
        [void]$sb.AppendLine(('  <testcase classname="{0}" name="{1}"><skipped/></testcase>' -f $class, $test))
    }
    else {
        [void]$sb.AppendLine(('  <testcase classname="{0}" name="{1}"/>' -f $class, $test))
    }
}
[void]$sb.AppendLine('</testsuite>')
$parent = Split-Path -Parent $Out
if ([string]::IsNullOrEmpty($parent)) { $parent = (Get-Location).Path }
$target = Join-Path $parent (Split-Path -Leaf $Out)
[System.IO.File]::WriteAllText($target, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))

Write-Output ("cases={0} failures={1} errors={2} skipped={3}" -f $cases.Count, $failed.Count, $errors.Count, $skipped.Count)

param(
    [Parameter(Mandatory=$true)][ValidateSet('new','status','advance')][string]$Action,
    [Parameter(Mandatory=$true)][ValidatePattern('^TASK-[0-9]{3,}$')][string]$Id,
    [string]$Title,
    [ValidateSet('ANALYSIS','PLANNING','CODING','INVARIANT_VALIDATION','CODE_REVIEW','TESTING','DONE')][string]$To,
    [string]$Reason
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$dir = Join-Path (Join-Path $root 'tasks') $Id
$statePath = Join-Path $dir 'state.json'
$stages = @('ANALYSIS','PLANNING','CODING','INVARIANT_VALIDATION','CODE_REVIEW','TESTING','DONE')
$reports = @('analysis.md','specs.md','implementation.md','invariants.md','review.md','tests.md')
function Save-State($value) {
    $temp = Join-Path $dir 'state.tmp'
    $value | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $temp -Encoding UTF8
    Move-Item -LiteralPath $temp -Destination $statePath -Force
}
if ($Action -eq 'new') {
    if ([string]::IsNullOrWhiteSpace($Title)) { throw 'Title is required.' }
    if (Test-Path -LiteralPath $dir) { throw 'Task already exists.' }
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    $now = [DateTime]::UtcNow.ToString('o')
    Save-State ([ordered]@{ id=$Id; title=$Title; stage='ANALYSIS'; updatedAt=$now; history=@(@{ from=$null; to='ANALYSIS'; at=$now; reason='Created' }) })
    foreach ($report in $reports) {
        Set-Content -LiteralPath (Join-Path $dir $report) -Encoding UTF8 -Value "Outcome: BLOCKED`n`n# $report`n`nNot started."
    }
    Set-Content -LiteralPath (Join-Path $dir 'handoff.md') -Encoding UTF8 -Value "# $Id`n`nNext: analysis and user stories.`nBlockers: clarify requirements."
} elseif (-not (Test-Path -LiteralPath $statePath)) {
    throw 'Task does not exist.'
}
$state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
if ($Action -eq 'advance') {
    if (-not $To -or [string]::IsNullOrWhiteSpace($Reason)) { throw 'To and Reason are required.' }
    $index = [array]::IndexOf($stages, [string]$state.stage)
    if ($index -lt 0) { throw 'Unknown saved stage.' }
    $next = [array]::IndexOf($stages, $To)
    $forward = $next -eq ($index + 1)
    $rollback = ($To -eq 'ANALYSIS' -and $index -gt 0) -or ($To -eq 'CODING' -and $index -gt 2)
    if (-not ($forward -or $rollback)) { throw "Invalid transition: $($state.stage) -> $To" }
    if ($forward) {
        $reportPath = Join-Path $dir $reports[$index]
        $reportText = Get-Content -LiteralPath $reportPath -Raw
        if ($reportText -notmatch '\AOutcome: PASS\r?\n' -or $reportText.Trim().Length -lt 40) {
            throw "Complete $($reports[$index]) with Outcome: PASS and evidence first."
        }
    } else {
        $archive = Join-Path $dir ('archive/' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffffff'))
        New-Item -ItemType Directory -Path $archive -Force | Out-Null
        for ($i=$next; $i -lt $reports.Count; $i++) {
            $path = Join-Path $dir $reports[$i]
            Copy-Item -LiteralPath $path -Destination $archive
            Set-Content -LiteralPath $path -Encoding UTF8 -Value "Outcome: BLOCKED`n`nInvalidated by rollback: $Reason"
        }
    }
    $now = [DateTime]::UtcNow.ToString('o')
    $state.history = @($state.history) + @(@{ from=$state.stage; to=$To; at=$now; reason=$Reason })
    $state.stage = $To
    $state.updatedAt = $now
    Save-State $state
}
$state | ConvertTo-Json -Depth 10

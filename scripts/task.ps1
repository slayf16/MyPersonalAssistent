param(
    [Parameter(Mandatory=$true)][ValidateSet('new','status','advance','approve-plan','allow-cycles','user-fixes','accept')][string]$Action,
    [Parameter(Mandatory=$true)][ValidatePattern('^TASK-[0-9]{3,}$')][string]$Id,
    [string]$Title,
    [ValidateSet('ANALYSIS','PLANNING','CODING','INVARIANT_VALIDATION','CODE_REVIEW','TESTING','DONE')][string]$To,
    [string]$Reason,
    [ValidateRange(1,100)][int]$AdditionalCycles = 1
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
# Migrate legacy tasks without inventing a past approval or resetting attempts.
if (-not $state.PSObject.Properties['cyclesUsed']) {
    $used = @($state.history | Where-Object { $_.to -eq 'CODING' }).Count
    $state | Add-Member cyclesUsed $used
    $state | Add-Member cycleLimit 2
    $state | Add-Member approvedPlanHash $null
    $state | Add-Member waitingFor $null
}
function Record-Event([string]$reason) {
    $now = [DateTime]::UtcNow.ToString('o')
    $state.updatedAt = $now
    $state.history = @($state.history) + @(@{ action=$Action; at=$now; reason=$reason })
    Save-State $state
}
function Require-Plan {
    $hash = (Get-FileHash -LiteralPath (Join-Path $dir 'specs.md') -Algorithm SHA256).Hash
    if ($state.approvedPlanHash -ne $hash) {
        $state.waitingFor = 'PLAN_APPROVAL'
        Save-State $state
        throw 'User approval of the current specs.md is required.'
    }
}
if ($Action -in @('approve-plan','allow-cycles','user-fixes','accept')) {
    if ([string]::IsNullOrWhiteSpace($Reason)) { throw 'Reason must quote the explicit user decision and identify the message/date.' }
    if ($Action -eq 'approve-plan') {
        if ($state.stage -ne 'PLANNING') { throw 'Plan approval requires PLANNING.' }
        $specs = Get-Content -LiteralPath (Join-Path $dir 'specs.md') -Raw
        if ($specs -notmatch '\AOutcome: PASS\r?\n' -or $specs.Trim().Length -lt 40) { throw 'Complete specs.md first.' }
        $state.approvedPlanHash = (Get-FileHash -LiteralPath (Join-Path $dir 'specs.md')).Hash
        $state.waitingFor = $null
    } else {
        if ($state.waitingFor -ne 'CYCLE_DECISION') { throw 'This decision requires an exhausted cycle budget.' }
        if ($Action -eq 'allow-cycles') {
            $state.cycleLimit += $AdditionalCycles
            $state.waitingFor = $null
        } elseif ($Action -eq 'accept') {
            $state.stage = 'ACCEPTED_WITH_ISSUES'
            $state.waitingFor = $null
        } else {
            Require-Plan
            # Keep old results for audit; user fixes still require fresh validation.
            $archive = Join-Path $dir ('archive/' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffffff'))
            New-Item -ItemType Directory -Path $archive -Force | Out-Null
            foreach ($report in @('invariants.md','review.md','tests.md')) {
                $path = Join-Path $dir $report
                Copy-Item -LiteralPath $path -Destination $archive
                Set-Content -LiteralPath $path -Encoding UTF8 -Value "Outcome: BLOCKED`n`nUser fixes require fresh verification."
            }
            $state.stage = 'INVARIANT_VALIDATION'
            $state.waitingFor = $null
        }
    }
    Record-Event $Reason
}
if ($Action -eq 'advance') {
    if (-not $To -or [string]::IsNullOrWhiteSpace($Reason)) { throw 'To and Reason are required.' }
    $index = [array]::IndexOf($stages, [string]$state.stage)
    if ($index -lt 0) { throw 'Unknown saved stage.' }
    $next = [array]::IndexOf($stages, $To)
    $forward = $next -eq ($index + 1)
    $rollback = ($To -eq 'ANALYSIS' -and $index -gt 0) -or ($To -eq 'CODING' -and $index -gt 2)
    if (-not ($forward -or $rollback)) { throw "Invalid transition: $($state.stage) -> $To" }
    if ($To -eq 'CODING') {
        Require-Plan
        if ($state.cyclesUsed -ge $state.cycleLimit) {
            $state.waitingFor = 'CYCLE_DECISION'
            Save-State $state
            throw 'Cycle limit reached. Ask the user: more cycles, manual fixes, or accept with issues.'
        }
    }
    if ($forward -and $index -ge 2) { Require-Plan }
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
    if ($To -eq 'CODING') { $state.cyclesUsed++ }
    if ($To -eq 'ANALYSIS') { $state.approvedPlanHash = $null }
    $state.waitingFor = if ($To -eq 'PLANNING') { 'PLAN_APPROVAL' } else { $null }
    $state.updatedAt = $now
    Save-State $state
}
if ($Action -eq 'new') { Save-State $state }
$state | ConvertTo-Json -Depth 10

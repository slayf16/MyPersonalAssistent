$ErrorActionPreference = 'Stop'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('mypersonalassistent-workflow-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $testRoot 'scripts') -Force | Out-Null
$runner = Join-Path $testRoot 'scripts/task.ps1'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'task.ps1') -Destination $runner
$script:taskId = 'TASK-900'
function Run($action, $to = '') {
    $arguments = @{ Action=$action; Id=$script:taskId; Reason='Synthetic user decision for isolated test' }
    if ($to) { $arguments.To = $to }
    & $runner @arguments | Out-Null
}
function State { Get-Content -LiteralPath (Join-Path $testRoot "tasks/$script:taskId/state.json") -Raw | ConvertFrom-Json }
function Report($name) {
    Set-Content -LiteralPath (Join-Path $testRoot "tasks/$script:taskId/$name.md") -Encoding UTF8 -Value "Outcome: PASS`n`nSynthetic evidence for isolated workflow testing."
}
function Assert($condition, $message) { if (-not $condition) { throw $message } }
function Reject([scriptblock]$operation, $pattern) {
    try { & $operation } catch {
        Assert ($_.Exception.Message -match $pattern) "Unexpected rejection: $_"
        return
    }
    throw 'Expected rejection, but operation succeeded.'
}
function New-TestTask {
    & $runner -Action new -Id $script:taskId -Title 'Isolated workflow test' | Out-Null
    Report 'analysis'
    Run 'advance' 'PLANNING'
    Report 'specs'
}
function To-Testing {
    Report 'implementation'; Run 'advance' 'INVARIANT_VALIDATION'
    Report 'invariants'; Run 'advance' 'CODE_REVIEW'
    Report 'review'; Run 'advance' 'TESTING'
}
New-TestTask
Reject { Run 'advance' 'CODING' } 'approval'
Run 'approve-plan'
Add-Content -LiteralPath (Join-Path $testRoot 'tasks/TASK-900/specs.md') -Value 'Plan changed.'
Reject { Run 'advance' 'CODING' } 'approval'
Run 'approve-plan'; Run 'advance' 'CODING'
Assert ((State).cyclesUsed -eq 1) 'First cycle not counted.'
To-Testing
Run 'advance' 'CODING'
Assert ((State).cyclesUsed -eq 2) 'Second cycle not counted.'
To-Testing
Reject { Run 'advance' 'CODING' } 'Cycle limit'
Assert ((State).waitingFor -eq 'CYCLE_DECISION') 'Missing decision gate.'
Run 'status'
Assert ((State).cyclesUsed -eq 2) 'Reload reset cycle count.'
Run 'user-fixes'
Assert ((State).stage -eq 'INVARIANT_VALIDATION') 'Manual fixes must be verified.'
Reject { Run 'advance' 'CODE_REVIEW' } 'Complete invariants'
Report 'invariants'; Run 'advance' 'CODE_REVIEW'
Reject { Run 'advance' 'CODING' } 'Cycle limit'
Run 'allow-cycles'
Assert ((State).cycleLimit -eq 3) 'Extension not recorded.'
Run 'advance' 'CODING'; To-Testing
Reject { Run 'advance' 'CODING' } 'Cycle limit'
Run 'accept'
Assert ((State).stage -eq 'ACCEPTED_WITH_ISSUES') 'Acceptance must not claim DONE.'
$script:taskId = 'TASK-901'
New-TestTask; Run 'approve-plan'; Run 'advance' 'CODING'; To-Testing
Report 'tests'; Run 'advance' 'DONE'
Assert ((State).cyclesUsed -eq 1) 'Success should not require a second cycle.'
Run 'advance' 'ANALYSIS'
Assert ((State).cyclesUsed -eq 1 -and $null -eq (State).approvedPlanHash) 'Reanalysis must revoke approval without resetting cycles.'
Report 'analysis'; Run 'advance' 'PLANNING'; Report 'specs'
Reject { Run 'advance' 'CODING' } 'approval'
# A legacy task preserves historical attempts and receives no fabricated approval.
$legacyPath = Join-Path $testRoot 'tasks/TASK-901/state.json'
$legacy = State
foreach ($field in @('cyclesUsed','cycleLimit','approvedPlanHash','waitingFor')) { $legacy.PSObject.Properties.Remove($field) }
$legacy | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $legacyPath -Encoding UTF8
$migrated = & $runner -Action status -Id $script:taskId | ConvertFrom-Json
Assert ($migrated.cyclesUsed -eq 1 -and $null -eq $migrated.approvedPlanHash) 'Legacy migration failed.'
Write-Output 'PASS: approval, changed plan, two cycles, persistence, manual fixes, extension, acceptance with issues, first-cycle success, reanalysis, legacy migration.'
Write-Output "Isolated artifacts: $testRoot"

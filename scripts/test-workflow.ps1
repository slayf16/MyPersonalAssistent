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
    Set-Content -LiteralPath (Join-Path $testRoot "tasks/$script:taskId/$name.md") -Encoding UTF8 -Value "Outcome: PASS`n`nSynthetic evidence for isolated workflow testing: $name."
}
function Assert($condition, $message) { if (-not $condition) { throw $message } }
function Reject([scriptblock]$operation, $pattern) {
    try { & $operation } catch {
        Assert ($_.Exception.Message -match $pattern) "Unexpected rejection for [$pattern]: $_"
        return
    }
    throw 'Expected rejection, but operation succeeded.'
}
function Dispatch($stage, $role, $agent, $model, $reasoning, $scope) {
    $result = & $runner -Action dispatch -Id $script:taskId -Stage $stage -Role $role -AgentId $agent -Model $model -ReasoningEffort $reasoning -Scope $scope | ConvertFrom-Json
    @($result.provenance.dispatches | Select-Object -Last 1)[0].id
}
function Complete($id) { & $runner -Action complete-stage -Id $script:taskId -DispatchId $id | Out-Null }
function Do-Stage($stage, $role, $agent, $model, $reasoning, $artifact) {
    $id = Dispatch $stage $role $agent $model $reasoning "Synthetic $stage/$role"
    Report $artifact
    Complete $id
}
function State-Text { Get-Content -LiteralPath (Join-Path $testRoot "tasks/$script:taskId/state.json") -Raw }
function Write-State($value) { $value | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $testRoot "tasks/$script:taskId/state.json") -Encoding UTF8 }
function Write-StateText($text) { Set-Content -LiteralPath (Join-Path $testRoot "tasks/$script:taskId/state.json") -Encoding UTF8 -NoNewline -Value $text }
function Reject-Unchanged([scriptblock]$operation, $pattern) {
    $before = State-Text
    Reject $operation $pattern
    Assert ((State-Text) -eq $before) "Rejected operation [$pattern] mutated state."
}
function Require-InvalidValidate($expected) {
    $before = State-Text
    $result = & $runner -Action validate -Id $script:taskId | ConvertFrom-Json
    Assert (-not $result.valid -and $result.diagnostics.Count -gt 0) "validate accepted tampered $expected."
    Assert ((State-Text) -eq $before) "validate mutated state for tampered $expected."
}
function Set-IsolatedStage($stage) {
    $value = State
    $value.stage = $stage
    if ($stage -notin @('ANALYSIS','PLANNING')) { $value.cyclesUsed = 1 }
    Write-State $value
}
function New-TestTask {
    & $runner -Action new -Id $script:taskId -Title 'Isolated workflow test' | Out-Null
    Reject { Run 'advance' 'PLANNING' } 'MISSING_COMPLETED_DISPATCH'
    Do-Stage 'ANALYSIS' 'SYSTEM_ANALYST' '/root/analyst' 'gpt-5.6-sol' 'medium' 'analysis'
    Run 'advance' 'PLANNING'
    Do-Stage 'PLANNING' 'SYSTEM_ANALYST' '/root/planner' 'gpt-5.6-sol' 'medium' 'specs'
    Do-Stage 'PLANNING' 'ANDROID_DEVELOPER' '/root/technical' 'gpt-5.6-terra' 'high' 'technical-plan'
    Do-Stage 'PLANNING' 'MOBILE_QA' '/root/qa' 'gpt-5.6-terra' 'high' 'qa-plan'
    Reject { Run 'advance' 'CODING' } 'approval'
    Run 'approve-plan'; Run 'advance' 'CODING'
}
function To-Testing($coder = '/root/coder', $priorCoder = '') {
    Do-Stage 'CODING' 'ANDROID_DEVELOPER' $coder 'gpt-5.6-terra' 'high' 'implementation'
    Run 'advance' 'INVARIANT_VALIDATION'
    Do-Stage 'INVARIANT_VALIDATION' 'ANDROID_DEVELOPER' '/root/invariants' 'gpt-5.6-terra' 'high' 'invariants'
    Run 'advance' 'CODE_REVIEW'
    Reject { Dispatch 'CODE_REVIEW' 'CODE_REVIEWER' $coder 'gpt-5.6-sol' 'medium' 'Self review' } 'Self-review'
    if ($priorCoder) {
        Reject { Dispatch 'CODE_REVIEW' 'CODE_REVIEWER' $priorCoder 'gpt-5.6-sol' 'medium' 'Historical author review' } 'Self-review'
    }
    Do-Stage 'CODE_REVIEW' 'CODE_REVIEWER' '/root/reviewer' 'gpt-5.6-sol' 'medium' 'review'
    Run 'advance' 'TESTING'
    Do-Stage 'TESTING' 'MOBILE_QA' '/root/tester' 'gpt-5.6-terra' 'high' 'tests'
}

# QP-01, 02, 03, 04, 07: mandatory dispatch, identity matrix and independent review.
& $runner -Action new -Id $script:taskId -Title 'Negative dispatch test' | Out-Null
foreach ($alias in @('root','/root','/root/','/root/.','/root/..',' /root/agent','/ROOT/agent')) {
    Reject-Unchanged { Dispatch 'ANALYSIS' 'SYSTEM_ANALYST' $alias 'gpt-5.6-sol' 'medium' 'Invalid actor alias' } 'canonical'
}
Reject-Unchanged { Dispatch 'ANALYSIS' 'SYSTEM_ANALYST' '/root/bad_model' 'gpt-5.6-terra' 'medium' 'Invalid model' } 'mismatch'
Assert ((State).provenance.dispatches.Count -eq 0) 'Rejected dispatch mutated state.'
Remove-Item -LiteralPath (Join-Path $testRoot 'tasks/TASK-900') -Recurse -Force
New-TestTask
Assert ((State).cyclesUsed -eq 1) 'First cycle not counted.'

# QP-05: changing a completed report blocks the transition and validate reports stale evidence.
$implementation = Join-Path $testRoot 'tasks/TASK-900/implementation.md'
$coding = Dispatch 'CODING' 'ANDROID_DEVELOPER' '/root/coder' 'gpt-5.6-terra' 'high' 'Tamper test'
Report 'implementation'; Complete $coding
Add-Content -LiteralPath $implementation -Value 'Tampered.'
$validation = & $runner -Action validate -Id $script:taskId | ConvertFrom-Json
Assert (-not $validation.valid -and $validation.diagnostics -match 'STALE_REPORT') 'validate must fail stale report.'
Reject { Run 'advance' 'INVARIANT_VALIDATION' } 'STALE_REPORT'
Report 'implementation'; Run 'advance' 'INVARIANT_VALIDATION'
Do-Stage 'INVARIANT_VALIDATION' 'ANDROID_DEVELOPER' '/root/invariants' 'gpt-5.6-terra' 'high' 'invariants'
Run 'advance' 'CODE_REVIEW'
Reject { Dispatch 'CODE_REVIEW' 'CODE_REVIEWER' '/root/coder' 'gpt-5.6-sol' 'medium' 'Self review' } 'Self-review'
Do-Stage 'CODE_REVIEW' 'CODE_REVIEWER' '/root/reviewer' 'gpt-5.6-sol' 'medium' 'review'
Run 'advance' 'TESTING'
Do-Stage 'TESTING' 'MOBILE_QA' '/root/tester' 'gpt-5.6-terra' 'high' 'tests'

# QP-06, 11: rollback invalidates old evidence; cycles and exhausted-cycle decision remain enforced.
Run 'advance' 'CODING'
Assert ((State).cyclesUsed -eq 2) 'Second cycle not counted.'
Reject { Run 'advance' 'INVARIANT_VALIDATION' } 'MISSING_COMPLETED_DISPATCH'
To-Testing '/root/coder_cycle_2'
Reject { Run 'advance' 'CODING' } 'Cycle limit'
Assert ((State).waitingFor -eq 'CYCLE_DECISION') 'Missing exhausted-cycle decision.'
Run 'accept'
Assert ((State).stage -eq 'ACCEPTED_WITH_ISSUES') 'Acceptance must not claim DONE.'

# QP-05, 10: approval is invalidated by plan changes, even with otherwise valid evidence.
$script:taskId = 'TASK-901'; New-TestTask
Add-Content -LiteralPath (Join-Path $testRoot 'tasks/TASK-901/specs.md') -Value 'Plan changed.'
$validation = & $runner -Action validate -Id $script:taskId | ConvertFrom-Json
Assert (-not $validation.valid -and $validation.diagnostics -match 'STALE_APPROVED_PLAN') 'validate must fail stale approved plan.'
Reject { Run 'advance' 'INVARIANT_VALIDATION' } 'approval'

# QP-08, 09: migration preserves history but never fabricates completed evidence.
$script:taskId = 'TASK-902'; & $runner -Action new -Id $script:taskId -Title 'Legacy workflow test' | Out-Null
$legacyPath = Join-Path $testRoot 'tasks/TASK-902/state.json'
$legacy = State
foreach ($field in @('cyclesUsed','cycleLimit','approvedPlanHash','waitingFor','schemaVersion','provenance')) { $legacy.PSObject.Properties.Remove($field) }
$legacy | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $legacyPath -Encoding UTF8
$migrated = & $runner -Action status -Id $script:taskId | ConvertFrom-Json
Assert ($migrated.schemaVersion -eq 2 -and $migrated.provenance.dispatches.Count -eq 0 -and $migrated.cyclesUsed -eq 0) 'Legacy migration fabricated or lost state.'
Report 'analysis'
Reject { Run 'advance' 'PLANNING' } 'MISSING_COMPLETED_DISPATCH'

# QP-02, QP-05: every ledger binding fails both validate and transition when missing or tampered.
$script:taskId = 'TASK-903'; New-TestTask
$tamperDispatch = Dispatch 'CODING' 'ANDROID_DEVELOPER' '/root/tamper_coder' 'gpt-5.6-terra' 'high' 'Tamper matrix'
Report 'implementation'; Complete $tamperDispatch
$baseline = State-Text
$mutations = @(
    [pscustomobject]@{ name='dispatch stage'; apply={ param($s) $s.provenance.dispatches[-1].stage = 'TESTING' } },
    [pscustomobject]@{ name='dispatch role'; apply={ param($s) $s.provenance.dispatches[-1].role = 'MOBILE_QA' } },
    [pscustomobject]@{ name='dispatch agentId'; apply={ param($s) $s.provenance.dispatches[-1].agentId = '/root/' } },
    [pscustomobject]@{ name='dispatch model'; apply={ param($s) $s.provenance.dispatches[-1].model = 'gpt-5.6-sol' } },
    [pscustomobject]@{ name='dispatch reasoning'; apply={ param($s) $s.provenance.dispatches[-1].reasoningEffort = 'medium' } },
    [pscustomobject]@{ name='dispatch cycle'; apply={ param($s) $s.provenance.dispatches[-1].cycle = 99 } },
    [pscustomobject]@{ name='dispatch planHash'; apply={ param($s) $s.provenance.dispatches[-1].planHash = 'BAD' } },
    [pscustomobject]@{ name='completion reportHash'; apply={ param($s) $s.provenance.completions[-1].reportHash = 'BAD' } }
)
foreach ($mutation in $mutations) {
    $value = State; & $mutation.apply $value; Write-State $value
    Require-InvalidValidate $mutation.name
    Reject-Unchanged { Run 'advance' 'INVARIANT_VALIDATION' } 'Provenance'
    Write-StateText $baseline
}
foreach ($field in @('stage','role','agentId','model','reasoningEffort','cycle','planHash')) {
    $value = State; [void]$value.provenance.dispatches[-1].PSObject.Properties.Remove($field); Write-State $value
    Require-InvalidValidate "missing dispatch $field"
    Reject-Unchanged { Run 'advance' 'INVARIANT_VALIDATION' } 'Provenance'
    Write-StateText $baseline
}
$value = State; [void]$value.provenance.completions[-1].PSObject.Properties.Remove('reportHash'); Write-State $value
Require-InvalidValidate 'missing completion reportHash'
Reject-Unchanged { Run 'advance' 'INVARIANT_VALIDATION' } 'Provenance'
Write-StateText $baseline

# QP-04: every stage rejects incompatible role, model and reasoning before state mutation.
$matrix = @(
    [pscustomobject]@{ stage='ANALYSIS'; role='SYSTEM_ANALYST'; model='gpt-5.6-sol'; reasoning='medium' },
    [pscustomobject]@{ stage='PLANNING'; role='SYSTEM_ANALYST'; model='gpt-5.6-sol'; reasoning='medium' },
    [pscustomobject]@{ stage='CODING'; role='ANDROID_DEVELOPER'; model='gpt-5.6-terra'; reasoning='high' },
    [pscustomobject]@{ stage='INVARIANT_VALIDATION'; role='ANDROID_DEVELOPER'; model='gpt-5.6-terra'; reasoning='high' },
    [pscustomobject]@{ stage='CODE_REVIEW'; role='CODE_REVIEWER'; model='gpt-5.6-sol'; reasoning='medium' },
    [pscustomobject]@{ stage='TESTING'; role='MOBILE_QA'; model='gpt-5.6-terra'; reasoning='high' }
)
$number = 920
foreach ($entry in $matrix) {
    $script:taskId = "TASK-$number"; $number++
    & $runner -Action new -Id $script:taskId -Title 'Role matrix reject test' | Out-Null
    Set-IsolatedStage $entry.stage
    $wrongReasoning = if ($entry.reasoning -eq 'high') { 'medium' } else { 'high' }
    Reject-Unchanged { Dispatch $entry.stage $entry.role "/root/matrix_$number" $entry.model $wrongReasoning 'Wrong reasoning' } 'mismatch'
    $wrongModel = if ($entry.model -eq 'gpt-5.6-sol') { 'gpt-5.6-terra' } else { 'gpt-5.6-sol' }
    Reject-Unchanged { Dispatch $entry.stage $entry.role "/root/matrix_$number" $wrongModel $entry.reasoning 'Wrong model' } 'mismatch'
    $wrongRole = if ($entry.stage -eq 'PLANNING') { 'CODE_REVIEWER' } elseif ($entry.role -eq 'SYSTEM_ANALYST') { 'MOBILE_QA' } else { 'SYSTEM_ANALYST' }
    Reject-Unchanged { Dispatch $entry.stage $wrongRole "/root/matrix_$number" $entry.model $entry.reasoning 'Wrong role' } 'not required'
}

# QP-06 and QP-11: invalidated dispatch cannot be reused; real cycle decisions cover allow-cycles and user-fixes.
$script:taskId = 'TASK-940'; New-TestTask
$cycleOneDispatch = Dispatch 'CODING' 'ANDROID_DEVELOPER' '/root/coder_cycle_one' 'gpt-5.6-terra' 'high' 'Cycle one'
Report 'implementation'; Complete $cycleOneDispatch
Run 'advance' 'INVARIANT_VALIDATION'
Do-Stage 'INVARIANT_VALIDATION' 'ANDROID_DEVELOPER' '/root/invariants_cycle_one' 'gpt-5.6-terra' 'high' 'invariants'
Run 'advance' 'CODE_REVIEW'; Do-Stage 'CODE_REVIEW' 'CODE_REVIEWER' '/root/reviewer_cycle_one' 'gpt-5.6-sol' 'medium' 'review'
Run 'advance' 'TESTING'; Do-Stage 'TESTING' 'MOBILE_QA' '/root/tester_cycle_one' 'gpt-5.6-terra' 'high' 'tests'
Run 'advance' 'CODING'
Reject-Unchanged { Complete $cycleOneDispatch } 'not active'
To-Testing '/root/coder_cycle_two' '/root/coder_cycle_one'
Reject { Run 'advance' 'CODING' } 'Cycle limit'
Assert ((State).waitingFor -eq 'CYCLE_DECISION') 'Missing cycle decision before allow-cycles.'
& $runner -Action allow-cycles -Id $script:taskId -AdditionalCycles 1 -Reason 'Synthetic explicit approval of one additional cycle' | Out-Null
Assert ((State).cycleLimit -eq 3 -and $null -eq (State).waitingFor) 'allow-cycles did not persist.'
Run 'advance' 'CODING'; To-Testing '/root/coder_cycle_three'
Reject { Run 'advance' 'CODING' } 'Cycle limit'
& $runner -Action user-fixes -Id $script:taskId -Reason 'Synthetic explicit user completion of manual fixes' | Out-Null
Assert ((State).stage -eq 'INVARIANT_VALIDATION') 'user-fixes did not require fresh validation.'
Assert ((Get-Content -Raw (Join-Path $testRoot 'tasks/TASK-940/invariants.md')) -match 'fresh verification') 'user-fixes did not reset invariant artifact.'
Reject { Run 'advance' 'CODE_REVIEW' } 'MISSING_COMPLETED_DISPATCH'

# QP-08: legacy cycle usage is derived from real history, while provenance remains empty.
$script:taskId = 'TASK-950'; & $runner -Action new -Id $script:taskId -Title 'Legacy historical attempts test' | Out-Null
$legacyPath = Join-Path $testRoot 'tasks/TASK-950/state.json'
$legacy = State
$legacy.history = @($legacy.history) + @(@{ from='PLANNING'; to='CODING'; at='2026-09-22T00:00:00.0000000Z'; reason='Historical coding entry' })
foreach ($field in @('cyclesUsed','cycleLimit','approvedPlanHash','waitingFor','schemaVersion','provenance')) { [void]$legacy.PSObject.Properties.Remove($field) }
$legacy | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $legacyPath -Encoding UTF8
$migrated = & $runner -Action status -Id $script:taskId | ConvertFrom-Json
Assert ($migrated.cyclesUsed -eq 1 -and $migrated.provenance.dispatches.Count -eq 0 -and $migrated.provenance.completions.Count -eq 0) 'Legacy history migration fabricated evidence or lost cycle count.'

# QP-12: policy texts and all four profiles publish the same matrix and honest trust boundary.
$projectRoot = Split-Path $PSScriptRoot -Parent
$agents = Get-Content -Raw (Join-Path $projectRoot 'AGENTS.md')
$workflow = Get-Content -Raw (Join-Path $projectRoot 'docs/WORKFLOW.md')
foreach ($needle in @('gpt-5.6-sol','gpt-5.6-terra','/root/<lowercase_id>')) {
    Assert ($agents -match [regex]::Escape($needle)) "AGENTS missing $needle."
}
foreach ($needle in @('Sol/medium','Terra/high','/root/<lowercase_id>','не authentication')) {
    Assert ($workflow -match [regex]::Escape($needle)) "WORKFLOW missing $needle."
}
foreach ($profile in @('android-developer.md','mobile-qa.md','system-analyst.md','code-reviewer.md')) {
    $text = Get-Content -Raw (Join-Path $projectRoot "docs/agents/$profile")
    Assert ($text -match 'dispatch' -and $text -match 'complete-stage') "Profile $profile lacks provenance instructions."
}

Write-Output 'PASS: QP-01..QP-12 executed: positive flow, full field tamper/missing matrix, canonical root aliases, all-stage role/model/reasoning rejects, stale report/plan, invalidated reuse, independent review, legacy history, allow-cycles/user-fixes/accept, and docs consistency.'

Write-Output "Isolated artifacts: $testRoot"

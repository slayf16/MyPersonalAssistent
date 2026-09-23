$ErrorActionPreference = 'Stop'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('mypersonalassistent-workflow-' + [guid]::NewGuid().ToString('N'))
$scriptsDir = Join-Path $testRoot 'scripts'
New-Item -ItemType Directory -Path $scriptsDir -Force | Out-Null
$runner = Join-Path $scriptsDir 'task.ps1'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'task.ps1') -Destination $runner

function Assert($condition, $message) { if (-not $condition) { throw $message } }
function State($id) { Get-Content -LiteralPath (Join-Path $testRoot "tasks/$id/state.json") -Raw | ConvertFrom-Json }
function StateText($id) { Get-Content -LiteralPath (Join-Path $testRoot "tasks/$id/state.json") -Raw }
function Invoke-Task($id, [hashtable]$taskArgs) { & $runner -Id $id @taskArgs }
function Reject-Unchanged($id, [scriptblock]$operation, $pattern) {
    $before = StateText $id
    try { & $operation } catch {
        Assert ($_.Exception.Message -match $pattern) "Unexpected rejection for [$pattern]: $($_.Exception.Message)"
        Assert ((StateText $id) -eq $before) "Rejected operation [$pattern] mutated state."
        return
    }
    throw "Expected rejection [$pattern], but operation succeeded."
}
function Write-Scope($id, [string[]]$checks = @('workflow')) {
    ([ordered]@{ scope=@('workflow change'); result=@('guard updated'); checks=$checks } | ConvertTo-Json -Depth 4) |
        Set-Content -LiteralPath (Join-Path $testRoot "tasks/$id/scope.json") -Encoding UTF8
}
function New-V3($id, $mode) {
    Invoke-Task $id @{ Action='new'; Title='Isolated workflow test'; Mode=$mode } | Out-Null
    Write-Scope $id
    Invoke-Task $id @{ Action='approve-scope'; Reason='Synthetic explicit scope approval' } | Out-Null
}
function Dispatch($id, $item, $phase, $role, $actor, [guid]$reviewOf = [guid]::Empty) {
    $taskArgs = @{ Action='dispatch'; WorkItem=$item; Phase=$phase; V3Role=$role; ActorId=$actor }
    if ($reviewOf -ne [guid]::Empty) { $taskArgs.ReviewOf = $reviewOf }
    $result = Invoke-Task $id $taskArgs | ConvertFrom-Json
    @($result.provenance.dispatches | Select-Object -Last 1)[0].id
}
function Report($id, [guid]$dispatchId, $outcome) {
    $dispatch = @((State $id).provenance.dispatches | Where-Object { $_.id -eq $dispatchId.ToString() })[0]
    Set-Content -LiteralPath (Join-Path $testRoot "tasks/$id/$($dispatch.artifact)") -Encoding UTF8 -Value "Outcome: $outcome`n`nSynthetic report $dispatchId for isolated workflow self-test."
}
function Complete($id, [guid]$dispatchId, $outcome = 'PASS') { Report $id $dispatchId $outcome; Invoke-Task $id @{ Action='complete-stage'; DispatchId=$dispatchId } | Out-Null }
function Check($id, $item, $check = 'workflow', $outcome = 'PASS') { Invoke-Task $id @{ Action='record-check'; WorkItem=$item; CheckId=$check; Command='pwsh -File scripts/test-workflow.ps1'; Outcome=$outcome; Evidence='Synthetic isolated result' } | Out-Null }
function V2-Dispatch($id, $stage, $role, $agent, $model, $reasoning) {
    $result = Invoke-Task $id @{ Action='dispatch'; Stage=$stage; Role=$role; AgentId=$agent; Model=$model; ReasoningEffort=$reasoning; Scope='Synthetic V2 regression scope' } | ConvertFrom-Json
    @($result.provenance.dispatches | Select-Object -Last 1)[0].id
}

try {
    # Small/root + canonical JSON scope: whitespace and property order do not change approval.
    $small = 'TASK-900'; New-V3 $small 'small'
    $impl = Dispatch $small 'core' 'IMPLEMENT' 'EXECUTOR' 'root'; Complete $small $impl; Check $small 'core'
    Set-Content -LiteralPath (Join-Path $testRoot "tasks/$small/scope.json") -Encoding UTF8 -Value @'
{ "checks" : [ "workflow" ], "result" : [ "guard updated" ], "scope" : [ "workflow change" ] }
'@
    $validation = Invoke-Task $small @{ Action='validate' } | ConvertFrom-Json
    Assert $validation.valid 'Whitespace-only scope JSON edit invalidated approval.'
    Invoke-Task $small @{ Action='finish'; Reason='Synthetic small result' } | Out-Null
    Assert ((State $small).status -eq 'DONE') 'Small root flow did not finish.'

    # A semantic scope edit blocks every kind of dispatch until a new explicit approval.
    $staleScope = 'TASK-906'; New-V3 $staleScope 'standard'
    ([ordered]@{ scope=@('expanded workflow change'); result=@('guard updated'); checks=@('workflow') } | ConvertTo-Json -Depth 4) |
        Set-Content -LiteralPath (Join-Path $testRoot "tasks/$staleScope/scope.json") -Encoding UTF8
    Reject-Unchanged $staleScope { Dispatch $staleScope 'core' 'PLAN' 'EXECUTOR' 'root' } 'STALE_SCOPE_APPROVAL'
    Reject-Unchanged $staleScope { Dispatch $staleScope 'core' 'IMPLEMENT' 'EXECUTOR' 'root' } 'STALE_SCOPE_APPROVAL'
    Reject-Unchanged $staleScope { Dispatch $staleScope 'core' 'REVIEW' 'REVIEWER' '/root/reviewer' } 'STALE_SCOPE_APPROVAL'

    # Completed V3 reports are hash-bound; a later edit is not evidence of PASS.
    $tamper = 'TASK-905'; New-V3 $tamper 'small'
    $tamperImpl = Dispatch $tamper 'core' 'IMPLEMENT' 'EXECUTOR' 'root'; Complete $tamper $tamperImpl
    $tamperDispatch = @((State $tamper).provenance.dispatches | Where-Object { $_.id -eq $tamperImpl })[0]
    Add-Content -LiteralPath (Join-Path $testRoot "tasks/$tamper/$($tamperDispatch.artifact)") -Value 'Tampered.'
    $tamperValidation = Invoke-Task $tamper @{ Action='validate' } | ConvertFrom-Json
    Assert (-not $tamperValidation.valid -and $tamperValidation.diagnostics -match 'STALE_REPORT') 'V3 tampered report was accepted.'
    Reject-Unchanged $tamper { Invoke-Task $tamper @{ Action='finish'; Reason='Synthetic tampered finish' } } 'CANNOT_FINISH'

    # Standard review checks all implementation authors in the run, not merely the reviewed item.
    $standard = 'TASK-901'; New-V3 $standard 'standard'
    $core = Dispatch $standard 'core' 'IMPLEMENT' 'EXECUTOR' 'root'; Complete $standard $core
    $ui = Dispatch $standard 'ui' 'IMPLEMENT' 'EXECUTOR' '/root/ui_author'; Complete $standard $ui
    Reject-Unchanged $standard { Dispatch $standard 'ui' 'REVIEW' 'REVIEWER' '/root/reviewer' ([guid]$core) } 'REVIEW_REQUIRES_CURRENT_PASS_IMPLEMENTATION'
    Reject-Unchanged $standard { Dispatch $standard 'core' 'REVIEW' 'REVIEWER' 'root' ([guid]$core) } 'SELF_REVIEW'
    $reviewCore = Dispatch $standard 'core' 'REVIEW' 'REVIEWER' '/root/reviewer' ([guid]$core); Complete $standard $reviewCore
    $reviewUi = Dispatch $standard 'ui' 'REVIEW' 'REVIEWER' '/root/reviewer' ([guid]$ui); Complete $standard $reviewUi
    Check $standard 'core'; Check $standard 'ui'; Invoke-Task $standard @{ Action='finish'; Reason='Synthetic standard result' } | Out-Null

    # Work-item replacement isolates the other item, while obsolete dispatch cannot be completed.
    $parallel = 'TASK-902'; New-V3 $parallel 'small'
    $parallelCore = Dispatch $parallel 'core' 'IMPLEMENT' 'EXECUTOR' 'root'; Complete $parallel $parallelCore
    $oldUi = Dispatch $parallel 'ui' 'IMPLEMENT' 'EXECUTOR' 'root'
    $newUi = Dispatch $parallel 'ui' 'IMPLEMENT' 'EXECUTOR' 'root'; Complete $parallel $newUi
    Assert (@((State $parallel).provenance.dispatches | Where-Object { $_.id -eq $parallelCore }).status -eq 'COMPLETED') 'Replacing ui invalidated core evidence.'
    Reject-Unchanged $parallel { Complete $parallel ([guid]$oldUi) } 'DISPATCH_NOT_ACTIVE'
    Check $parallel 'core'; Check $parallel 'ui'; Invoke-Task $parallel @{ Action='finish'; Reason='Synthetic parallel result' } | Out-Null

    # Failed review is fixed in one batch; old code-bound check cannot validate the replacement.
    $fix = 'TASK-903'; New-V3 $fix 'standard'
    $beforeFix = Dispatch $fix 'core' 'IMPLEMENT' 'EXECUTOR' 'root'; Complete $fix $beforeFix; Check $fix 'core'
    $failedReview = Dispatch $fix 'core' 'REVIEW' 'REVIEWER' '/root/reviewer' ([guid]$beforeFix); Complete $fix $failedReview 'FAIL'
    $findingValidation = Invoke-Task $fix @{ Action='validate' } | ConvertFrom-Json
    Assert (-not $findingValidation.valid -and $findingValidation.diagnostics -match 'UNRESOLVED_REVIEW_FINDING') 'Finish validation did not retain the unresolved review finding.'
    Reject-Unchanged $fix { Dispatch $fix 'core' 'IMPLEMENT' 'EXECUTOR' 'root' } 'FIX_LINK_REQUIRED'
    Reject-Unchanged $fix { Dispatch $fix 'ui' 'IMPLEMENT' 'EXECUTOR' 'root' ([guid]$failedReview) } 'FIX_REQUIRES_CURRENT_NONPASS_REVIEW'
    $afterFix = Dispatch $fix 'core' 'IMPLEMENT' 'EXECUTOR' 'root' ([guid]$failedReview); Complete $fix $afterFix
    $notReady = Invoke-Task $fix @{ Action='validate' } | ConvertFrom-Json
    Assert (-not $notReady.valid -and $notReady.diagnostics -match 'MISSING_CHECK') 'Old check unexpectedly proved fixed code.'
    $finalReview = Dispatch $fix 'core' 'REVIEW' 'REVIEWER' '/root/reviewer' ([guid]$afterFix); Complete $fix $finalReview
    Check $fix 'core'; Invoke-Task $fix @{ Action='finish'; Reason='Synthetic batch fix result' } | Out-Null

    # Check ids are scope-bound; acceptance/reopen preserves prior run evidence separately.
    $reopen = 'TASK-904'; New-V3 $reopen 'small'
    Reject-Unchanged $reopen { Check $reopen 'core' 'invented' } 'UNKNOWN_CHECK_ID'
    Invoke-Task $reopen @{ Action='accept'; Reason='Synthetic explicit acceptance with issue' } | Out-Null
    $beforeReopen = (State $reopen).history.Count
    Invoke-Task $reopen @{ Action='reopen'; Reason='Synthetic explicit reopen decision' } | Out-Null
    Assert ((State $reopen).run -eq 2 -and (State $reopen).history.Count -gt $beforeReopen) 'Reopen did not preserve V3 history.'

    # V2 state is read structurally without fabricated actors/completions, and can be reopened honestly.
    $legacy = 'TASK-950'; $legacyDir = Join-Path $testRoot "tasks/$legacy"; New-Item -ItemType Directory -Path $legacyDir -Force | Out-Null
    [ordered]@{ id=$legacy; title='Legacy'; stage='ACCEPTED_WITH_ISSUES'; updatedAt='2026-01-01T00:00:00Z'; history=@() } |
        ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $legacyDir 'state.json') -Encoding UTF8
    foreach ($name in @('analysis.md','specs.md','technical-plan.md','qa-plan.md','implementation.md','invariants.md','review.md','tests.md')) { Set-Content -LiteralPath (Join-Path $legacyDir $name) -Value "Outcome: BLOCKED`n`nLegacy." }
    $legacyState = Invoke-Task $legacy @{ Action='status' } | ConvertFrom-Json
    Assert ($legacyState.provenance.dispatches.Count -eq 0 -and $legacyState.provenance.completions.Count -eq 0) 'Legacy read fabricated provenance.'
    Invoke-Task $legacy @{ Action='reopen'; Reason='Synthetic explicit reopen decision' } | Out-Null
    Assert ((State $legacy).stage -eq 'PLANNING' -and (State $legacy).history.Count -gt 0) 'Legacy accepted task did not reopen honestly.'

    # Legacy negative regression: stale report and self-review remain fail-closed.
    $v2 = 'TASK-951'; $v2Dir = Join-Path $testRoot "tasks/$v2"; New-Item -ItemType Directory -Path $v2Dir -Force | Out-Null
    foreach ($name in @('analysis.md','technical-plan.md','qa-plan.md','implementation.md','invariants.md','review.md','tests.md')) { Set-Content -LiteralPath (Join-Path $v2Dir $name) -Value "Outcome: BLOCKED`n`nSynthetic V2." }
    Set-Content -LiteralPath (Join-Path $v2Dir 'specs.md') -Value "Outcome: PASS`n`nSynthetic V2 approved specification."
    $v2Hash = (Get-FileHash -LiteralPath (Join-Path $v2Dir 'specs.md') -Algorithm SHA256).Hash
    [ordered]@{ id=$v2; title='Legacy regression'; stage='CODING'; updatedAt='2026-01-01T00:00:00Z'; history=@(); cyclesUsed=1; cycleLimit=2; approvedPlanHash=$v2Hash; waitingFor=$null; schemaVersion=2; provenance=[ordered]@{ dispatches=@(); completions=@(); legacyMigratedAt=$null; legacyBootstrapStage=$null } } |
        ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $v2Dir 'state.json') -Encoding UTF8
    $v2Coder = V2-Dispatch $v2 'CODING' 'ANDROID_DEVELOPER' '/root/legacy_coder' 'gpt-5.6-terra' 'high'; Complete $v2 $v2Coder
    Add-Content -LiteralPath (Join-Path $v2Dir 'implementation.md') -Value 'Tampered.'
    $v2Validation = Invoke-Task $v2 @{ Action='validate' } | ConvertFrom-Json
    Assert (-not $v2Validation.valid -and $v2Validation.diagnostics -match 'STALE_REPORT') 'Legacy tampered report was accepted.'
    Reject-Unchanged $v2 { Invoke-Task $v2 @{ Action='advance'; To='INVARIANT_VALIDATION'; Reason='Synthetic transition' } } 'STALE_REPORT'
    $freshCoder = V2-Dispatch $v2 'CODING' 'ANDROID_DEVELOPER' '/root/legacy_coder' 'gpt-5.6-terra' 'high'; Complete $v2 $freshCoder
    Invoke-Task $v2 @{ Action='advance'; To='INVARIANT_VALIDATION'; Reason='Synthetic transition' } | Out-Null
    $v2Invariant = V2-Dispatch $v2 'INVARIANT_VALIDATION' 'ANDROID_DEVELOPER' '/root/legacy_invariants' 'gpt-5.6-terra' 'high'; Complete $v2 $v2Invariant
    Invoke-Task $v2 @{ Action='advance'; To='CODE_REVIEW'; Reason='Synthetic transition' } | Out-Null
    Reject-Unchanged $v2 { V2-Dispatch $v2 'CODE_REVIEW' 'CODE_REVIEWER' '/root/legacy_coder' 'gpt-5.6-sol' 'medium' } 'Self-review'
    Assert ((State $v2).cyclesUsed -eq 1) 'Legacy regression changed cycle counter.'

    Write-Output 'PASS: isolated V3 small/standard, scope, work-item, fix, reopen and legacy fail-closed workflow assertions completed.'
} finally {
    # Only delete the fresh child below the system temp root; never use a computed broad target.
    $resolvedRoot = [IO.Path]::GetFullPath($testRoot)
    $resolvedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($resolvedRoot.StartsWith($resolvedTemp, [StringComparison]::OrdinalIgnoreCase) -and $resolvedRoot -match 'mypersonalassistent-workflow-[0-9a-f]{32}$') {
        Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
    } else { throw "Unsafe test cleanup target: $resolvedRoot" }
}

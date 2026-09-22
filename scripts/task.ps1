param(
    [Parameter(Mandatory=$true)][ValidateSet('new','status','advance','approve-plan','allow-cycles','user-fixes','accept','dispatch','complete-stage','validate')][string]$Action,
    [Parameter(Mandatory=$true)][ValidatePattern('^TASK-[0-9]{3,}$')][string]$Id,
    [string]$Title,
    [ValidateSet('ANALYSIS','PLANNING','CODING','INVARIANT_VALIDATION','CODE_REVIEW','TESTING','DONE')][string]$To,
    [ValidateSet('ANALYSIS','PLANNING','CODING','INVARIANT_VALIDATION','CODE_REVIEW','TESTING')][string]$Stage,
    [ValidateSet('SYSTEM_ANALYST','ANDROID_DEVELOPER','MOBILE_QA','CODE_REVIEWER')][string]$Role,
    [string]$AgentId,
    [string]$Model,
    [ValidateSet('medium','high')][string]$ReasoningEffort,
    [string]$Scope,
    [guid]$DispatchId,
    [string]$Reason,
    [ValidateRange(1,100)][int]$AdditionalCycles = 1
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$dir = Join-Path (Join-Path $root 'tasks') $Id
$statePath = Join-Path $dir 'state.json'
$stages = @('ANALYSIS','PLANNING','CODING','INVARIANT_VALIDATION','CODE_REVIEW','TESTING','DONE')
$reports = @('analysis.md','specs.md','implementation.md','invariants.md','review.md','tests.md')
$allArtifacts = @('analysis.md','specs.md','technical-plan.md','qa-plan.md','implementation.md','invariants.md','review.md','tests.md')
function Save-State($value) {
    $temp = Join-Path $dir 'state.tmp'
    $value | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $temp -Encoding UTF8
    Move-Item -LiteralPath $temp -Destination $statePath -Force
}
function Get-Requirements([string]$forStage) {
    switch ($forStage) {
        'ANALYSIS' { return @([pscustomobject]@{ role='SYSTEM_ANALYST'; artifact='analysis.md'; model='gpt-5.6-sol'; reasoning='medium' }) }
        'PLANNING' { return @(
            [pscustomobject]@{ role='SYSTEM_ANALYST'; artifact='specs.md'; model='gpt-5.6-sol'; reasoning='medium' },
            [pscustomobject]@{ role='ANDROID_DEVELOPER'; artifact='technical-plan.md'; model='gpt-5.6-terra'; reasoning='high' },
            [pscustomobject]@{ role='MOBILE_QA'; artifact='qa-plan.md'; model='gpt-5.6-terra'; reasoning='high' }
        ) }
        'CODING' { return @([pscustomobject]@{ role='ANDROID_DEVELOPER'; artifact='implementation.md'; model='gpt-5.6-terra'; reasoning='high' }) }
        'INVARIANT_VALIDATION' { return @([pscustomobject]@{ role='ANDROID_DEVELOPER'; artifact='invariants.md'; model='gpt-5.6-terra'; reasoning='high' }) }
        'CODE_REVIEW' { return @([pscustomobject]@{ role='CODE_REVIEWER'; artifact='review.md'; model='gpt-5.6-sol'; reasoning='medium' }) }
        'TESTING' { return @([pscustomobject]@{ role='MOBILE_QA'; artifact='tests.md'; model='gpt-5.6-terra'; reasoning='high' }) }
        default { return @() }
    }
}
function Get-PlanHash { (Get-FileHash -LiteralPath (Join-Path $dir 'specs.md') -Algorithm SHA256).Hash }
function Get-EffectiveCycle([string]$forStage) {
    if ($forStage -in @('ANALYSIS','PLANNING')) { return 0 }
    [int]$state.cyclesUsed
}
function Ensure-ProvenanceSchema {
    $changed = $false
    # Structural legacy migration intentionally creates no past actor, dispatch or completion.
    if (-not $state.PSObject.Properties['schemaVersion']) {
        $state | Add-Member schemaVersion 2
        $state | Add-Member provenance ([pscustomobject]@{
            dispatches=@(); completions=@(); legacyMigratedAt=[DateTime]::UtcNow.ToString('o'); legacyBootstrapStage=$state.stage
        })
        $changed = $true
    }
    if (-not $state.PSObject.Properties['provenance']) {
        $state | Add-Member provenance ([pscustomobject]@{ dispatches=@(); completions=@(); legacyMigratedAt=[DateTime]::UtcNow.ToString('o'); legacyBootstrapStage=$state.stage })
        $changed = $true
    }
    foreach ($name in @('dispatches','completions')) {
        if (-not $state.provenance.PSObject.Properties[$name]) { $state.provenance | Add-Member $name @(); $changed = $true }
    }
    $changed
}
function ConvertTo-CanonicalAgentId([string]$forAgentId) {
    # Codex canonical subagent task path: root itself is never a valid actor here.
    if ([string]::IsNullOrWhiteSpace($forAgentId) -or $forAgentId -cnotmatch '^/root/[a-z0-9_]+(?:/[a-z0-9_]+)*$') {
        throw 'AgentId must be a canonical non-root subagent path: /root/<lowercase_id>(/<lowercase_id>)*.'
    }
    $forAgentId
}
function Assert-DispatchIdentity($requirement, [string]$forAgentId, [string]$forModel, [string]$forReasoning) {
    [void](ConvertTo-CanonicalAgentId $forAgentId)
    if ($forModel -ne $requirement.model -or $forReasoning -ne $requirement.reasoning) {
        throw "Role/model/reasoning mismatch for $($requirement.role)."
    }
}
function Get-RelevantCoderAgentIds {
    $currentPlanHash = Get-PlanHash
    @($state.provenance.dispatches | Where-Object {
        $_.stage -eq 'CODING' -and $_.planHash -eq $currentPlanHash -and $_.PSObject.Properties['completedAt']
    } | ForEach-Object { ConvertTo-CanonicalAgentId $_.agentId } | Select-Object -Unique)
}
function Invalidate-Provenance([string]$fromStage, [string]$why) {
    $from = [array]::IndexOf($stages, $fromStage)
    $now = [DateTime]::UtcNow.ToString('o')
    foreach ($dispatch in @($state.provenance.dispatches)) {
        if ([array]::IndexOf($stages, [string]$dispatch.stage) -ge $from -and $dispatch.status -ne 'INVALIDATED') {
            $dispatch.status = 'INVALIDATED'
            $dispatch | Add-Member -NotePropertyName invalidatedAt -NotePropertyValue $now -Force
            $dispatch | Add-Member -NotePropertyName invalidatedReason -NotePropertyValue $why -Force
        }
    }
}
function Get-EvidenceDiagnostics([string]$forStage) {
    $problems = New-Object System.Collections.Generic.List[string]
    $cycle = Get-EffectiveCycle $forStage
    $planHash = Get-PlanHash
    if ($forStage -notin @('ANALYSIS','PLANNING')) {
        if ([string]::IsNullOrWhiteSpace([string]$state.approvedPlanHash)) { $problems.Add('MISSING_PLAN_APPROVAL') }
        elseif ($state.approvedPlanHash -ne $planHash) { $problems.Add('STALE_APPROVED_PLAN') }
    }
    foreach ($requirement in @(Get-Requirements $forStage)) {
        $matches = @($state.provenance.dispatches | Where-Object { $_.stage -eq $forStage -and $_.cycle -eq $cycle -and $_.role -eq $requirement.role -and $_.status -eq 'COMPLETED' })
        if ($matches.Count -ne 1) { $problems.Add("MISSING_COMPLETED_DISPATCH:$($requirement.role)"); continue }
        $dispatch = $matches[0]
        try { Assert-DispatchIdentity $requirement $dispatch.agentId $dispatch.model $dispatch.reasoningEffort } catch { $problems.Add("INVALID_IDENTITY:$($requirement.role)") }
        if ($dispatch.artifact -ne $requirement.artifact) { $problems.Add("INVALID_ARTIFACT:$($requirement.role)") }
        $planAuthor = $forStage -eq 'PLANNING' -and $requirement.role -eq 'SYSTEM_ANALYST'
        if (-not $planAuthor -and $dispatch.planHash -ne $planHash) { $problems.Add("STALE_DISPATCH_PLAN:$($requirement.role)") }
        if ($forStage -notin @('ANALYSIS','PLANNING') -and $dispatch.planHash -ne $state.approvedPlanHash) { $problems.Add("MISMATCHED_APPROVED_PLAN:$($requirement.role)") }
        $completion = @($state.provenance.completions | Where-Object { $_.dispatchId -eq $dispatch.id })
        if ($completion.Count -ne 1) { $problems.Add("MISSING_COMPLETION:$($requirement.role)"); continue }
        if ($completion[0].stage -ne $forStage -or $completion[0].cycle -ne $cycle -or $completion[0].planHash -ne $planHash) { $problems.Add("STALE_STAGE_OR_CYCLE:$($requirement.role)") }
        $path = Join-Path $dir $requirement.artifact
        if (-not (Test-Path -LiteralPath $path)) { $problems.Add("MISSING_ARTIFACT:$($requirement.role)") }
        else {
            $text = Get-Content -LiteralPath $path -Raw
            if ($text -notmatch '\AOutcome: PASS\r?\n' -or $text.Trim().Length -lt 40) { $problems.Add("REPORT_NOT_PASS:$($requirement.role)") }
            elseif ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $completion[0].reportHash) { $problems.Add("STALE_REPORT:$($requirement.role)") }
        }
    }
    if ($forStage -eq 'CODE_REVIEW') {
        $reviewer = @($state.provenance.dispatches | Where-Object { $_.stage -eq 'CODE_REVIEW' -and $_.cycle -eq $cycle -and $_.role -eq 'CODE_REVIEWER' -and $_.status -eq 'COMPLETED' })
        $coder = @($state.provenance.dispatches | Where-Object { $_.stage -eq 'CODING' -and $_.cycle -eq $cycle -and $_.role -eq 'ANDROID_DEVELOPER' -and $_.status -eq 'COMPLETED' })
        if ($coder.Count -eq 0) { $problems.Add('MISSING_CODER_FOR_INDEPENDENT_REVIEW') }
        elseif ($reviewer.Count -eq 1) {
            try {
                $reviewerId = ConvertTo-CanonicalAgentId $reviewer[0].agentId
                $coderIds = @(Get-RelevantCoderAgentIds)
                if ($coderIds -contains $reviewerId) { $problems.Add('SELF_REVIEW_FORBIDDEN') }
            } catch { $problems.Add('INVALID_IDENTITY:CODE_REVIEWER_OR_CODER') }
        }
    }
    @($problems)
}
function Assert-StageEvidence([string]$forStage) {
    $problems = @(Get-EvidenceDiagnostics $forStage)
    if ($problems.Count -gt 0) { throw "Provenance validation failed: $($problems -join ', ')." }
}
if ($Action -eq 'new') {
    if ([string]::IsNullOrWhiteSpace($Title)) { throw 'Title is required.' }
    if (Test-Path -LiteralPath $dir) { throw 'Task already exists.' }
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    $now = [DateTime]::UtcNow.ToString('o')
    Save-State ([ordered]@{
        id=$Id; title=$Title; stage='ANALYSIS'; updatedAt=$now; history=@(@{ from=$null; to='ANALYSIS'; at=$now; reason='Created' })
        cyclesUsed=0; cycleLimit=2; approvedPlanHash=$null; waitingFor=$null; schemaVersion=2
        provenance=[ordered]@{ dispatches=@(); completions=@(); legacyMigratedAt=$null; legacyBootstrapStage=$null }
    })
    foreach ($report in $allArtifacts) {
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
$schemaChanged = Ensure-ProvenanceSchema
if ($schemaChanged) { Save-State $state }
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
if ($Action -eq 'dispatch') {
    if (-not $Stage -or -not $Role -or [string]::IsNullOrWhiteSpace($AgentId) -or [string]::IsNullOrWhiteSpace($Model) -or -not $ReasoningEffort -or [string]::IsNullOrWhiteSpace($Scope)) {
        throw 'dispatch requires Stage, Role, AgentId, Model, ReasoningEffort and Scope.'
    }
    if ($Stage -ne $state.stage) { throw "Dispatch stage must equal current stage $($state.stage)." }
    $requirement = @(Get-Requirements $Stage | Where-Object { $_.role -eq $Role })
    if ($requirement.Count -ne 1) { throw "Role $Role is not required for stage $Stage." }
    $AgentId = ConvertTo-CanonicalAgentId $AgentId
    Assert-DispatchIdentity $requirement[0] $AgentId $Model $ReasoningEffort
    $cycle = Get-EffectiveCycle $Stage
    if ($Stage -eq 'CODE_REVIEW') {
        $coder = @($state.provenance.dispatches | Where-Object { $_.stage -eq 'CODING' -and $_.cycle -eq $cycle -and $_.role -eq 'ANDROID_DEVELOPER' -and $_.status -eq 'COMPLETED' })
        if ($coder.Count -eq 0) { throw 'CODE_REVIEW dispatch requires a completed coder record for the current cycle.' }
        $coderIds = @(Get-RelevantCoderAgentIds)
        if ($coderIds -contains $AgentId) { throw 'Self-review is forbidden: reviewer AgentId must differ from every completed coding author for the current plan.' }
    }
    # A re-dispatch is explicit; the superseded evidence remains audit-visible but unusable.
    foreach ($old in @($state.provenance.dispatches | Where-Object { $_.stage -eq $Stage -and $_.cycle -eq $cycle -and $_.role -eq $Role -and $_.status -ne 'INVALIDATED' })) {
        $old.status = 'INVALIDATED'
        $old | Add-Member -NotePropertyName invalidatedAt -NotePropertyValue ([DateTime]::UtcNow.ToString('o')) -Force
        $old | Add-Member -NotePropertyName invalidatedReason -NotePropertyValue 'Superseded by a new dispatch.' -Force
    }
    $dispatch = [pscustomobject]@{
        id=([guid]::NewGuid().ToString()); stage=$Stage; cycle=$cycle; role=$Role; agentId=$AgentId
        model=$Model; reasoningEffort=$ReasoningEffort; planHash=(Get-PlanHash); artifact=$requirement[0].artifact
        scope=$Scope; dispatchedAt=[DateTime]::UtcNow.ToString('o'); status='DISPATCHED'
    }
    $state.provenance.dispatches = @($state.provenance.dispatches) + @($dispatch)
    Record-Event "Dispatch $($dispatch.id): $Stage/$Role -> $AgentId ($Model/$ReasoningEffort)."
    $state | ConvertTo-Json -Depth 12
    exit 0
}
if ($Action -eq 'complete-stage') {
    if ($DispatchId -eq [guid]::Empty) { throw 'complete-stage requires DispatchId.' }
    $dispatch = @($state.provenance.dispatches | Where-Object { $_.id -eq $DispatchId.ToString() })
    if ($dispatch.Count -ne 1) { throw 'Unknown DispatchId.' }
    $dispatch = $dispatch[0]
    if ($dispatch.status -ne 'DISPATCHED') { throw 'Dispatch is not active.' }
    if ($dispatch.stage -ne $state.stage -or $dispatch.cycle -ne (Get-EffectiveCycle $state.stage)) { throw 'Dispatch is stale for the current stage or cycle.' }
    $requirement = @(Get-Requirements $dispatch.stage | Where-Object { $_.role -eq $dispatch.role })
    if ($requirement.Count -ne 1 -or $dispatch.artifact -ne $requirement[0].artifact) { throw 'Dispatch role or artifact is invalid.' }
    Assert-DispatchIdentity $requirement[0] $dispatch.agentId $dispatch.model $dispatch.reasoningEffort
    $path = Join-Path $dir $dispatch.artifact
    $text = Get-Content -LiteralPath $path -Raw
    if ($text -notmatch '\AOutcome: PASS\r?\n' -or $text.Trim().Length -lt 40) { throw "Complete $($dispatch.artifact) with Outcome: PASS and evidence first." }
    $planHash = Get-PlanHash
    $planAuthor = $dispatch.stage -eq 'PLANNING' -and $dispatch.role -eq 'SYSTEM_ANALYST'
    if (-not $planAuthor -and $dispatch.planHash -ne $planHash) { throw 'Dispatch plan hash is stale; create a new dispatch.' }
    if ($dispatch.stage -notin @('ANALYSIS','PLANNING') -and $dispatch.planHash -ne $state.approvedPlanHash) { throw 'Dispatch plan hash does not match the approved plan.' }
    $completion = [pscustomobject]@{
        dispatchId=$dispatch.id; stage=$dispatch.stage; cycle=$dispatch.cycle; planHash=$planHash
        reportHash=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash; completedAt=[DateTime]::UtcNow.ToString('o')
    }
    $state.provenance.completions = @($state.provenance.completions) + @($completion)
    $dispatch.status = 'COMPLETED'
    $dispatch | Add-Member -NotePropertyName completedAt -NotePropertyValue $completion.completedAt -Force
    Record-Event "Completion $($dispatch.id): $($dispatch.stage)/$($dispatch.role), report SHA256 $($completion.reportHash)."
    $state | ConvertTo-Json -Depth 12
    exit 0
}
if ($Action -eq 'validate') {
    $diagnostics = @(Get-EvidenceDiagnostics $state.stage)
    [pscustomobject]@{ id=$state.id; stage=$state.stage; cycle=(Get-EffectiveCycle $state.stage); valid=($diagnostics.Count -eq 0); diagnostics=$diagnostics } | ConvertTo-Json -Depth 6
    exit 0
}
if ($Action -in @('approve-plan','allow-cycles','user-fixes','accept')) {
    if ([string]::IsNullOrWhiteSpace($Reason)) { throw 'Reason must quote the explicit user decision and identify the message/date.' }
    if ($Action -eq 'approve-plan') {
        if ($state.stage -ne 'PLANNING') { throw 'Plan approval requires PLANNING.' }
        Assert-StageEvidence 'PLANNING'
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
            Invalidate-Provenance 'INVARIANT_VALIDATION' 'User fixes require fresh verification.'
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
        Assert-StageEvidence $state.stage
        $reportPath = Join-Path $dir $reports[$index]
        $reportText = Get-Content -LiteralPath $reportPath -Raw
        if ($reportText -notmatch '\AOutcome: PASS\r?\n' -or $reportText.Trim().Length -lt 40) {
            throw "Complete $($reports[$index]) with Outcome: PASS and evidence first."
        }
    } else {
        Invalidate-Provenance $To "Invalidated by rollback: $Reason"
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

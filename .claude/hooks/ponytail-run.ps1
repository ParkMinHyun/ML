# ponytail — thin hook launcher.
# Runs a hook's node script, forwarding stdin and exit code, so Claude Code
# hooks don't depend on node being on the launcher shell's PATH.
# ponytail: node missing -> no-op exit 0, matching the original plugin's graceful skip.
param([Parameter(Mandatory = $true)][string]$Script)

$node = (Get-Command node -ErrorAction SilentlyContinue).Source
if (-not $node) {
    foreach ($p in @("$env:ProgramFiles\nodejs\node.exe", "${env:ProgramFiles(x86)}\nodejs\node.exe")) {
        if (Test-Path -LiteralPath $p) { $node = $p; break }
    }
}
if (-not $node) { exit 0 }

& $node (Join-Path $PSScriptRoot $Script)
exit $LASTEXITCODE

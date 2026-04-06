param(
    [Parameter(Mandatory = $true)]
    [string]$Command,
    [string]$Experiment = "scaffold"
)

python "$PSScriptRoot\\..\\pipeline\\cli.py" $Command --experiment $Experiment


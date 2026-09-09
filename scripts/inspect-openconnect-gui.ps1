param(
    [Parameter(Mandatory = $true)]
    [int]$ProcessId
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$process = Get-Process -Id $ProcessId -ErrorAction Stop
$roots = @(if ($process.MainWindowHandle -ne [IntPtr]::Zero) {
    [System.Windows.Automation.AutomationElement]::FromHandle($process.MainWindowHandle)
} else {
    $processCondition = [System.Windows.Automation.PropertyCondition]::new(
        [System.Windows.Automation.AutomationElement]::ProcessIdProperty,
        $ProcessId
    )
    [System.Windows.Automation.AutomationElement]::RootElement.FindAll(
        [System.Windows.Automation.TreeScope]::Children,
        $processCondition
    )
})

if ($roots.Count -eq 0) {
    throw "No UI Automation windows found for process $ProcessId"
}

foreach ($root in $roots) {
    [PSCustomObject]@{
        Name = $root.Current.Name
        AutomationId = $root.Current.AutomationId
        ControlType = $root.Current.ControlType.ProgrammaticName
        IsEnabled = $root.Current.IsEnabled
        IsOffscreen = $root.Current.IsOffscreen
    }

    $elements = $root.FindAll(
        [System.Windows.Automation.TreeScope]::Descendants,
        [System.Windows.Automation.Condition]::TrueCondition
    )
    foreach ($element in $elements) {
        [PSCustomObject]@{
            Name = $element.Current.Name
            AutomationId = $element.Current.AutomationId
            ControlType = $element.Current.ControlType.ProgrammaticName
            IsEnabled = $element.Current.IsEnabled
            IsOffscreen = $element.Current.IsOffscreen
        }
    }
}

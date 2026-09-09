Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$root = [System.Windows.Automation.AutomationElement]::RootElement
$elements = $root.FindAll(
    [System.Windows.Automation.TreeScope]::Descendants,
    [System.Windows.Automation.Condition]::TrueCondition
)

foreach ($element in $elements) {
    if ($element.Current.Name -match 'OpenConnect') {
        [PSCustomObject]@{
            Name = $element.Current.Name
            AutomationId = $element.Current.AutomationId
            ControlType = $element.Current.ControlType.ProgrammaticName
            ProcessId = $element.Current.ProcessId
            IsEnabled = $element.Current.IsEnabled
            IsOffscreen = $element.Current.IsOffscreen
            NativeWindowHandle = $element.Current.NativeWindowHandle
        }
    }
}

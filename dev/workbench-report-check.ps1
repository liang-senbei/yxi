function Test-WorkbenchReports {
    param([Parameter(Mandatory)][string]$ReportDirectory, [Parameter(Mandatory)][string[]]$Classes)
    if ($Classes.Count -eq 0 -or @($Classes | Select-Object -Unique).Count -ne $Classes.Count) { throw 'Invalid workbench test selection' }
    $total = 0
    foreach ($class in $Classes) {
        if ($class -notmatch '^app\.yxi\.(desktop|agent)\.[A-Za-z0-9]+Test$') { throw 'Invalid workbench test class' }
        $path = Join-Path $ReportDirectory "TEST-$class.xml"
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing report: $class" }
        $settings = [System.Xml.XmlReaderSettings]::new()
        $settings.DtdProcessing = [System.Xml.DtdProcessing]::Prohibit
        $settings.XmlResolver = $null
        $reader = [System.Xml.XmlReader]::Create($path, $settings)
        try { $xml = [System.Xml.XmlDocument]::new(); $xml.XmlResolver = $null; $xml.Load($reader) } finally { $reader.Dispose() }
        $suite = $xml.DocumentElement
        if ($suite.LocalName -ne 'testsuite' -or $suite.GetAttribute('name') -ne $class) { throw "Wrong suite: $class" }
        $counts = @{}
        foreach ($attribute in @('tests', 'failures', 'errors', 'skipped')) {
            $raw = $suite.GetAttribute($attribute)
            $count = 0
            if ($raw -notmatch '^[0-9]+$' -or -not [int]::TryParse($raw, [ref]$count)) { throw "Invalid $attribute count: $class" }
            $counts[$attribute] = $count
        }
        $cases = @($suite.SelectNodes('testcase'))
        if ($counts.tests -lt 1 -or $counts.failures -ne 0 -or $counts.errors -ne 0 -or $counts.skipped -ne 0 -or
            $cases.Count -ne $counts.tests -or $suite.SelectNodes('.//failure|.//error|.//skipped').Count -ne 0) {
            throw "Workbench report not fully passed: $class"
        }
        $total += $counts.tests
    }
    return $total
}


$output = .\gradlew.bat -q printTestClasspath
$lines = $output -split "\r?\n"
$cp = ""
foreach ($l in $lines) {
    if ($l.StartsWith("TEST_CLASSPATH=")) {
        $cp = $l.Substring("TEST_CLASSPATH=".Length)
    }
}

# Resolve the user's true home profile path from the OS environment to bypass encoding mismatches
$userProfile = $env:USERPROFILE
$cp = $cp -replace "C:\\Users\\[^\\]+\\\.gradle", "$userProfile\.gradle"

Write-Host "Resolved Classpath: $cp"
java -cp $cp com.example.pythonrpg.engine.ManualTestRunnerKt

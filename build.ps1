param([Parameter(Mandatory=$true)][string]$Jdk, [Parameter(Mandatory=$true)][string]$R8Jar)
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
New-Item -ItemType Directory -Force "$taskRoot/tools/classes", "$taskRoot/dist" | Out-Null
& "$Jdk/bin/javac.exe" --release 8 -encoding UTF-8 -d "$taskRoot/tools/classes" "$taskRoot/src/PhoneBot.java"
if ($LASTEXITCODE) { throw 'javac failed' }
$taskClasses = @(Get-ChildItem "$taskRoot/tools/classes" -Filter '*.class' | ForEach-Object FullName)
& "$Jdk/bin/java.exe" -cp $R8Jar com.android.tools.r8.D8 --min-api 26 --output "$taskRoot/dist" @taskClasses
if ($LASTEXITCODE) { throw 'D8 failed' }
& "$Jdk/bin/java.exe" -cp "$taskRoot/tools/classes" PhoneBot --selftest
if ($LASTEXITCODE) { throw 'Selftest failed' }

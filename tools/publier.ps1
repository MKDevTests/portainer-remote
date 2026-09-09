<#
  Publie une version, en verifiant l'APK avant de l'envoyer.

  Ce script existe a cause d'une erreur reelle : la 1.6.0 a ete publiee avec
  l'APK de la 1.5.0. Le numero avait bien ete change dans build.gradle.kts, le
  commit et l'etiquette etaient justes, la signature etait bonne - mais la
  compilation incrementale n'avait pas refait le paquet, et personne n'avait lu
  ce que l'APK disait de lui-meme. L'application affichait donc une mise a jour
  qui, une fois installee, ne changeait rien.

  D'ou l'ordre impose ici : construire propre, LIRE la version dans le fichier,
  refuser si elle ne correspond pas, et seulement alors publier.

  Usage :
    .\publier.ps1 -Version 1.6.1
    .\publier.ps1 -Version 1.6.1 -Notes notes.md
#>
param(
  [Parameter(Mandatory = $true)][string]$Version,
  [string]$Notes,
  [string]$Titre
)

$ErrorActionPreference = "Stop"
$racine = Split-Path $PSScriptRoot -Parent
Set-Location $racine

function Echec($message) {
  Write-Host ""
  Write-Host "  REFUS : $message" -ForegroundColor Red
  exit 1
}

<#
  Lance un programme externe et ne juge que son code de sortie.

  Sans cela, la publication s'arretait au milieu : git ecrit son avancement sur
  la sortie d'erreur, et avec « ErrorActionPreference = Stop » PowerShell prend
  cette sortie pour un echec. La branche etait poussee, l'etiquette non, et la
  release restait a creer a la main - c'est arrive en publiant la 1.7.0.
#>
function Executer($programme, [string[]]$arguments) {
  $ancien = $ErrorActionPreference
  $ErrorActionPreference = "Continue"
  & $programme @arguments 2>&1 | ForEach-Object {
    Write-Host ("  {0}" -f $_) -ForegroundColor DarkGray
  }
  $code = $LASTEXITCODE
  $ErrorActionPreference = $ancien
  if ($code -ne 0) {
    Echec ("{0} {1} a echoue (code {2})" -f $programme, ($arguments -join " "), $code)
  }
}

# ------------------------------------------------------- ce qui est declare
$gradle = Get-Content "app\build.gradle.kts" -Raw
if ($gradle -notmatch 'versionName\s*=\s*"([^"]+)"') { Echec "versionName introuvable" }
$declaree = $Matches[1]
if ($gradle -notmatch 'versionCode\s*=\s*(\d+)') { Echec "versionCode introuvable" }
$code = [int]$Matches[1]

if ($declaree -ne $Version) {
  Echec "build.gradle.kts declare $declaree, la commande demande $Version"
}

Write-Host ""
Write-Host "Version declaree : $declaree (code $code)" -ForegroundColor Cyan

# ------------------------------------------------- construire, sans reliquat
# « clean » n'est pas une precaution de style : c'est exactement ce qui
# manquait le jour ou l'artefact precedent a ete republie.
Write-Host "Compilation propre..." -ForegroundColor Cyan
& .\gradlew clean assembleRelease -q
if ($LASTEXITCODE -ne 0) { Echec "la compilation a echoue" }

$apk = "app\build\outputs\apk\release\app-release.apk"
if (-not (Test-Path $apk)) { Echec "aucun APK produit" }

# ------------------------------------------- ce que l'APK dit de lui-meme
$outils = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" -Directory |
          Sort-Object Name | Select-Object -Last 1
$aapt2      = Join-Path $outils.FullName "aapt2.exe"
$apksigner  = Join-Path $outils.FullName "apksigner.bat"

$badging = & $aapt2 dump badging $apk | Select-Object -First 1
if ($badging -notmatch "versionCode='(\d+)'") { Echec "versionCode illisible dans l'APK" }
$codeApk = [int]$Matches[1]
if ($badging -notmatch "versionName='([^']+)'") { Echec "versionName illisible dans l'APK" }
$nomApk = $Matches[1]

Write-Host "APK produit      : $nomApk (code $codeApk)" -ForegroundColor Cyan

if ($nomApk -ne $declaree -or $codeApk -ne $code) {
  Echec "l'APK annonce $nomApk/$codeApk alors que le projet declare $declaree/$code"
}

# --------------------------------------------------------------- signature
$certs = & $apksigner verify --print-certs -v $apk
$empreinte = ($certs | Select-String "Signer #1 certificate SHA-256 digest:").ToString().Split(":")[1].Trim()
$attendue = "3c7d9196007ebafe2c87de764f4679a5c8710c6028d0862158c74c09c27fe212"
if ($empreinte -ne $attendue) {
  Echec "signee par une autre cle ($empreinte) : la mise a jour en place serait refusee"
}
if (-not ($certs | Select-String "v2 scheme \(APK Signature Scheme v2\): true")) {
  Echec "schema de signature v2 absent"
}
Write-Host "Signature        : identique aux versions precedentes" -ForegroundColor Green

# ----------------------------------------------------------------- publier
$fichier = Join-Path $env:TEMP "portainer-remote-$Version.apk"
Copy-Item $apk $fichier -Force

$etiquette = "v$Version"
Executer "git" @("tag", $etiquette)
Executer "git" @("push", "origin", "main")
Executer "git" @("push", "origin", $etiquette)

$titre = $Titre
if (-not $titre) { $titre = $Version }

if ($Notes -and (Test-Path $Notes)) {
  Executer "gh" @("release", "create", $etiquette, $fichier, "--title", $titre, "--notes-file", $Notes)
} else {
  Executer "gh" @("release", "create", $etiquette, $fichier, "--title", $titre, "--generate-notes")
}

Write-Host ""
Write-Host "Publie : $etiquette" -ForegroundColor Green

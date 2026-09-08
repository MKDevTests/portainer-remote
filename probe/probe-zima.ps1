<#
  Sonde une instance ZimaOS (ou CasaOS) et archive ce qu'elle expose.

  Deux phases, la premiere sans aucun identifiant :

    1. Extraction des routes. L'interface de ZimaOS est une application Vue dont
       les clients d'API sont generes : chaque route figure en clair dans les
       fichiers JavaScript. Les lire evite d'avoir a deviner une seule adresse.

    2. Sondage authentifie, en lecture seule et sur demande. Le mot de passe est
       saisi au clavier : jamais d'argument, jamais d'historique, jamais de
       fichier. Le jeton obtenu n'est jamais ecrit sur le disque.

  Usage :
    .\probe-zima.ps1 -BaseUrl http://192.168.1.30:85
    .\probe-zima.ps1 -BaseUrl http://192.168.1.30:85 -Auth
#>
param(
  [Parameter(Mandatory = $true)][string]$BaseUrl,
  [string]$Tag = "zima",
  [switch]$Auth,
  [switch]$SkipTls
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd("/")
$out = Join-Path $PSScriptRoot $Tag
if (-not (Test-Path $out)) { New-Item -ItemType Directory -Path $out | Out-Null }

if ($SkipTls) {
  Add-Type @"
using System.Net; using System.Security.Cryptography.X509Certificates;
public class TrustAllZima : ICertificatePolicy {
  public bool CheckValidationResult(ServicePoint s, X509Certificate c, WebRequest r, int p) { return true; }
}
"@
  [System.Net.ServicePointManager]::CertificatePolicy = New-Object TrustAllZima
  [System.Net.ServicePointManager]::SecurityProtocol  = [System.Net.SecurityProtocolType]::Tls12
}

# --------------------------------------------------------------- phase 1 : routes

Write-Host ""
Write-Host "Phase 1 - extraction des routes (aucun identifiant requis)" -ForegroundColor Cyan

$index = Invoke-WebRequest -Uri "$BaseUrl/" -UseBasicParsing -TimeoutSec 15
$assets = [System.Collections.Generic.HashSet[string]]::new()

foreach ($m in [regex]::Matches($index.Content, '(?:src|href)="([^"]+\.js)"')) {
  [void]$assets.Add($m.Groups[1].Value)
}
# Les imports dynamiques citent leurs voisins : un niveau de plus suffit a
# atteindre les modules d'API, qui ne sont pas charges par la page d'accueil.
foreach ($m in [regex]::Matches($index.Content, '"(\.?/assets/[^"]+\.js)"')) {
  [void]$assets.Add($m.Groups[1].Value)
}

Write-Host ("  {0} fichier(s) reference(s) par la page d'accueil" -f $assets.Count)

$routes = [System.Collections.Generic.HashSet[string]]::new()
$seen = [System.Collections.Generic.HashSet[string]]::new()
$queue = New-Object System.Collections.Queue
foreach ($a in $assets) { $queue.Enqueue($a) }

while ($queue.Count -gt 0) {
  $rel = $queue.Dequeue()
  if (-not $seen.Add($rel)) { continue }

  $url = $rel
  if (-not $rel.StartsWith("http")) {
    $url = "$BaseUrl/" + $rel.TrimStart("./").TrimStart("/")
  }

  try {
    $js = (Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 20).Content
  } catch {
    Write-Host ("  ignore {0}" -f $rel) -ForegroundColor DarkGray
    continue
  }

  foreach ($m in [regex]::Matches($js, '/v[12]/[A-Za-z0-9_\-/\.\{\}\$:]{2,80}')) {
    [void]$routes.Add($m.Value)
  }
  # Un seul niveau de descente : au-dela, on retelecharge toute l'application.
  if ($seen.Count -le $assets.Count) {
    foreach ($m in [regex]::Matches($js, '"(\.?/assets/[^"]+\.js)"')) {
      $queue.Enqueue($m.Groups[1].Value)
    }
  }
}

$sorted = $routes | Sort-Object
$sorted | Out-File (Join-Path $out "routes.txt") -Encoding utf8
Write-Host ("  {0} route(s) distinctes -> {1}" -f $sorted.Count, (Join-Path $out "routes.txt")) -ForegroundColor Green

Write-Host ""
Write-Host "  Routes de gestion d'applications :" -ForegroundColor Yellow
$sorted | Where-Object { $_ -match "app_management|compose|app_store" } | ForEach-Object { Write-Host "    $_" }

Write-Host ""
Write-Host "  Routes systeme et alimentation :" -ForegroundColor Yellow
$sorted | Where-Object { $_ -match "shutdown|reboot|restart|power|sleep|scheduled|usage|device|system" } |
  ForEach-Object { Write-Host "    $_" }

if (-not $Auth) {
  Write-Host ""
  Write-Host "Phase 2 ignoree. Relancer avec -Auth pour sonder en lecture seule." -ForegroundColor DarkGray
  return
}

# ------------------------------------------------- phase 2 : sondage authentifie

Write-Host ""
Write-Host "Phase 2 - sondage authentifie, lecture seule" -ForegroundColor Cyan

$user = Read-Host "Utilisateur ZimaOS"
$sec  = Read-Host "Mot de passe (invisible)" -AsSecureString
$token = $null

try {
  $pass = [System.Net.NetworkCredential]::new("", $sec).Password
  $body = @{ username = $user; password = $pass } | ConvertTo-Json
  $login = Invoke-RestMethod -Uri "$BaseUrl/v1/users/login" -Method Post `
                             -ContentType "application/json" -Body $body -TimeoutSec 15
  $pass = $null

  # On cherche le jeton sans jamais l'afficher ni l'ecrire.
  $flat = $login | ConvertTo-Json -Depth 10
  foreach ($m in [regex]::Matches($flat, '"(access_token|token)"\s*:\s*"([^"]{20,})"')) {
    $token = $m.Groups[2].Value
    break
  }

  if (-not $token) {
    Write-Host "  Connexion acceptee mais aucun jeton reconnu. Cles de la reponse :" -ForegroundColor Yellow
    ($login | Get-Member -MemberType NoteProperty).Name | ForEach-Object { Write-Host "    $_" }
    return
  }
  Write-Host "  Jeton obtenu (non affiche, non ecrit)." -ForegroundColor Green

  # Deux conventions coexistent selon les versions : on garde celle qui repond.
  $header = $null
  foreach ($candidate in @(@{ Authorization = $token }, @{ Authorization = "Bearer $token" })) {
    try {
      Invoke-RestMethod -Uri "$BaseUrl/v2/zimaos/device/info" -Headers $candidate -TimeoutSec 10 | Out-Null
      $header = $candidate
      break
    } catch { }
  }
  if (-not $header) {
    Write-Host "  Jeton refuse par /v2/zimaos/device/info. En-tete inconnu." -ForegroundColor Red
    return
  }
  Write-Host ("  En-tete retenu : {0}" -f $header.Authorization.Substring(0, [Math]::Min(7, $header.Authorization.Length)) + "...") -ForegroundColor Green

  # Seules les routes concretes sont interrogees : ni parametre de chemin, ni
  # verbe autre que GET. Une route qui repond 405 est simplement notee.
  $concrete = $sorted | Where-Object { $_ -notmatch '[\{\$:]' -and $_.Length -gt 6 }
  $report = @()

  foreach ($path in $concrete) {
    try {
      $r = Invoke-RestMethod -Uri "$BaseUrl$path" -Headers $header -Method Get -TimeoutSec 10
      $json = $r | ConvertTo-Json -Depth 6
      $json = [regex]::Replace($json, '("(?:access_token|refresh_token|token|password|secret)"\s*:\s*)"[^"]*"', '$1"<redige>"')
      $name = ($path.Trim("/") -replace "[^A-Za-z0-9]", "_")
      $json | Out-File (Join-Path $out "$name.json") -Encoding utf8
      $report += "OK    $path"
      Write-Host ("  OK    {0}" -f $path)
    } catch {
      $code = "?"
      if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
      $report += "$code   $path"
      Write-Host ("  {0,-5} {1}" -f $code, $path) -ForegroundColor DarkGray
    }
  }

  $report | Out-File (Join-Path $out "sondage.txt") -Encoding utf8
  Write-Host ""
  Write-Host ("Termine. Resultats dans {0}" -f $out) -ForegroundColor Green
  Write-Host "Le dossier probe/ est ignore par git : rien ne partira sur GitHub." -ForegroundColor DarkGray
} finally {
  # Le jeton et le mot de passe ne survivent pas au script.
  $token = $null
  $pass = $null
  [System.GC]::Collect()
}

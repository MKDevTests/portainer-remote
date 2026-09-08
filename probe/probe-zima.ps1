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

$queue = New-Object System.Collections.Queue
$seen  = [System.Collections.Generic.HashSet[string]]::new()

function Enqueue-Assets($text) {
  foreach ($m in [regex]::Matches($text, '(?:src|href)="([^"]+\.js)"')) { $queue.Enqueue($m.Groups[1].Value) }
  # Les bundles Vite citent leurs voisins en clair : c'est ainsi qu'on atteint
  # les modules d'API, qui ne sont pas tous charges par la page d'accueil.
  foreach ($m in [regex]::Matches($text, '["'']((?:\.{0,2}/)?assets/[^"'']+\.js)["'']')) { $queue.Enqueue($m.Groups[1].Value) }
}

Enqueue-Assets $index.Content

$corpus    = New-Object System.Text.StringBuilder
$bases     = [System.Collections.Generic.HashSet[string]]::new()
$endpoints = [System.Collections.Generic.HashSet[string]]::new()
$files     = 0

# Plafond volontaire : l'application entiere pese plusieurs mega-octets, et les
# modules d'API sont atteints bien avant.
while ($queue.Count -gt 0 -and $files -lt 80) {
  $rel = $queue.Dequeue()
  $key = $rel.TrimStart(".").TrimStart("/")
  if (-not $seen.Add($key)) { continue }

  $url = $rel
  if (-not $rel.StartsWith("http")) { $url = "$BaseUrl/" + $key }

  try {
    $js = (Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 20).Content
  } catch {
    continue
  }
  $files++
  [void]$corpus.Append($js)

  # 1. Les bases, passees au constructeur des clients generes.
  foreach ($m in [regex]::Matches($js, '/v[12]/[A-Za-z0-9_\-/\.]{2,60}')) { [void]$bases.Add($m.Value) }

  # 2. Les chemins relatifs, avec leur verbe. Un client genere ecrit le chemin
  #    puis, un peu plus loin, la methode : on lit les deux ensemble, sinon une
  #    liste de chemins sans verbe ne dit pas ce qui demarre quoi.
  foreach ($m in [regex]::Matches($js, '["''`](/[A-Za-z0-9_\-/\{\}\$\.]{2,70})["''`]')) {
    $path = $m.Groups[1].Value
    if ($path -match '\.(js|css|png|svg|jpg|json|woff2?)$') { continue }
    if ($path -match '^/(assets|node_modules)/') { continue }

    $verb = "?"
    $tail = $js.Substring($m.Index, [Math]::Min(400, $js.Length - $m.Index))
    $mv = [regex]::Match($tail, 'method\s*:\s*["'']([A-Za-z]+)["'']')
    if ($mv.Success) {
      $verb = $mv.Groups[1].Value.ToUpper()
    } else {
      $head = $js.Substring([Math]::Max(0, $m.Index - 160), [Math]::Min(160, $m.Index))
      $mh = [regex]::Match($head, '\.(get|post|put|patch|delete)\(\s*$')
      if ($mh.Success) { $verb = $mh.Groups[1].Value.ToUpper() }
    }
    [void]$endpoints.Add(("{0,-6} {1}" -f $verb, $path))
  }

  if ($files -le 20) { Enqueue-Assets $js }
}

Write-Host ("  {0} fichier(s) analyse(s)" -f $files)

$sorted = $bases | Sort-Object
$sorted | Out-File (Join-Path $out "routes.txt") -Encoding utf8

$eps = $endpoints | Sort-Object { $_.Substring(7) }
$eps | Out-File (Join-Path $out "endpoints.txt") -Encoding utf8
Write-Host ("  {0} base(s), {1} chemin(s) -> {2}" -f $sorted.Count, $eps.Count, $out) -ForegroundColor Green

Write-Host ""
Write-Host "  Bases d'API :" -ForegroundColor Yellow
$sorted | ForEach-Object { Write-Host "    $_" }

Write-Host ""
Write-Host "  Applications et compose :" -ForegroundColor Yellow
$eps | Where-Object { $_ -match "compose|app|store|container|image" } | ForEach-Object { Write-Host "    $_" }

Write-Host ""
Write-Host "  Systeme et alimentation :" -ForegroundColor Yellow
$eps | Where-Object { $_ -match "shutdown|reboot|restart|power|sleep|scheduled|usage|state|status|device|system" } |
  ForEach-Object { Write-Host "    $_" }

# Une route ne suffit pas : il faut savoir ce qu'on lui envoie. Le corps de la
# requete est ecrit a cote du chemin dans un client genere, donc on relit le
# voisinage plutot que de deviner un nom de champ ou une valeur d'enumeration.
$interesting = @(
  '/compose/\{[^}]+\}/status',
  '/sys/state/',
  '/scheduledoff',
  '/unapp/(start|stop)',
  '/disk/sleep'
)

$all = $corpus.ToString()
$ctx = @()
foreach ($pattern in $interesting) {
  $hits = [regex]::Matches($all, $pattern)
  $shown = 0
  foreach ($h in $hits) {
    if ($shown -ge 2) { break }
    $from = [Math]::Max(0, $h.Index - 260)
    $len  = [Math]::Min(560, $all.Length - $from)
    $ctx += "----- $pattern -----"
    $ctx += $all.Substring($from, $len)
    $ctx += ""
    $shown++
  }
}

if ($ctx.Count -gt 0) {
  $ctx | Out-File (Join-Path $out "context.txt") -Encoding utf8
  Write-Host ""
  Write-Host ("  Voisinage des routes decisives -> {0}" -f (Join-Path $out "context.txt")) -ForegroundColor Green
  Write-Host "  (il contient le nom du champ et les valeurs acceptees)" -ForegroundColor DarkGray
}

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

  # Une base ne repond a aucune question. Les inconnues de l'etude tiennent dans
  # des chemins complets, que la phase 1 ne reconstitue pas : les clients generes
  # concatenent une base et un chemin relatif, si bien qu'aucun des deux ne
  # figure entier dans le JavaScript. On les nomme donc ici, une fois.
  $questions = @(
    '/v2/app_management/apps/upgradable',
    '/v2/app_management/compose',
    '/v2/app_management/installed/list',
    '/v2/app_management/web/appgrid',
    '/v2/app_management/system/df',
    '/v2/zimaos/scheduledoff',
    '/v2/zimaos/device/info',
    '/v2/local_storage/disk/sleep',
    '/v1/sys/utilization',
    '/v1/sys/hardware'
  )

  $targets = @($concrete) + @($questions) | Select-Object -Unique
  $report = @()

  foreach ($path in $targets) {
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

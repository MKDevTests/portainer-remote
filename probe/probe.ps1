<#
  Sonde une instance Portainer et archive les reponses JSON dans ce dossier.
  Le jeton est saisi au clavier : jamais d'argument, jamais d'historique, jamais de fichier.

  Usage :
    .\probe.ps1
    .\probe.ps1 -BaseUrl https://mon-nas:9443 -Tag nas -SkipTls
#>
param(
  [string]$BaseUrl = "http://localhost:9000",
  [string]$Tag     = "local",
  [switch]$SkipTls
)

$ErrorActionPreference = "Stop"
$out = Join-Path $PSScriptRoot $Tag
if (-not (Test-Path $out)) { New-Item -ItemType Directory -Path $out | Out-Null }

if ($SkipTls) {
  Add-Type @"
using System.Net; using System.Security.Cryptography.X509Certificates;
public class TrustAll : ICertificatePolicy {
  public bool CheckValidationResult(ServicePoint s, X509Certificate c, WebRequest r, int p) { return true; }
}
"@
  [System.Net.ServicePointManager]::CertificatePolicy = New-Object TrustAll
  [System.Net.ServicePointManager]::SecurityProtocol  = [System.Net.SecurityProtocolType]::Tls12
}

$sec   = Read-Host "Jeton Portainer pour $BaseUrl" -AsSecureString
$token = [System.Net.NetworkCredential]::new("", $sec).Password
$H     = @{ "X-API-Key" = $token }

function Probe($name, $path) {
  $url = "$BaseUrl$path"
  try {
    $r = Invoke-RestMethod -Uri $url -Headers $H -TimeoutSec 10
    $r | ConvertTo-Json -Depth 8 | Out-File (Join-Path $out "$name.json") -Encoding utf8
    Write-Host ("  OK    {0,-14} {1}" -f $name, $path)
    return $r
  } catch {
    $code = "?"
    if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
    "{`"error`": `"$code`", `"path`": `"$path`"}" | Out-File (Join-Path $out "$name.json") -Encoding utf8
    Write-Host ("  HS {0,-4} {1,-14} {2}" -f $code, $name, $path) -ForegroundColor DarkYellow
    return $null
  }
}

Write-Host "`nSondage de $BaseUrl -> probe\$Tag`n"

# Regle 2 : on sonde, on ne suppose pas. La route de version a bouge entre les majeures.
$st = Probe "status" "/api/system/status"
if (-not $st) { $st = Probe "status_legacy" "/api/status" }

$eps = Probe "endpoints" "/api/endpoints"
Probe "endpoints_light" "/api/endpoints?excludeSnapshots=true" | Out-Null
Probe "stacks" "/api/stacks" | Out-Null

# Regle 2 encore : un parametre inconnu n'est pas rejete, il est ignore en silence.
# Le seul test fiable est donc la taille de la reponse, pas le code HTTP.
$full  = Join-Path $out "endpoints.json"
$light = Join-Path $out "endpoints_light.json"
if ((Test-Path $full) -and (Test-Path $light)) {
  $a = (Get-Item $full).Length
  $b = (Get-Item $light).Length
  Write-Host ""
  Write-Host ("  /api/endpoints                   {0,10:N0} octets" -f $a)
  Write-Host ("  ...?excludeSnapshots=true        {0,10:N0} octets" -f $b)
  if ($a -gt 0 -and $b -lt ($a * 0.5)) {
    $gain  = [math]::Round((1 - $b / $a) * 100, 1)
    $ratio = [math]::Round($a / $b)
    Write-Host ("  -> SUPPORTE : $gain % de charge en moins, rapport $ratio pour 1") -ForegroundColor Green
  } else {
    Write-Host "  -> IGNORE par cette version : meme charge, ne pas s'en servir" -ForegroundColor DarkYellow
  }
  Write-Host ""
}

# Regle 1 : l'endpointId est decouvert, jamais ecrit en dur.
if ($eps) {
  foreach ($e in @($eps)) {
    $id = $e.Id
    Write-Host ("`n  environnement {0} : {1} (type {2})" -f $id, $e.Name, $e.Type)
    Probe "containers_env$id" "/api/endpoints/$id/docker/containers/json?all=true" | Out-Null
    Probe "dockerinfo_env$id" "/api/endpoints/$id/docker/info" | Out-Null
  }
}

# Les evenements Docker. Reponse en JSON par lignes, pas en tableau : elle est
# donc lue en texte brut, et non par Invoke-RestMethod qui la refuserait.
#
# « until » est obligatoire : sans lui, Docker garde la connexion ouverte et
# diffuse indefiniment. Avec lui, la fenetre est bornee et la requete se termine.
function ProbeEvents($id) {
  $now   = [int][double]::Parse((Get-Date -UFormat %s))
  $since = $now - 86400
  $url   = "$BaseUrl/api/endpoints/$id/docker/events?since=$since&until=$now"
  try {
    $raw = (Invoke-WebRequest -Uri $url -Headers $H -TimeoutSec 30 -UseBasicParsing).Content
    # Les evenements portent les etiquettes des conteneurs : rien n'y est
    # secret en principe, mais on retire quand meme ce qui en aurait l'air.
    $raw = [regex]::Replace($raw, '("[^"]*(?i:password|passwd|token|secret|api_?key)[^"]*"\s*:\s*)"[^"]*"', '$1"<redige>"')
    $raw | Out-File (Join-Path $out "events_env$id.ndjson") -Encoding utf8
    $lignes = ($raw -split "`n" | Where-Object { $_.Trim() }).Count
    Write-Host ("  OK    events_env{0,-6} {1} evenements sur 24 h" -f $id, $lignes) -ForegroundColor Green
  } catch {
    $code = "?"
    if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
    Write-Host ("  HS {0,-4} events_env{1}" -f $code, $id) -ForegroundColor DarkYellow
  }
}

if ($eps) {
  Write-Host "`n  Evenements Docker (fenetre de 24 h, bornee)"
  foreach ($e in @($eps)) { ProbeEvents $e.Id }
}

$token = $null; $sec.Dispose()
Write-Host "`nTermine. JSON dans : $out`n"

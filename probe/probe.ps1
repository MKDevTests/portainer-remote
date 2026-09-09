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
# Deux precautions apprises a la dure :
#
#   1. L'horodatage vient de [DateTimeOffset]::UtcNow, pas de « Get-Date
#      -UFormat %s » : sous PowerShell 5.1, ce dernier rend l'heure locale
#      comme si elle etait UTC. Avec un fuseau a +2, « until » se retrouvait
#      deux heures dans le futur, et Docker attendait sagement cette heure-la
#      avant de fermer - le script semblait bloque alors qu'il obeissait.
#
#   2. La lecture est plafonnee. Une fenetre bornee devrait suffire, mais un
#      plafond garantit qu'aucune erreur de date ne fera plus attendre.
function ProbeEvents($id, $heures, $filtre, $nom) {
  $now   = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
  $since = $now - ($heures * 3600)
  $until = $now - 1
  $url   = "$BaseUrl/api/endpoints/$id/docker/events?since=$since&until=$until"
  if ($filtre) { $url += "&filters=" + [uri]::EscapeDataString($filtre) }

  $resp = $null; $reader = $null
  try {
    $req = [System.Net.HttpWebRequest]::Create($url)
    $req.Method           = "GET"
    $req.Timeout          = 20000
    $req.ReadWriteTimeout = 20000
    $req.Headers.Add("X-API-Key", $token)

    $resp   = $req.GetResponse()
    $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())

    $sb = New-Object System.Text.StringBuilder
    $lignes = 0
    while ($lignes -lt 2000 -and $sb.Length -lt 524288) {
      $line = $reader.ReadLine()
      if ($null -eq $line) { break }
      if ($line.Trim()) { [void]$sb.AppendLine($line); $lignes++ }
    }

    # Les evenements portent les etiquettes des conteneurs : rien n'y est
    # secret en principe, mais on retire quand meme ce qui en aurait l'air.
    $raw = [regex]::Replace($sb.ToString(), '("[^"]*(?i:password|passwd|token|secret|api_?key)[^"]*"\s*:\s*)"[^"]*"', '$1"<redige>"')
    $raw | Out-File (Join-Path $out "$nom`_env$id.ndjson") -Encoding utf8
    Write-Host ("  OK    {0,-16} {1,5} evenements sur {2} h" -f "$nom`_env$id", $lignes, $heures) -ForegroundColor Green
  } catch {
    $code = "?"
    if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
    Write-Host ("  HS {0,-4} {1}_env{2}  {3}" -f $code, $nom, $id, $_.Exception.Message) -ForegroundColor DarkYellow
  } finally {
    if ($reader) { $reader.Close() }
    if ($resp)   { $resp.Close() }
  }
}

# La premiere mesure a tranche : sur 24 h, 256 evenements, tous des sondes de
# sante (exec_create, exec_start, exec_die). Aucun demarrage, aucun arret. Un
# flux brut serait donc un mur de bruit ou l'on ne verrait jamais ce qui compte.
#
# On mesure donc deux choses : ce que sept jours contiennent vraiment, et si
# Docker sait filtrer lui-meme - auquel cas le tri se fait avant le reseau, pas
# apres.
$interessants = '{"type":["container","image","volume","network"],' +
                '"event":["start","stop","die","kill","restart","create","destroy",' +
                '"health_status","oom","pull","delete"]}'

if ($eps) {
  Write-Host "`n  Evenements Docker (fenetre fermee dans le passe)"
  foreach ($e in @($eps)) {
    ProbeEvents $e.Id 168 $null        "events_brut"
    ProbeEvents $e.Id 168 $interessants "events_filtres"
  }
}

$token = $null; $sec.Dispose()
Write-Host "`nTermine. JSON dans : $out`n"

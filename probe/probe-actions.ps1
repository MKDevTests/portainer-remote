<#
  Valide les DEUX affirmations non verifiees de l'etude :
    1. les routes start/stop de stack fonctionnent-elles, et que renvoient-elles ?
    2. les logs Docker sont-ils multiplexes (entete binaire de 8 octets) ?

  ATTENTION : ce script ARRETE puis REDEMARRE le stack demo-managed.
  Il refuse donc de tourner ailleurs que sur localhost, sauf -Force explicite.
  Le jeton est saisi au clavier, jamais ecrit.
#>
param(
  [string]$BaseUrl = "http://localhost:9000",
  [string]$Stack   = "demo-managed",
  [switch]$Force
)

$ErrorActionPreference = "Stop"

if ($BaseUrl -notmatch "localhost|127\.0\.0\.1" -and -not $Force) {
  Write-Host "REFUS : ce script arrete des conteneurs." -ForegroundColor Red
  Write-Host "Il ne vise que l'instance jetable. Relance avec -Force si c'est voulu."
  exit 1
}

Write-Host ""
Write-Host "  Ce script va ARRETER puis REDEMARRER le stack '$Stack' sur $BaseUrl" -ForegroundColor Yellow
$ok = Read-Host "  Continuer ? (o/N)"
if ($ok -ne "o") { Write-Host "  Annule."; exit 0 }

$sec   = Read-Host "`nJeton Portainer pour $BaseUrl" -AsSecureString
$token = [System.Net.NetworkCredential]::new("", $sec).Password
$H     = @{ "X-API-Key" = $token }

function Call($method, $path) {
  try {
    $r = Invoke-WebRequest -Uri "$BaseUrl$path" -Headers $H -Method $method -TimeoutSec 20 -UseBasicParsing
    return [PSCustomObject]@{ Code = [int]$r.StatusCode; Len = $r.RawContentLength; Body = $r }
  } catch {
    $c = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { -1 }
    return [PSCustomObject]@{ Code = $c; Len = 0; Body = $null }
  }
}

# --- decouverte, jamais d'id en dur ---
$stacks = Invoke-RestMethod -Uri "$BaseUrl/api/stacks" -Headers $H
$s = @($stacks) | Where-Object { $_.Name -eq $Stack } | Select-Object -First 1
if (-not $s) { Write-Host "Stack '$Stack' introuvable." -ForegroundColor Red; exit 1 }
$sid = $s.Id; $eid = $s.EndpointId
Write-Host "`n  stack $Stack -> Id=$sid  EndpointId=$eid  Status=$($s.Status)"

# --- 1. cycle stop / start ---
Write-Host "`n=== ROUTES DE STACK ===" -ForegroundColor Cyan
$r = Call POST "/api/stacks/$sid/stop?endpointId=$eid"
Write-Host ("  stop                       -> HTTP {0}" -f $r.Code)

$r = Call POST "/api/stacks/$sid/stop?endpointId=$eid"
Write-Host ("  stop (deja arrete)         -> HTTP {0}" -f $r.Code)

$after = Invoke-RestMethod -Uri "$BaseUrl/api/stacks/$sid" -Headers $H
Write-Host ("  Status apres arret         -> {0}" -f $after.Status)

# --- 2. logs : le stack est arrete, on relance d'abord ---
$r = Call POST "/api/stacks/$sid/start?endpointId=$eid"
Write-Host ("  start                      -> HTTP {0}" -f $r.Code)
Start-Sleep -Seconds 3
$after = Invoke-RestMethod -Uri "$BaseUrl/api/stacks/$sid" -Headers $H
Write-Host ("  Status apres demarrage     -> {0}" -f $after.Status)

# --- 3. conteneur : le 304 existe-t-il vraiment ? ---
Write-Host "`n=== ROUTES DE CONTENEUR ===" -ForegroundColor Cyan
$cts = Invoke-RestMethod -Uri "$BaseUrl/api/endpoints/$eid/docker/containers/json?all=true" -Headers $H
$ct = @($cts) | Where-Object { $_.Labels."com.docker.compose.project" -eq $Stack -and $_.State -eq "running" } | Select-Object -First 1
if (-not $ct) { Write-Host "  aucun conteneur en marche, on s'arrete la." -ForegroundColor DarkYellow; exit 0 }
$cid = $ct.Id
Write-Host ("  conteneur {0}" -f ($ct.Names -join ",").TrimStart("/"))

$r = Call POST "/api/endpoints/$eid/docker/containers/$cid/start"
Write-Host ("  start (deja en marche)     -> HTTP {0}   (304 attendu)" -f $r.Code)

# --- 4. logs : multiplexes ou texte brut ? ---
Write-Host "`n=== FORMAT DES LOGS ===" -ForegroundColor Cyan
$url = "$BaseUrl/api/endpoints/$eid/docker/containers/$cid/logs?stdout=1&stderr=1&tail=20&timestamps=1"
$w = Invoke-WebRequest -Uri $url -Headers $H -TimeoutSec 20 -UseBasicParsing
$ms = $w.RawContentStream; $ms.Position = 0
$buf = New-Object byte[] $ms.Length
[void]$ms.Read($buf, 0, $buf.Length)

Write-Host ("  octets recus               -> {0}" -f $buf.Length)
if ($buf.Length -eq 0) { Write-Host "  (vide : le conteneur n'a rien ecrit)" -ForegroundColor DarkYellow; exit 0 }

$hex = ($buf[0..([Math]::Min(23, $buf.Length - 1))] | ForEach-Object { $_.ToString("X2") }) -join " "
Write-Host "  24 premiers octets         -> $hex"

if ($buf[0] -le 2 -and $buf[1] -eq 0 -and $buf[2] -eq 0 -and $buf[3] -eq 0) {
  $flux = switch ($buf[0]) { 0 {"stdin"} 1 {"stdout"} 2 {"stderr"} }
  $taille = [int]$buf[4] * 16777216 + [int]$buf[5] * 65536 + [int]$buf[6] * 256 + [int]$buf[7]
  Write-Host "  -> MULTIPLEXE confirme : entete de 8 octets, flux=$flux, trame=$taille octets" -ForegroundColor Green
  Write-Host "     un demultiplexeur est obligatoire."
} else {
  Write-Host "  -> TEXTE BRUT : ce conteneur a un TTY, pas d'entete." -ForegroundColor Yellow
  Write-Host "     l'app doit gerer les deux cas."
}

$token = $null; $sec.Dispose()
Write-Host ""

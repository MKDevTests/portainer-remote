<#
  Sonde un NAS Synology (DSM 7) et archive ce qu'il expose.

  Deux phases, la premiere sans aucun identifiant :

    1. Catalogue des API. DSM publie lui-meme la liste de ses interfaces, leur
       chemin et leurs versions, sans authentification. C'est le seul endroit ou
       lire ce que cette machine sait faire : deviner un nom d'API ne marche
       jamais, les versions changent d'une mise a jour a l'autre.

    2. Sondage authentifie, en lecture seule et sur demande. Le mot de passe est
       saisi au clavier, transmis dans le corps d'une requete POST - jamais dans
       une adresse, qui finirait dans les journaux de DSM - et le jeton de
       session est ferme a la fin du script.

  Ce que ce script ne fera jamais, par construction :

    - aucune ecriture : ni redemarrage, ni extinction, ni reglage, ni paquet ;
    - aucune API hors de la liste blanche ci-dessous ;
    - aucun compte, aucun partage, aucun fichier, aucune definition de
      conteneur (les variables d'environnement d'un conteneur contiennent des
      mots de passe : la route qui les renvoie n'est pas appelee) ;
    - aucun numero de serie, adresse MAC, adresse IP ou identifiant materiel
      dans les fichiers ecrits : ils sont remplaces avant enregistrement.

  Usage :
    .\probe-syno.ps1 -BaseUrl https://192.168.1.40:5001 -SkipTls
    .\probe-syno.ps1 -BaseUrl https://192.168.1.40:5001 -SkipTls -Auth
#>
param(
  [Parameter(Mandatory = $true)][string]$BaseUrl,
  [string]$Tag = "syno",
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
public class TrustAllSyno : ICertificatePolicy {
  public bool CheckValidationResult(ServicePoint s, X509Certificate c, WebRequest r, int p) { return true; }
}
"@
  [System.Net.ServicePointManager]::CertificatePolicy = New-Object TrustAllSyno
  [System.Net.ServicePointManager]::SecurityProtocol  = [System.Net.SecurityProtocolType]::Tls12
}

# Ce qui est retire avant toute ecriture sur le disque. La liste vise ce qui
# identifie une machine ou une personne, pas ce qui decrit un materiel : le
# modele et la taille d'un disque restent lisibles, son numero de serie non.
$sensibles = 'access_token|refresh_token|token|synotoken|sid|password|passwd|secret|serial|serial_num|sn|mac|mac_address|uuid|unique|device_id|did|hostname|email|account|owner|ip|ipv6|gateway|dns'

function Write-Safe($object, $name) {
  $json = $object | ConvertTo-Json -Depth 8
  $json = [regex]::Replace($json, ('("(?:{0})"\s*:\s*)"[^"]*"' -f $sensibles), '$1"<redige>"')
  # Une adresse IPv4 peut apparaitre dans un champ au nom quelconque.
  $json = [regex]::Replace($json, '\b(?:\d{1,3}\.){3}\d{1,3}\b', '<ip>')
  $json | Out-File (Join-Path $out "$name.json") -Encoding utf8
}

function Read-Error($response) {
  if ($response -and $response.error -and $response.error.code) { return [int]$response.error.code }
  return 0
}

# --------------------------------------------------- phase 1 : catalogue des API

Write-Host ""
Write-Host "Phase 1 - catalogue des API (aucun identifiant requis)" -ForegroundColor Cyan

$catalogue = $null
try {
  $catalogue = Invoke-RestMethod -TimeoutSec 20 -Method Get `
    -Uri "$BaseUrl/webapi/query.cgi?api=SYNO.API.Info&version=1&method=query&query=all"
} catch {
  Write-Host ("  Injoignable : {0}" -f $_.Exception.Message) -ForegroundColor Red
  Write-Host "  Verifier l'adresse, le port (5000 en clair, 5001 en TLS) et -SkipTls." -ForegroundColor DarkGray
  return
}

if (-not $catalogue.success) {
  Write-Host ("  DSM refuse le catalogue (code {0})." -f (Read-Error $catalogue)) -ForegroundColor Red
  return
}

$apis = @()
foreach ($p in $catalogue.data.PSObject.Properties) {
  $apis += [pscustomobject]@{
    api     = $p.Name
    path    = $p.Value.path
    minVer  = $p.Value.minVersion
    maxVer  = $p.Value.maxVersion
  }
}
Write-Host ("  {0} API declarees." -f $apis.Count) -ForegroundColor Green

$apis | Sort-Object api | ForEach-Object {
  "{0,-45} {1,-22} v{2}-{3}" -f $_.api, $_.path, $_.minVer, $_.maxVer
} | Out-File (Join-Path $out "api_info.txt") -Encoding utf8

# Les API qui portent les reponses de l'etude, et leur presence sur ce DSM.
$attendues = @(
  'SYNO.API.Auth',
  'SYNO.Core.System',
  'SYNO.Core.System.Utilization',
  'SYNO.Storage.CGI.Storage',
  'SYNO.Core.Hardware.Hibernation',
  'SYNO.Core.Hardware.PowerSchedule',
  'SYNO.Core.Package',
  'SYNO.Docker.Container',
  'SYNO.Docker.Container.Resource'
)
Write-Host ""
foreach ($nom in $attendues) {
  $trouve = $apis | Where-Object { $_.api -eq $nom }
  if ($trouve) {
    Write-Host ("  present  {0,-34} {1} v{2}-{3}" -f $nom, $trouve.path, $trouve.minVer, $trouve.maxVer) -ForegroundColor Green
  } else {
    Write-Host ("  absent   {0}" -f $nom) -ForegroundColor DarkGray
  }
}

if (-not $Auth) {
  Write-Host ""
  Write-Host ("Termine. Catalogue dans {0}" -f $out) -ForegroundColor Green
  Write-Host "Relancer avec -Auth pour le sondage en lecture seule." -ForegroundColor DarkGray
  return
}

# ------------------------------------------------ phase 2 : sondage authentifie

function Find-Api($nom) { return ($apis | Where-Object { $_.api -eq $nom } | Select-Object -First 1) }

$authApi = Find-Api 'SYNO.API.Auth'
if (-not $authApi) {
  Write-Host "  SYNO.API.Auth absent : rien a sonder." -ForegroundColor Red
  return
}

Write-Host ""
Write-Host "Phase 2 - sondage en lecture seule" -ForegroundColor Cyan
Write-Host "  Utiliser de preference un compte dedie, sans droits d'administration." -ForegroundColor DarkGray
$user = Read-Host "  Utilisateur DSM"
$sec  = Read-Host "  Mot de passe" -AsSecureString

$sid = $null
try {
  $pass = [System.Net.NetworkCredential]::new("", $sec).Password

  # Le mot de passe voyage en corps de requete : place dans l'adresse, il serait
  # ecrit en clair dans les journaux de connexion de DSM.
  $form = @{
    api     = 'SYNO.API.Auth'
    version = [string]$authApi.maxVer
    method  = 'login'
    account = $user
    passwd  = $pass
    session = 'PortainerRemoteProbe'
    format  = 'sid'
  }

  $login = Invoke-RestMethod -TimeoutSec 20 -Method Post -Uri "$BaseUrl/webapi/$($authApi.path)" -Body $form

  # 403 : la double authentification est exigee. Le code arrive du telephone,
  # il n'est ni stocke ni reutilisable.
  if (-not $login.success -and (Read-Error $login) -eq 403) {
    $form.otp_code = Read-Host "  Code de verification en deux etapes"
    $login = Invoke-RestMethod -TimeoutSec 20 -Method Post -Uri "$BaseUrl/webapi/$($authApi.path)" -Body $form
    $form.otp_code = $null
  }
  $form.passwd = $null
  $pass = $null

  if (-not $login.success) {
    $code = Read-Error $login
    $raison = switch ($code) {
      400 { "identifiant ou mot de passe refuse" }
      401 { "compte desactive" }
      402 { "permission refusee" }
      404 { "code de verification refuse" }
      406 { "la double authentification est obligatoire sur ce compte" }
      407 { "adresse bloquee par le pare-feu DSM" }
      default { "code $code" }
    }
    Write-Host ("  Connexion refusee : {0}." -f $raison) -ForegroundColor Red
    return
  }

  $sid = $login.data.sid
  if (-not $sid) {
    Write-Host "  Connexion acceptee mais aucun identifiant de session reconnu." -ForegroundColor Yellow
    return
  }
  Write-Host "  Session ouverte (identifiant non affiche, non ecrit)." -ForegroundColor Green

  # Liste blanche stricte : chaque ligne est une question de l'etude. Rien
  # d'autre ne sera demande, et aucune de ces methodes n'ecrit.
  #
  #   SYNO.Docker.Container / list   : les noms et l'etat des conteneurs.
  #   SYNO.Docker.Container / get    : DELIBEREMENT ABSENT - il renvoie les
  #                                    variables d'environnement, donc des mots
  #                                    de passe.
  $questions = @(
    @{ api = 'SYNO.Core.System';               method = 'info';       nom = 'core_system_info' },
    @{ api = 'SYNO.Core.System.Utilization';   method = 'get';        nom = 'core_utilization' },
    @{ api = 'SYNO.Storage.CGI.Storage';       method = 'load_info';  nom = 'storage_load_info' },
    @{ api = 'SYNO.Core.Hardware.Hibernation'; method = 'get';        nom = 'hardware_hibernation' },
    @{ api = 'SYNO.Core.Hardware.PowerSchedule'; method = 'load';     nom = 'hardware_powerschedule' },
    @{ api = 'SYNO.Docker.Container';          method = 'list';       nom = 'docker_container_list' },
    @{ api = 'SYNO.Core.Package';              method = 'list';       nom = 'core_package_list' }
  )

  $report = @()
  Write-Host ""
  foreach ($q in $questions) {
    $cible = Find-Api $q.api
    if (-not $cible) {
      $report += "absent   {0}" -f $q.api
      Write-Host ("  absent   {0}" -f $q.api) -ForegroundColor DarkGray
      continue
    }

    $uri = "{0}/webapi/{1}?api={2}&version={3}&method={4}&_sid={5}" -f `
           $BaseUrl, $cible.path, $q.api, $cible.maxVer, $q.method, [uri]::EscapeDataString($sid)
    try {
      $r = Invoke-RestMethod -Uri $uri -Method Get -TimeoutSec 20
      if ($r.success) {
        Write-Safe $r $q.nom
        $report += "OK       {0} / {1}  (v{2})" -f $q.api, $q.method, $cible.maxVer
        Write-Host ("  OK       {0} / {1}" -f $q.api, $q.method) -ForegroundColor Green
      } else {
        $code = Read-Error $r
        # Une version trop recente est le refus le plus courant : on retente
        # une fois avec la plus ancienne que DSM declare.
        $uriMin = $uri -replace "version=$($cible.maxVer)", "version=$($cible.minVer)"
        $r2 = Invoke-RestMethod -Uri $uriMin -Method Get -TimeoutSec 20
        if ($r2.success) {
          Write-Safe $r2 $q.nom
          $report += "OK       {0} / {1}  (v{2})" -f $q.api, $q.method, $cible.minVer
          Write-Host ("  OK       {0} / {1}  (v{2})" -f $q.api, $q.method, $cible.minVer) -ForegroundColor Green
        } else {
          $report += "erreur {0}  {1} / {2}" -f $code, $q.api, $q.method
          Write-Host ("  erreur {0}  {1} / {2}" -f $code, $q.api, $q.method) -ForegroundColor DarkGray
        }
      }
    } catch {
      $report += "echec    {0} / {1}  {2}" -f $q.api, $q.method, $_.Exception.Message
      Write-Host ("  echec    {0} / {1}" -f $q.api, $q.method) -ForegroundColor DarkGray
    }
  }

  $report | Out-File (Join-Path $out "sondage.txt") -Encoding utf8
  Write-Host ""
  Write-Host ("Termine. Resultats dans {0}" -f $out) -ForegroundColor Green
  Write-Host "Le dossier probe/ est ignore par git : rien ne partira sur GitHub." -ForegroundColor DarkGray
} finally {
  # La session est fermee cote DSM, pas seulement oubliee ici.
  if ($sid) {
    try {
      Invoke-RestMethod -TimeoutSec 10 -Method Post -Uri "$BaseUrl/webapi/$($authApi.path)" -Body @{
        api     = 'SYNO.API.Auth'
        version = [string]$authApi.maxVer
        method  = 'logout'
        session = 'PortainerRemoteProbe'
        _sid    = $sid
      } | Out-Null
      Write-Host "Session DSM fermee." -ForegroundColor DarkGray
    } catch { }
  }
  $sid = $null
  $pass = $null
  [System.GC]::Collect()
}

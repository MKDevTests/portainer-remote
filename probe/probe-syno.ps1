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

  Le journal systeme fait partie des besoins. DSM en publie un - connexions,
  utilisateurs, acces - mais sous un nom d'API qui change d'une version a
  l'autre. Ce script ne devine pas ce nom : la phase 1 nomme simplement toutes
  les API dont le nom evoque un journal, et le contenu ne sera lu qu'apres,
  quand on saura laquelle repond. Un journal de connexions dit qui s'est
  connecte et depuis ou : il ne sera jamais archive en clair sans decision
  explicite.

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
if ($BaseUrl -notmatch '^https?://') { $BaseUrl = "http://$BaseUrl" }
$enClair = $BaseUrl.StartsWith("http://")
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

# Pour les journaux, on n'ecrit pas les valeurs : seulement la forme.
#
# Un journal de connexions nomme des personnes, des adresses, des machines.
# Pour ecrire l'application, savoir qu'un champ « user » existe et qu'il porte
# du texte suffit ; savoir qui il nomme ne sert a rien. Cette fonction rend donc
# l'arborescence des champs et le type de chacun, jamais leur contenu.
function Get-Shape($node, $prefix, $acc) {
  if ($null -eq $node) { [void]$acc.Add("$prefix : null"); return }

  if ($node -is [System.Collections.IEnumerable] -and $node -isnot [string]) {
    $items = @($node)
    [void]$acc.Add("$prefix[] : $($items.Count) entrees")
    if ($items.Count -gt 0) { Get-Shape $items[0] "$prefix[]" $acc }
    return
  }

  if ($node -is [psobject] -and $node.PSObject.Properties.Name.Count -gt 0 -and
      $node -isnot [string] -and $node -isnot [int] -and $node -isnot [bool]) {
    foreach ($prop in $node.PSObject.Properties) {
      $chemin = $prop.Name
      if ($prefix) { $chemin = "$prefix.$($prop.Name)" }
      Get-Shape $prop.Value $chemin $acc
    }
    return
  }

  $type = switch ($node.GetType().Name) {
    "String"  { "texte ($($node.Length) car.)" }
    "Boolean" { "booleen" }
    default   { "nombre" }
  }
  [void]$acc.Add("$prefix : $type")
}

function Write-Shape($object, $name) {
  $acc = New-Object System.Collections.ArrayList
  Get-Shape $object "" $acc
  $acc | Out-File (Join-Path $out "$name`_forme.txt") -Encoding utf8
  Write-Host ("        forme : {0} champs, valeurs non ecrites" -f $acc.Count) -ForegroundColor DarkGray
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

# Les API de journal, recensees sans etre appelees. C'est la reponse a « ou
# sont les connexions et les acces » : elle se lit dans le catalogue, pas dans
# une supposition sur le nom.
$journaux = $apis | Where-Object { $_.api -match '(?i)log|syslog|event|security|connection' }
Write-Host ""
if ($journaux) {
  Write-Host ("  {0} API de journal declarees :" -f @($journaux).Count) -ForegroundColor Cyan
  foreach ($j in ($journaux | Sort-Object api)) {
    Write-Host ("    {0,-45} {1} v{2}-{3}" -f $j.api, $j.path, $j.minVer, $j.maxVer)
  }
  $journaux | Sort-Object api | ForEach-Object {
    "{0,-45} {1,-22} v{2}-{3}" -f $_.api, $_.path, $_.minVer, $_.maxVer
  } | Out-File (Join-Path $out "api_journaux.txt") -Encoding utf8
} else {
  Write-Host "  Aucune API de journal declaree par ce DSM." -ForegroundColor DarkGray
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
if ($enClair) {
  # Dit une fois, sans empecher : sur un reseau prive ou dans un tunnel
  # chiffre, le port 5000 est un choix defendable. Sur un reseau partage, non.
  Write-Host ""
  Write-Host "  Attention : l'adresse est en http. Le mot de passe traversera le reseau" -ForegroundColor Yellow
  Write-Host "  en clair, sauf si ce reseau est deja chiffre (Tailscale, VPN). DSM ecoute" -ForegroundColor Yellow
  Write-Host "  aussi en TLS sur 5001 : .\probe-syno.ps1 -BaseUrl https://<hote>:5001 -SkipTls" -ForegroundColor Yellow
  Write-Host ""
  $suite = Read-Host "  Continuer quand meme ? (o/N)"
  if ($suite -notmatch '^(o|O|y|Y)') { return }
}
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
  #
  # On demande en meme temps un jeton d'appareil. C'est la question ouverte :
  # DSM le rend sous le nom « did », mais pas dans toutes les versions, et sans
  # lui l'application reclamerait un code a chaque session. Seuls les NOMS des
  # champs rendus sont affiches - jamais leurs valeurs.
  if (-not $login.success -and (Read-Error $login) -eq 403) {
    $form.otp_code            = Read-Host "  Code de verification en deux etapes"
    $form.enable_device_token = "yes"
    $form.device_name         = "Portainer Remote"
    $login = Invoke-RestMethod -TimeoutSec 20 -Method Post -Uri "$BaseUrl/webapi/$($authApi.path)" -Body $form
    $form.otp_code = $null

    if ($login.success -and $login.data) {
      $champs = ($login.data.PSObject.Properties).Name
      Write-Host ("  Champs rendus par la connexion : {0}" -f ($champs -join ", ")) -ForegroundColor Cyan
      $jeton = $champs | Where-Object { $_ -match '(?i)did|device' }
      if ($jeton) {
        Write-Host ("  JETON D'APPAREIL PRESENT sous « {0} » : un seul code suffira." -f ($jeton -join ", ")) -ForegroundColor Green
      } else {
        Write-Host "  AUCUN jeton d'appareil rendu : ce DSM reclamera un code a chaque session." -ForegroundColor Yellow
      }
      ($champs -join ", ") | Out-File (Join-Path $out "champs_connexion.txt") -Encoding utf8
    }
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

  # ------------------------------------------------------------- journaux
  #
  # Ce que le catalogue a revele : DSM publie bien ce que ZimaOS et Portainer CE
  # n'ont pas. Quatre API, et le nom de leur methode n'est ecrit nulle part - on
  # essaie donc les trois verbes de lecture, dans l'ordre, et on s'arrete au
  # premier qui repond.
  #
  # Aucun autre verbe n'est tente : « list », « get » et « load » ne modifient
  # rien. Et seule la forme est ecrite, jamais les valeurs : ces reponses
  # nomment des personnes et des adresses.
  $journaux = @(
    @{ api = 'SYNO.Core.CurrentConnection';        nom = 'connexions_en_cours' },
    @{ api = 'SYNO.SecurityAdvisor.LoginActivity'; nom = 'activite_connexion' },
    @{ api = 'SYNO.Core.SyslogClient.Log';         nom = 'journal_systeme' },
    @{ api = 'SYNO.Core.Security.AutoBlock';       nom = 'adresses_bloquees' }
  )

  Write-Host ""
  Write-Host "  Journaux (forme seulement, aucune valeur ecrite)" -ForegroundColor Cyan
  foreach ($j in $journaux) {
    $cible = Find-Api $j.api
    if (-not $cible) {
      Write-Host ("  absent   {0}" -f $j.api) -ForegroundColor DarkGray
      continue
    }

    $repondu = $false
    foreach ($methode in @('list', 'get', 'load')) {
      if ($repondu) { break }
      $uri = "{0}/webapi/{1}?api={2}&version={3}&method={4}&limit=20&_sid={5}" -f `
             $BaseUrl, $cible.path, $j.api, $cible.maxVer, $methode, [uri]::EscapeDataString($sid)
      try {
        $r = Invoke-RestMethod -Uri $uri -Method Get -TimeoutSec 20
        if ($r.success) {
          Write-Host ("  OK       {0} / {1}" -f $j.api, $methode) -ForegroundColor Green
          Write-Shape $r.data $j.nom
          $report += "OK       {0} / {1}  (forme seule)" -f $j.api, $methode
          $repondu = $true
        }
      } catch { }
    }
    if (-not $repondu) {
      Write-Host ("  aucune methode de lecture acceptee : {0}" -f $j.api) -ForegroundColor DarkYellow
      $report += "refus    {0}  (list, get, load)" -f $j.api
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
